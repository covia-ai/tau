package tau.rooms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.ANumeric;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * The owner's view of collaboration rooms (docs/ROOMS.md), for the Rooms
 * screen: every room operation made in process as the acting principal in
 * person, so none leaves a job in Jobs; the list read from the rooms view at
 * {@code x/room}, which shows the owner every room; and a failure turned into
 * a plain kind the screen can say in its own words. A refusal keeps only the
 * ability that was refused and the room, never anything else the caller may
 * or may not do.
 */
public final class Rooms {

	/** The room operations, under {@code v/ops/room/}. */
	static final String OPS = "v/ops/room/";
	/** The longest a post may be, as the adapter caps it. */
	public static final int MAX_TEXT = 8000;
	/** The latest posts a plain read gives, and a page back. */
	public static final int PAGE = 20;
	/** A room's name: 1 to 64 lower-case letters, digits, and hyphens, starting with a letter or digit. */
	public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

	private static final long TIMEOUT_SECONDS = 30;
	private static final Pattern MAY_NOT = Pattern.compile("This request may not (create|post in|read) room ([a-z0-9-]+)");
	private static final Pattern NOT_OWNER = Pattern.compile("Only the owner of room ([a-z0-9-]+) moderates it");
	private static final Pattern NO_ADMIN = Pattern.compile("Moderating room ([a-z0-9-]+) takes admin/room");
	private static final Pattern DELETED = Pattern.compile("Room ([a-z0-9-]+) was deleted");
	private static final Pattern NOT_FOUND = Pattern.compile("No room named ([a-z0-9-]+)");
	private static final Pattern EXISTS = Pattern.compile("There is already a room named ([a-z0-9-]+)");

	/** A room as the list shows it: its settings and where its posts start and end, without the posts. */
	public record Room(String name, String owner, String description, boolean anyoneReads, boolean anyonePosts,
			String toldAgent, String toldSession, long created, long first, long end) {

		/** Whether {@code principal}, or one of their agents, owns it. */
		public boolean ownedBy(String principal) {
			return owner != null && (owner.equals(principal) || owner.startsWith(principal + ":g:"));
		}

		public boolean isPublic() {
			return anyoneReads || anyonePosts;
		}

		/** How many posts the room holds. */
		public long kept() {
			return Math.max(0, end - first);
		}
	}

	/** One post: its number, when, who posted it, the access token it came through (or null), and its text. */
	public record Post(long n, long ts, String from, String via, String text) {
	}

	/** Posts read from a room, oldest first, with the number of the oldest post it holds and the next it will take. */
	public record Page(String room, List<Post> posts, long first, long end) {
	}

	/** A recorded wait on a room, which Jobs shows and can cancel. */
	public record Wait(String jobId, String caller, String agent, long since) {
	}

	/** Why an operation did not do what was asked. */
	public enum Kind {
		/** The request lacked a right: {@link Failure#ability} says which. */
		REFUSED,
		/** The room was deleted, or was never there. */
		GONE,
		/** The input was not accepted: a name taken or malformed, a post too long. */
		INVALID,
		/** Anything else: the venue did not answer as expected. */
		ERROR
	}

	/** A failure in plain parts: what kind, the refused ability where one was, the room, and the adapter's words. */
	public record Failure(Kind kind, String ability, String room, String message) {
	}

	private final Engine engine;
	private final String principal;

	/**
	 * @param engine    the embedded venue's engine
	 * @param principal the acting principal's DID: the owner, or the operator when acting as it
	 */
	public Rooms(Engine engine, String principal) {
		this.engine = Objects.requireNonNull(engine, "engine");
		this.principal = Objects.requireNonNull(principal, "principal");
	}

	public String principal() {
		return principal;
	}

	private RequestContext inPerson() {
		return RequestContext.of(Strings.create(principal));
	}

	// ------------------------------------------------------------------
	// The list
	// ------------------------------------------------------------------

	/** The rooms view as it stands, for a watch that follows it: null when there are no rooms yet. */
	public ACell view() {
		try {
			return engine.resolvePath(Strings.create("x/room"), inPerson());
		} catch (Exception e) {
			return null;
		}
	}

	/** Every room in {@code view}, the room most recently posted in first, then by name. */
	public static List<Room> rooms(ACell view) {
		List<Room> out = new ArrayList<>();
		if (view instanceof Index<?, ?> index) {
			for (long i = 0; i < index.count(); i++) {
				MapEntry<?, ?> e = index.entryAt(i);
				Room r = room(String.valueOf(e.getKey()), RT.ensureMap((ACell) e.getValue()));
				if (r != null) out.add(r);
			}
		} else if (view instanceof AMap<?, ?> map) {
			for (long i = 0; i < map.count(); i++) {
				MapEntry<?, ?> e = map.entryAt(i);
				Room r = room(String.valueOf(e.getKey()), RT.ensureMap((ACell) e.getValue()));
				if (r != null) out.add(r);
			}
		}
		out.sort(Comparator.comparing(Room::name));
		return out;
	}

	/** The posts {@code view} holds for room {@code name} numbered from {@code from}, oldest first. */
	public static List<Post> postsFrom(ACell view, String name, long from) {
		AMap<AString, ACell> room = RT.ensureMap(RT.getIn(view, name));
		List<Post> out = new ArrayList<>();
		if (room == null || !(room.get(Strings.create("posts")) instanceof Index<?, ?> posts)) return out;
		for (long i = 0; i < posts.count(); i++) {
			Post p = post(RT.ensureMap((ACell) posts.entryAt(i).getValue()));
			if (p != null && p.n() >= from) out.add(p);
		}
		return out;
	}

	static Room room(String name, AMap<AString, ACell> r) {
		if (r == null) return null;
		AMap<AString, ACell> notify = RT.ensureMap(r.get(Strings.create("notify")));
		long end = num(r.get(Strings.create("next")), num(r.get(Strings.create("end")), 0));
		long first = num(r.get(Strings.create("first")), firstOf(r, end));
		AVector<ACell> rights = RT.ensureVector(r.get(Strings.create("public")));
		return new Room(name, str(r.get(Strings.create("owner"))), str(r.get(Strings.create("description"))),
			has(rights, "read"), has(rights, "post"),
			notify == null ? null : str(notify.get(Fields.AGENT_ID)),
			notify == null ? null : str(notify.get(Fields.SESSION_ID)),
			num(r.get(Strings.create("created")), 0), first, end);
	}

	private static long firstOf(AMap<AString, ACell> r, long end) {
		if (!(r.get(Strings.create("posts")) instanceof Index<?, ?> posts) || posts.isEmpty()) return end;
		return num(RT.getIn((ACell) posts.entryAt(0).getValue(), "n"), end);
	}

	// ------------------------------------------------------------------
	// Reading, waiting and posting
	// ------------------------------------------------------------------

	/** Up to {@code limit} posts from {@code start}, or the latest when {@code start} is null. */
	public Page read(String room, Long start, int limit) throws Exception {
		AMap<AString, ACell> in = Maps.of("room", room, "limit", (long) limit);
		if (start != null) in = in.assoc(Strings.create("start"), CVMLong.create(start));
		return page(call("read", in));
	}

	/**
	 * The posts after {@code after}, or the room's next post when it is null:
	 * at once if there are any, else when one arrives or {@code timeoutMillis}
	 * passes, with none. Made in process, so it leaves no job; cancel the
	 * future to stop waiting.
	 */
	public CompletableFuture<Page> await(String room, Long after, long timeoutMillis) {
		AMap<AString, ACell> in = Maps.of("room", room, "timeout", timeoutMillis, "limit", 200L);
		if (after != null) in = in.assoc(Strings.create("after"), CVMLong.create(after));
		return engine.jobs().invokeInternal(OPS + "wait", in, inPerson()).thenApply(Rooms::page);
	}

	/** Posts {@code text} as the acting principal, in person. */
	public Post post(String room, String text) throws Exception {
		return post(RT.ensureMap(call("post", Maps.of("room", room, "text", text))));
	}

	// ------------------------------------------------------------------
	// The owner's tools
	// ------------------------------------------------------------------

	/** Creates a room the acting principal owns. {@code toldAgent} null tells nobody; {@code toldSession} null starts a chat. */
	public Room create(String name, String description, boolean anyoneReads, boolean anyonePosts, String toldAgent,
			String toldSession) throws Exception {
		AMap<AString, ACell> in = settings(Maps.of("room", name), description, anyoneReads, anyonePosts, toldAgent,
			toldSession);
		return room(name, RT.ensureMap(call("create", in)));
	}

	/** Changes a room's description, public rights, and who is told; its posts stay as they are. */
	public Room update(String name, String description, boolean anyoneReads, boolean anyonePosts, String toldAgent,
			String toldSession) throws Exception {
		AMap<AString, ACell> in = settings(Maps.of("room", name), description, anyoneReads, anyonePosts, toldAgent,
			toldSession);
		return room(name, RT.ensureMap(call("update", in)));
	}

	/** Stops telling anyone of the room's posts. */
	public void stopTelling(String name) throws Exception {
		call("update", Maps.of("room", name, "notify", Maps.empty()));
	}

	/** Removes the posts numbered below {@code before}; returns how many went. */
	public long prune(String name, long before) throws Exception {
		return num(RT.getIn(call("prune", Maps.of("room", name, "before", before)), "pruned"), 0);
	}

	public void delete(String name) throws Exception {
		call("delete", Maps.of("room", name));
	}

	private static AMap<AString, ACell> settings(AMap<AString, ACell> in, String description, boolean anyoneReads,
			boolean anyonePosts, String toldAgent, String toldSession) {
		AVector<ACell> rights = Vectors.empty();
		if (anyoneReads) rights = rights.conj(Strings.create("read"));
		if (anyonePosts) rights = rights.conj(Strings.create("post"));
		in = in.assoc(Strings.create("description"), Strings.create(description == null ? "" : description.strip()))
			.assoc(Strings.create("public"), rights);
		if (toldAgent == null || toldAgent.isBlank()) return in.assoc(Strings.create("notify"), Maps.empty());
		AMap<AString, ACell> notify = Maps.of(Fields.AGENT_ID, toldAgent);
		if (toldSession != null && !toldSession.isBlank()) notify = notify.assoc(Fields.SESSION_ID, Strings.create(toldSession));
		return in.assoc(Strings.create("notify"), notify);
	}

	// ------------------------------------------------------------------
	// Waits recorded as jobs
	// ------------------------------------------------------------------

	/** The waits on {@code room} still open among the acting principal's jobs: what Jobs can cancel. */
	public List<Wait> waits(String room) {
		List<Wait> out = new ArrayList<>();
		Index<Blob, ACell> jobs;
		try {
			jobs = engine.jobs().getJobs(inPerson());
		} catch (Exception e) {
			return out;
		}
		for (long i = 0; i < jobs.count(); i++) {
			MapEntry<Blob, ACell> e = jobs.entryAt(i);
			AMap<AString, ACell> rec = RT.ensureMap(e.getValue());
			if (rec == null || !isWaitOn(rec, room)) continue;
			String status = str(rec.get(Fields.STATUS));
			if (!"STARTED".equals(status) && !"PENDING".equals(status)) continue;
			out.add(new Wait(e.getKey().toHexString(), str(rec.get(Fields.CALLER)), str(rec.get(Fields.AGENT_ID)),
				num(rec.get(Fields.CREATED), 0)));
		}
		return out;
	}

	/** Whether a job record is a wait on {@code room}. */
	public static boolean isWaitOn(AMap<AString, ACell> rec, String room) {
		String op = str(rec.get(Fields.OP));
		if (op == null || !(op.endsWith("room/wait") || op.endsWith("room:wait"))) return false;
		return room == null || room.equals(str(RT.getIn(rec.get(Fields.INPUT), "room")));
	}

	/** Cancels a recorded wait, as Jobs does. */
	public void cancel(String jobId) throws Exception {
		engine.jobs().cancelJob(Blob.fromHex(jobId), inPerson());
	}

	// ------------------------------------------------------------------
	// Failures
	// ------------------------------------------------------------------

	/** {@code t} as the screen says it: a refusal keeps only the refused ability and the room. */
	public static Failure failure(Throwable t) {
		Throwable root = t;
		while ((root instanceof ExecutionException || root instanceof CompletionException) && root.getCause() != null) {
			root = root.getCause();
		}
		Throwable deepest = root;
		while (deepest.getCause() != null && deepest.getCause() != deepest) deepest = deepest.getCause();
		String message = firstMessage(root);
		Matcher m;
		if ((m = MAY_NOT.matcher(message)).find()) {
			String ability = switch (m.group(1)) {
				case "create" -> "room/create";
				case "post in" -> "room/post";
				default -> "crud/read";
			};
			return new Failure(Kind.REFUSED, ability, m.group(2), null);
		}
		if ((m = NOT_OWNER.matcher(message)).find() || (m = NO_ADMIN.matcher(message)).find()) {
			return new Failure(Kind.REFUSED, "admin/room", m.group(1), null);
		}
		if ((m = DELETED.matcher(message)).find() || (m = NOT_FOUND.matcher(message)).find()) {
			return new Failure(Kind.GONE, null, m.group(1), null);
		}
		if ((m = EXISTS.matcher(message)).find()) return new Failure(Kind.INVALID, null, m.group(1), message);
		if (root instanceof AuthException || deepest instanceof AuthException) {
			// A refusal from the venue's own check: keep no list of what the caller may do.
			return new Failure(Kind.REFUSED, null, null, null);
		}
		if (root instanceof NotFoundException || deepest instanceof NotFoundException) {
			return new Failure(Kind.GONE, null, null, null);
		}
		if (root instanceof InvalidInputException || deepest instanceof InvalidInputException) {
			return new Failure(Kind.INVALID, null, null, message);
		}
		return new Failure(Kind.ERROR, null, null, message);
	}

	private static String firstMessage(Throwable t) {
		for (Throwable c = t; c != null; c = (c.getCause() == c) ? null : c.getCause()) {
			if (c.getMessage() != null && !c.getMessage().isBlank()) return c.getMessage();
		}
		return "";
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private ACell call(String op, ACell input) throws Exception {
		return engine.jobs().invokeInternal(OPS + op, input, inPerson()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	static Page page(ACell out) {
		List<Post> posts = new ArrayList<>();
		AVector<ACell> v = RT.ensureVector(RT.getIn(out, "posts"));
		if (v != null) for (ACell p : v) {
			Post post = post(RT.ensureMap(p));
			if (post != null) posts.add(post);
		}
		long end = num(RT.getIn(out, "end"), 0);
		return new Page(str(RT.getIn(out, "room")), posts, num(RT.getIn(out, "first"), end), end);
	}

	static Post post(AMap<AString, ACell> p) {
		if (p == null) return null;
		return new Post(num(p.get(Strings.create("n")), -1), num(p.get(Strings.create("ts")), 0),
			str(p.get(Strings.create("from"))), str(p.get(Strings.create("via"))), str(p.get(Strings.create("text"))));
	}

	private static boolean has(AVector<ACell> rights, String right) {
		if (rights == null) return false;
		for (ACell r : rights) if (right.equals(String.valueOf(r))) return true;
		return false;
	}

	private static String str(ACell c) {
		return (c == null) ? null : c.toString();
	}

	private static long num(ACell c, long otherwise) {
		return (c instanceof ANumeric n) ? n.longValue() : otherwise;
	}
}
