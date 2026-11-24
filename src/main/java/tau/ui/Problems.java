package tau.ui;

import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A failure as the owner should read it (docs/DESIGN.md, "fail loudly,
 * kindly"): what happened, what to try, and where the full line is in the
 * log, with the raw text kept behind Details. The raw text never goes into a
 * chat, a dialog or a toast by itself.
 *
 * <p>A refusal names only the refused ability and the resource; the list of
 * everything the caller may do, which the venue appends to its refusals, is
 * cut from both the message and the Details. Anything shaped like a provider
 * key is masked.</p>
 *
 * <p>The provider patterns are the model-key test's, so a key test and a
 * failed reply say the same thing about the same error.</p>
 */
public final class Problems {

	private static final Logger log = LoggerFactory.getLogger(Problems.class);

	/** Which failure it is. */
	public enum Kind {
		MODEL_AUTH, NO_KEY, QUOTA, RATE_LIMITED, MODEL_NOT_FOUND, PROVIDER_DOWN, VENUE_NOT_READY, TOOL_FAILED, REFUSED,
		NETWORK, OTHER
	}

	/**
	 * A failure described: {@code what} happened, what to {@code tryThis},
	 * {@code where} its log line is, and the raw {@code details}, cleaned.
	 * {@code ability} and {@code resource} are set for a refusal only.
	 */
	public record Problem(Kind kind, String what, String tryThis, String where, String details, String ability,
			String resource) {

		/** What happened and what to try, on one line: for a note, a toast or a dialog. */
		public String line() {
			return what + " " + tryThis;
		}
	}

	/** Where the log is, as the menus name it. */
	public static final String LOG = "Developer → Open logs folder, tau.log";

	private static final AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis() % 100_000);

	private Problems() {
	}

	/**
	 * Describes {@code t} and writes its raw text to the log once, under a
	 * reference the message gives, so the line can be found.
	 */
	public static Problem describe(Throwable t) {
		Throwable root = unwrap(t);
		String raw = raw(root);
		String ref = "problem " + Long.toString(SEQUENCE.incrementAndGet(), 36);
		Problem p = classify(raw, root, ref);
		log.warn("{}: {} ({})", ref, p.kind(), clean(raw));
		return p;
	}

	/** {@code raw} described, without logging: for tests and for text already logged. */
	public static Problem classify(String raw) {
		return classify(raw, null, null);
	}

	static Problem classify(String raw, Throwable root, String ref) {
		String text = (raw == null) ? "" : raw;
		String e = text.toLowerCase(Locale.ROOT);
		String type = (root == null) ? "" : root.getClass().getSimpleName().toLowerCase(Locale.ROOT);
		String where = (ref == null) ? "The log has the details: " + LOG + "."
			: "The log has the details: " + LOG + ", under " + ref + ".";
		String details = clean(text);

		Matcher refused = REFUSAL.matcher(text);
		if (type.equals("authexception") && !MODEL_HINT.matcher(e).find() || refused.find() || ROOM_REFUSAL.matcher(text).find()) {
			String[] ar = refusal(text);
			String ability = ar[0], resource = ar[1];
			String what = (ability == null) ? "Refused." : "Refused: " + ability + (resource == null ? "." : " on " + resource + ".");
			String tryThis = (ability == null) ? "This needs a permission that wasn't given."
				: "This needs " + ability + (resource == null ? "" : " on " + resource) + ", which wasn't given.";
			return new Problem(Kind.REFUSED, what, tryThis, where, details, ability, resource);
		}
		if (VENUE.matcher(e).find()) {
			return new Problem(Kind.VENUE_NOT_READY, "Your Home/venue isn't ready yet.",
				"Wait a moment and try again; if it keeps happening, restart " + tau.Tau.APP_NAME + ".", where, details, null, null);
		}
		if (NO_KEY.matcher(e).find()) {
			return new Problem(Kind.NO_KEY, "No model key is set.", "Add one in Settings → Model.", where, details, null, null);
		}
		if (RATE_PER_PERIOD.matcher(e).find()) return rateLimited(where, details);
		if (HTTP_AUTH.matcher(e).find() || FORBIDDEN.matcher(e).find() || BAD_KEY.matcher(e).find() && !NO_CREDIT.matcher(e).find()) {
			return new Problem(Kind.MODEL_AUTH, "The model provider didn't accept your key.",
				"Check the key in Settings → Model, or paste a new one.", where, details, null, null);
		}
		if (NO_CREDIT.matcher(e).find()) {
			return new Problem(Kind.QUOTA, "The model provider's account is out of credit or quota.",
				"Check billing on the provider's site, or choose another model in Settings → Model.", where, details, null, null);
		}
		if (RATE_LIMITED.matcher(e).find()) return rateLimited(where, details);
		if (MODEL_UNAVAILABLE.matcher(e).find()) {
			return new Problem(Kind.MODEL_NOT_FOUND, "That model isn't available to your key.",
				"Choose another model in Settings → Model.", where, details, null, null);
		}
		if (PROVIDER_ERROR.matcher(e).find()) {
			return new Problem(Kind.PROVIDER_DOWN, "The model provider had a problem on its side.",
				"Try again in a few minutes.", where, details, null, null);
		}
		if (TOOL.matcher(e).find()) {
			return new Problem(Kind.TOOL_FAILED, "A tool the assistant used failed.",
				"Ask it to try another way, or check that tool's settings.", where, details, null, null);
		}
		if (UNREACHABLE.matcher(e).find() || type.contains("unknownhost") || type.contains("connect")
			|| type.contains("timeout")) {
			return new Problem(Kind.NETWORK, "Couldn't reach the network.", "Check your connection and try again.",
				where, details, null, null);
		}
		return new Problem(Kind.OTHER, "Something went wrong.", "Try again; if it keeps happening, look in the log.", where,
			details, null, null);
	}

	private static Problem rateLimited(String where, String details) {
		return new Problem(Kind.RATE_LIMITED, "The model provider is busy or limiting how fast you can ask.",
			"Wait a minute, then send again.", where, details, null, null);
	}

	// ------------------------------------------------------------------
	// Refusals
	// ------------------------------------------------------------------

	/** Covia's capability refusal: "Capability denied: requires <ability> on <resource>". */
	private static final Pattern REFUSAL = Pattern.compile(
		"(?i)capability denied|requires ([a-z0-9_/:*-]+) on (\\S+)|denied by gate|not permitted");
	private static final Pattern REQUIRES = Pattern.compile("(?i)requires ([a-z0-9_/:*-]+) on (\\S+?)(?=[.,;]?(\\s|$))");
	/** The rooms' own refusals, and the venue's for an owner's moderation. */
	private static final Pattern ROOM_REFUSAL = Pattern.compile(
		"This request may not (create|post in|read) room ([a-z0-9-]+)|Only the owner of room ([a-z0-9-]+)");
	/** What the venue appends to a refusal: everything the caller may do. Never shown. */
	private static final Pattern RIGHTS = Pattern.compile("(?is)\\s*\\.?\\s*(Your capabilities are|Caller holds|holds \\[).*$");
	/** Words in an auth-typed failure that make it the model provider's, not a refusal. */
	private static final Pattern MODEL_HINT = Pattern.compile("api.?key|x-api-key|provider|anthropic|openai|model");

	/** {ability, resource} of a refusal, the resource shown from its path in the owner's namespace. */
	static String[] refusal(String text) {
		Matcher m = REQUIRES.matcher(text);
		if (m.find()) return new String[] { m.group(1), shortResource(m.group(2)) };
		Matcher r = ROOM_REFUSAL.matcher(text);
		if (r.find()) {
			if (r.group(3) != null) return new String[] { "admin/room", "room " + r.group(3) };
			String ability = switch (r.group(1)) {
				case "create" -> "room/create";
				case "post in" -> "room/post";
				default -> "crud/read";
			};
			return new String[] { ability, "room " + r.group(2) };
		}
		return new String[] { null, null };
	}

	/** {@code did:…:u:sam/s/KEY} as {@code s/KEY}: the path, not the owner's identity. */
	static String shortResource(String resource) {
		String r = resource.replaceAll("[.,;]+$", "");
		int slash = r.indexOf('/');
		if (r.startsWith("did:") && slash > 0) return r.substring(slash + 1);
		return r;
	}

	// ------------------------------------------------------------------
	// Patterns: the model-key test's, most specific first
	// ------------------------------------------------------------------

	private static final Pattern VENUE = Pattern.compile(
		"venue (is )?(not|isn't) (ready|running|started)|venue (is )?(starting|closed|closing|shut)|engine (is )?(closed|shut)|not (yet )?unlocked|no venue");
	private static final Pattern NO_KEY = Pattern.compile(
		"(api.?key|secret).{0,40}(missing|required|not (set|found|provided|configured))|no api.?key|missing (api.?key|credentials)");
	private static final Pattern RATE_PER_PERIOD = Pattern.compile(
		"rate.?limit (reached|exceeded)|per.?minute|per.?day limit|retry in \\d|retry after|requests per|tokens per");
	private static final Pattern HTTP_AUTH = Pattern.compile("\\b401\\b|authenticationexception|unauthori[sz]ed");
	private static final Pattern FORBIDDEN = Pattern.compile("\\b403\\b|permission denied|forbidden");
	private static final Pattern NO_CREDIT = Pattern.compile(
		"\\b402\\b|insufficient[_ ](quota|balance|credits?|funds)|credit balance|out of credits?|payment required|exceeded your current quota|set up billing|billing (is )?not (set up|enabled|active)|billing hard limit");
	private static final Pattern BAD_KEY = Pattern.compile(
		"invalid.{0,12}(api.?key|x-api-key|token|credentials)|incorrect api.?key|api.?key (is )?(not valid|invalid)"
		+ "|api.?key.{0,20}(expired|revoked|disabled)|(expired|revoked|disabled).{0,20}api.?key");
	private static final Pattern RATE_LIMITED = Pattern.compile(
		"\\b429\\b|\\b529\\b|ratelimitexception|rate.?limit|too many requests|overloaded");
	private static final Pattern MODEL_UNAVAILABLE = Pattern.compile(
		"modelnotfoundexception|model.{0,30}(not found|does not exist|not available|unknown|unsupported)|not_found_error|no such model|unknown model|cannot resolve operation: v/(ops|models)/");
	private static final Pattern PROVIDER_ERROR = Pattern.compile(
		"\\b50[0-4]\\b|internalserverexception|internal server error|service unavailable|bad gateway|gateway time-?out");
	private static final Pattern TOOL = Pattern.compile("tool (call )?(failed|error)|tool failure|toolexecution|tool_error");
	private static final Pattern UNREACHABLE = Pattern.compile(
		"unknownhost|unknown host|unresolvedmodelserver|(cannot|could not|unable to) resolve (host|hostname|address)|unresolvedaddress|connect(ion)? (refused|reset|timed out)|connectexception|timeoutexception|timed? ?out|unreachable|no route to host|network is unreachable|failed to connect");

	/** Anything shaped like a provider key: the key test's shape. */
	public static final Pattern KEY_SHAPED = Pattern.compile("\\b(sk|xai|gsk|AIza)[-_A-Za-z0-9]{12,}");

	// ------------------------------------------------------------------

	/**
	 * A file operation's failure in plain words, without the paths and
	 * internals the raw text carries (they go to the log).
	 */
	public static String fileLine(Throwable t) {
		Throwable root = unwrap(t);
		String line = switch (root) {
			case java.nio.file.NoSuchFileException e -> "That file or folder isn't there any more.";
			case java.nio.file.FileAlreadyExistsException e -> "There's already one with that name.";
			case java.nio.file.DirectoryNotEmptyException e -> "That folder isn't empty.";
			case java.nio.file.AccessDeniedException e -> tau.Tau.APP_NAME + " isn't allowed to change that.";
			case java.nio.file.FileSystemException e -> "The file system refused that.";
			default -> null;
		};
		if (line == null) return describe(root).line();
		log.warn("File operation failed: {}", clean(raw(root)));
		return line;
	}

	/** The raw text with the caller's rights cut and key-shaped strings masked: what Details shows. */
	public static String clean(String raw) {
		if (raw == null) return "";
		String out = RIGHTS.matcher(raw).replaceAll("").strip();
		return tau.KeyShapes.mask(KEY_SHAPED.matcher(out).replaceAll("•••"));
	}

	/** The innermost failure that says something, through the futures that wrap it. */
	static Throwable unwrap(Throwable t) {
		Throwable current = t;
		while ((current instanceof CompletionException || current instanceof ExecutionException
			|| current instanceof java.lang.reflect.InvocationTargetException) && current.getCause() != null
			&& current.getCause() != current) {
			current = current.getCause();
		}
		return current;
	}

	/** Every message down the cause chain, so a provider's words deep inside still count. */
	private static String raw(Throwable t) {
		StringBuilder sb = new StringBuilder();
		for (Throwable c = t; c != null && sb.length() < 4000; c = (c.getCause() == c) ? null : c.getCause()) {
			if (sb.length() > 0) sb.append(" | ");
			String m = c.getMessage();
			sb.append(c.getClass().getSimpleName());
			if (m != null && !m.isBlank()) sb.append(": ").append(m);
		}
		return sb.toString();
	}
}
