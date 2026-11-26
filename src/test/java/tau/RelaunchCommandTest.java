package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launcher's {@code --relaunch} decides what to start and with what; the
 * starting itself is injected, so no process is started here.
 */
class RelaunchCommandTest {

	@TempDir
	Path home;

	/** What the starter was asked to start, once per call. */
	private record Started(Path jar, Path config, List<String> options) {
	}

	private final List<Started> started = new ArrayList<>();
	private final ByteArrayOutputStream errors = new ByteArrayOutputStream();

	private int run(Path jar, String... args) {
		PrintStream err = new PrintStream(errors, true, StandardCharsets.UTF_8);
		return RelaunchCommand.run(args, jar, new PrintStream(OutputStream.nullOutputStream()), err, (j, c, o) -> {
			started.add(new Started(j, c, o));
			return ProcessHandle.current();
		});
	}

	private Path config() {
		return home.resolve("config.json");
	}

	private void remember() throws Exception {
		Files.writeString(home.resolve("unlock.passphrase"), "stand-in");
	}

	private Path jar(String mainClass) throws Exception {
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
		Path jar = Files.createTempFile(home, "build-", ".jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
			// the manifest is enough
		}
		return jar;
	}

	@Test
	void aJarIsCopiedAndStartedToUnlockAndTakeOver() throws Exception {
		remember();
		Path build = jar(Main.class.getName());
		assertEquals(0, run(build, RelaunchCommand.OPTION, config().toString()));
		assertEquals(1, started.size());
		Started s = started.get(0);
		assertEquals(home.resolve(Relaunch.RUN_DIR), s.jar().getParent(), "a copy in the run directory runs, not the build");
		assertNotEquals(build, s.jar());
		assertTrue(Files.isRegularFile(s.jar()));
		assertEquals(config(), s.config());
		assertEquals(List.of("--unlock", "--takeover"), s.options());
	}

	@Test
	void fromClassesItStartsTheClassPath() throws Exception {
		remember();
		assertEquals(0, run(null, RelaunchCommand.OPTION, config().toString()));
		assertNull(started.get(0).jar(), "no jar to copy: the starter runs this program's classes");
	}

	@Test
	void withoutARememberedPassphraseNothingStarts() throws Exception {
		assertEquals(1, run(jar(Main.class.getName()), RelaunchCommand.OPTION, config().toString()));
		assertTrue(started.isEmpty());
		assertTrue(errors.size() > 0, "it says why");
	}

	@Test
	void aJarThatIsNotTausIsRefused() throws Exception {
		remember();
		assertEquals(1, run(jar("some.Other"), RelaunchCommand.OPTION, config().toString()));
		assertTrue(started.isEmpty());
	}

	@Test
	void otherOptionsAreAUsageError() throws Exception {
		remember();
		assertEquals(64, run(null, RelaunchCommand.OPTION, "--unlock"));
		assertEquals(64, run(null, RelaunchCommand.OPTION, config().toString(), config().toString()));
		assertTrue(started.isEmpty());
	}
}
