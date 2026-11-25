package tau;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.venue.Config;

/**
 * Copy diagnostics (Settings → Developer): what a bug report needs, in one
 * paste, with nothing secret in it. Versions, the platform, the safe-defaults
 * posture (what the venue lets in and what is kept on disk), the venue's
 * adapters, and the tail of the log, redacted: keys, tokens, signed logins,
 * seeds, passphrases, email addresses, and the home folder never survive
 * {@link #redact}. No Swing here.
 */
public final class Diagnostics {

	/** How many log lines the report carries. */
	public static final int LOG_LINES = 200;

	/** What the venue lets in and what is kept on disk, read from its configuration and the app's choices. */
	public record Posture(String bind, boolean usersAutoCreate, boolean publicAccess, boolean keepToolLogin,
			boolean keepOperatorLogin, boolean rememberedPassphrase, boolean keepTokenKeys, boolean auditLog) {

		/** One line per item, in plain words. */
		public List<String> lines() {
			return List.of(
				"Venue bound to: " + bind + (loopback() ? " (this computer only)" : " (reachable from the network)"),
				"Anonymous access: " + (publicAccess ? "on" : "off"),
				"New users created on first sign-in: " + (usersAutoCreate ? "yes" : "no"),
				"Login as you kept for local tools: " + (keepToolLogin ? "yes" : "no"),
				"Venue operator login kept for local tools: " + (keepOperatorLogin ? "yes" : "no"),
				"Passphrase remembered on this computer: " + (rememberedPassphrase ? "yes" : "no"),
				"Access token keys kept sealed by the venue: " + (keepTokenKeys ? "yes" : "no"),
				"Audit and access logs: " + (auditLog ? "on" : "off"));
		}

		boolean loopback() {
			return "127.0.0.1".equals(bind) || "localhost".equals(bind) || "::1".equals(bind);
		}
	}

	private Diagnostics() {
	}

	/** The posture of a venue run with {@code config}, and the app's own choices. */
	public static Posture posture(AMap<AString, ACell> config, boolean keepToolLogin, boolean keepOperatorLogin,
			boolean rememberedPassphrase) {
		String bind = String.valueOf(RT.getIn(config, Config.BIND_ADDRESS));
		boolean auto = CVMBool.TRUE.equals(RT.getIn(config, Config.USERS, Config.AUTO_CREATE));
		boolean pub = CVMBool.TRUE.equals(RT.getIn(config, Config.AUTH, Config.PUBLIC, Config.ENABLED));
		boolean keys = CVMBool.TRUE.equals(RT.getIn(config, Config.ADAPTERS, "token", "keepRawTokens"));
		boolean audit = CVMBool.TRUE.equals(RT.getIn(config, Config.LOGGING, Config.AUDIT));
		return new Posture(bind, auto, pub, keepToolLogin, keepOperatorLogin, rememberedPassphrase, keys, audit);
	}

	/** The report, redacted throughout. */
	public static String report(String tauVersion, String coviaVersion, String venueUrl, Posture posture,
			List<String> adapters, String logTail) {
		StringBuilder sb = new StringBuilder();
		sb.append(Tau.APP_NAME).append(" diagnostics\n\n");
		sb.append("Version: ").append(tauVersion).append(" · Covia ").append(coviaVersion).append('\n');
		sb.append("Platform: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
			.append(" (").append(System.getProperty("os.arch")).append("), Java ").append(System.getProperty("java.version"))
			.append('\n');
		sb.append("Venue: ").append(venueUrl == null ? "not running" : venueUrl).append("\n\n");
		sb.append("Safe defaults\n");
		if (posture != null) for (String line : posture.lines()) sb.append("- ").append(line).append('\n');
		sb.append("\nAdapters\n");
		for (String a : adapters) sb.append("- ").append(a).append('\n');
		sb.append("\nLog (last ").append(LOG_LINES).append(" lines)\n");
		sb.append(logTail == null ? "(no log)" : logTail).append('\n');
		return redact(sb.toString());
	}

	private static final List<Pattern> SECRETS = List.of(
		// Signed logins (JWTs): three base64url parts, the first a JSON header.
		Pattern.compile("eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}"),
		// Anything after a Bearer.
		Pattern.compile("(?i)(bearer)\\s+[A-Za-z0-9._~+/=-]{6,}"),
		// Covia access-token keys.
		Pattern.compile("\\bcvtok_[A-Za-z0-9_-]{6,}"),
		// Seeds and private keys written as long hex.
		Pattern.compile("\\b(0x)?[0-9a-fA-F]{48,}\\b"),
		// "passphrase=…", "password: …", "secret …", "apiKey=…" and the like.
		Pattern.compile("(?i)\\b(passphrase|password|secret|api[_-]?key|token)\\b(\\s*[=:]\\s*|\\s+)\"?[^\\s\",}]{4,}"),
		// Email addresses.
		Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"));

	/** {@code text} with every secret shape replaced: what may leave this computer in a bug report. */
	public static String redact(String text) {
		if (text == null) return "";
		String out = KeyShapes.mask(text);
		out = tau.ui.Problems.clean(out);
		for (Pattern p : SECRETS) {
			out = p.matcher(out).replaceAll(m -> {
				String g = m.group();
				if (g.toLowerCase(Locale.ROOT).startsWith("bearer")) return "Bearer •••";
				int sep = g.indexOf('=') > 0 ? g.indexOf('=') : g.indexOf(':');
				if (sep > 0 && sep < 16 && Character.isLetter(g.charAt(0)) && !g.contains("@")) return g.substring(0, sep + 1) + "•••";
				return "•••";
			});
		}
		String home = System.getProperty("user.home");
		if (home != null && home.length() > 1) out = out.replace(home, "~");
		return out;
	}

	/**
	 * The venue's adapters from {@code venue:adapters}, one line each: its name
	 * and, where the venue says, its status.
	 */
	public static List<String> adapterLines(ACell out) {
		List<String> lines = new ArrayList<>();
		ACell list = RT.getIn(out, "adapters");
		if (list == null) list = out;
		if (list instanceof convex.core.data.AMap<?, ?> m) {
			for (long i = 0; i < m.count(); i++) {
				var e = m.entryAt(i);
				lines.add(e.getKey() + ": " + status(e.getValue()));
			}
		} else if (list instanceof convex.core.data.AVector<?> v) {
			for (long i = 0; i < v.count(); i++) {
				ACell a = (ACell) v.get(i);
				ACell name = RT.getIn(a, "name");
				lines.add((name == null ? String.valueOf(a) : name.toString()) + ": " + status(a));
			}
		}
		java.util.Collections.sort(lines);
		return lines;
	}

	private static String status(ACell a) {
		for (String k : List.of("status", "state")) {
			ACell s = RT.getIn(a, k);
			if (s != null) return s.toString().toLowerCase(Locale.ROOT);
		}
		ACell enabled = RT.getIn(a, "enabled");
		if (enabled != null) return CVMBool.TRUE.equals(enabled) ? "enabled" : "disabled";
		return "present";
	}

	/** The last {@code lines} lines of {@code log}, or null when there is none. */
	public static String tail(Path log, int lines) {
		if (log == null || !Files.isRegularFile(log)) return null;
		try (RandomAccessFile f = new RandomAccessFile(log.toFile(), "r")) {
			long len = f.length();
			long start = Math.max(0, len - 256 * 1024);
			byte[] buf = new byte[(int) (len - start)];
			f.seek(start);
			f.readFully(buf);
			String text = new String(buf, StandardCharsets.UTF_8);
			List<String> all = new ArrayList<>(List.of(text.split("\\R")));
			if (start > 0 && !all.isEmpty()) all.remove(0); // a partial first line
			return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
		} catch (IOException e) {
			return null;
		}
	}

	/** {@code tail}'s lines at {@code level} or above and containing {@code filter}, for the log viewer. */
	public static String filter(String tail, String level, String filter) {
		if (tail == null) return "";
		int min = rank(level);
		String f = (filter == null) ? "" : filter.strip().toLowerCase(Locale.ROOT);
		StringBuilder sb = new StringBuilder();
		for (String line : tail.split("\\R")) {
			if (min > 0 && rank(levelOf(line)) < min) continue;
			if (!f.isEmpty() && !line.toLowerCase(Locale.ROOT).contains(f)) continue;
			sb.append(line).append('\n');
		}
		return sb.toString();
	}

	private static String levelOf(String line) {
		for (String l : List.of("ERROR", "WARN", "INFO", "DEBUG", "TRACE")) if (line.contains(" " + l + " ")) return l;
		return "INFO";
	}

	private static int rank(String level) {
		if (level == null) return 0;
		return switch (level.toUpperCase(Locale.ROOT)) {
			case "ERROR" -> 4;
			case "WARN" -> 3;
			case "INFO" -> 2;
			case "DEBUG" -> 1;
			default -> 0;
		};
	}
}
