package tau.ui.character;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How much the character moves: Full, Reduced, or Off. The system property
 * {@value #PROPERTY} ({@code full}, {@code reduced}, or {@code off}) wins;
 * otherwise the operating system's own reduce-motion setting is read once,
 * and Full is assumed when it can't be read.
 *
 * <ul>
 * <li><b>Full</b>: crossfades, the settle, breathing on large slots, blinks, the
 * hop and jump, and sparks.</li>
 * <li><b>Reduced</b>: crossfades only; a still pose per beat; no loops, blinks,
 * hops, or sparks. The words still change.</li>
 * <li><b>Off</b>: an instant swap of still poses.</li>
 * </ul>
 */
public enum Motion {
	FULL, REDUCED, OFF;

	public static final String PROPERTY = "tau.motion";

	private static final Logger log = LoggerFactory.getLogger(Motion.class);
	private static volatile Motion current;

	/** The motion the app runs with. */
	public static Motion get() {
		Motion m = current;
		if (m == null) {
			synchronized (Motion.class) {
				if (current == null) current = find();
				m = current;
			}
		}
		return m;
	}

	/** Sets the motion from now on (a setting, or a test); null finds it again. */
	public static void set(Motion motion) {
		current = motion;
	}

	public boolean loops() {
		return this == FULL;
	}

	/** How long a crossfade between two poses takes. */
	public int crossfadeMillis() {
		return switch (this) {
			case FULL -> 320;
			case REDUCED -> 200;
			case OFF -> 0;
		};
	}

	static Motion find() {
		String named = System.getProperty(PROPERTY);
		if (named != null) {
			for (Motion m : values()) if (m.name().equalsIgnoreCase(named.strip())) return m;
		}
		return systemReducesMotion() ? REDUCED : FULL;
	}

	/** The OS's own setting, where there is one to read; false when it can't be read. */
	private static boolean systemReducesMotion() {
		if (java.awt.GraphicsEnvironment.isHeadless()) return false;
		String os = System.getProperty("os.name", "").toLowerCase();
		try {
			if (os.contains("mac")) {
				return "1".equals(run("defaults", "read", "com.apple.universalaccess", "reduceMotion"));
			}
			if (os.contains("linux")) {
				return "false".equals(run("gsettings", "get", "org.gnome.desktop.interface", "enable-animations"));
			}
		} catch (Exception e) {
			log.info("Could not read the system's reduce-motion setting; assuming full motion ({})", e.toString());
		}
		return false;
	}

	private static String run(String... command) throws Exception {
		Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
		String out;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			out = r.readLine();
		}
		if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
		return out == null ? "" : out.strip();
	}
}
