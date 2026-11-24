package tau;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Which build this is: Tau's version and the Covia line it was built on, as
 * Maven wrote them into {@code tau/build.properties}. Shown in the About box so
 * a bug report can name the build; also right when run from source, unlike the
 * jar manifest.
 */
public final class BuildInfo {

	private static final Properties PROPERTIES = load();

	private BuildInfo() {}

	/** Tau's version, for example {@code 0.1.0-SNAPSHOT}. */
	public static String version() {
		return value("version");
	}

	/** The Covia version Tau was built against. */
	public static String coviaVersion() {
		return value("covia");
	}

	private static String value(String key) {
		String v = PROPERTIES.getProperty(key);
		// An unfiltered placeholder means the resource was not processed by Maven.
		return (v == null || v.isBlank() || v.startsWith("${")) ? "unknown" : v;
	}

	private static Properties load() {
		Properties p = new Properties();
		try (InputStream in = BuildInfo.class.getResourceAsStream("build.properties")) {
			if (in != null) p.load(in);
		} catch (IOException e) {
			// Leave it empty: every value reads as "unknown".
		}
		return p;
	}
}
