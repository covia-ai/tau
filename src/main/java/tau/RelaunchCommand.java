package tau;

import static tau.Tau.APP_NAME;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * The launcher's {@code --relaunch}: starts this program as a new
 * Tau that takes over from the one running, or simply starts when none is,
 * then exits. It is how a local tool, such as the improvement loop, brings a
 * build in without holding any login: the new process unlocks with the
 * remembered passphrase ({@code --unlock}) and signs the request that the
 * running one step aside with the venue's own key ({@code --takeover}), as
 * any takeover does. Without a remembered passphrase it refuses, since
 * nobody would be there to unlock.
 *
 * <p>A jar is first copied into the run directory ({@link Relaunch#stage}),
 * so the build can be replaced while its copy runs; a launch from classes
 * starts from its class path as it is. The new process is started outside
 * the caller's Windows job ({@link Relaunch#start}), so it outlives the
 * session that ran this.
 */
final class RelaunchCommand {

	static final String OPTION = "--relaunch";
	/** What the new process is started with: unattended unlock, and takeover without asking. */
	static final List<String> OPTIONS = List.of("--unlock", "--takeover");
	static final String USAGE = "Usage: java -jar tau.jar " + OPTION + " [path/to/config.json]";

	/** Starts the new process: from {@code jar}, or from this program's class path when it is null. */
	@FunctionalInterface
	interface Starter {
		ProcessHandle start(Path jar, Path config, List<String> options) throws Exception;
	}

	private RelaunchCommand() {
	}

	static int run(String[] args, PrintStream out, PrintStream errors) {
		return run(args, Relaunch.runningJar(), out, errors, (jar, config, options) -> (jar == null)
			? Relaunch.start(config, options).toHandle()
			: Relaunch.start(jar, config, options).toHandle());
	}

	/** As {@link #run(String[], PrintStream, PrintStream)}, for {@code jar} (null from classes) and {@code starter}. */
	static int run(String[] args, Path jar, PrintStream out, PrintStream errors, Starter starter) {
		Path config = AppConfig.DEFAULT_FILE;
		boolean hasConfig = false;
		for (String arg : args) {
			if (arg.equals(OPTION)) continue;
			if (arg.startsWith("--") || hasConfig) {
				errors.println(USAGE);
				return 64; // EX_USAGE
			}
			config = Path.of(arg).toAbsolutePath().normalize();
			hasConfig = true;
		}
		Path home = config.toAbsolutePath().normalize().getParent();
		if (!RememberedPassphrase.exists(home)) {
			errors.println("No passphrase is remembered in " + home + ", so a new " + APP_NAME
				+ " could not unlock by itself: unlock once with Remember me ticked.");
			return 1;
		}
		try {
			Path staged = (jar == null) ? null : Relaunch.stage(jar, home);
			ProcessHandle started = starter.start(staged, config, OPTIONS);
			out.println("Started " + APP_NAME + " as process " + started.pid()
				+ ((staged == null) ? " from this program's classes" : " from " + staged)
				+ "; it unlocks and takes over from any " + APP_NAME + " running. Its log says how it went.");
			return 0;
		} catch (Exception e) {
			errors.println("Could not start " + APP_NAME + ": " + e.getMessage());
			return 1;
		}
	}
}
