package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A process {@link WindowsProcess} starts gets exactly the arguments it was
 * given, and was started outside this process's job: from a Claude session,
 * whose job forbids breaking away, through WMI. Run from the desktop, a child
 * WMI starts joins the desktop's own job, which this process is also in, so
 * where it lands is not asserted here.
 * Starts one short-lived Java process; Windows only.
 */
class WindowsProcessTest {

	/** The child: writes its arguments after the first, one per line, to the file the first names. */
	public static void main(String[] args) throws Exception {
		Files.write(Path.of(args[0]), List.of(args).subList(1, args.length), StandardCharsets.UTF_8);
	}

	@Test
	void theChildGetsItsArgumentsAndLeavesTheJob(@TempDir Path dir) throws Exception {
		assumeTrue(WindowsProcess.isWindows(), "Windows only");
		List<String> args = List.of("plain", "two words", "", "quote\"inside", "trailing\\", "dir with space\\",
			"back\\slashes", "tab\there", "\\\"", "ünïcødé");
		Path out = dir.resolve("args with space.txt");
		List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
			"-cp", System.getProperty("java.class.path"), WindowsProcessTest.class.getName(), out.toString()));
		command.addAll(args);

		WindowsProcess child = WindowsProcess.start(command);
		assertTrue(child.brokeAway(), "started outside this process's job: by breaking away, or else through WMI");
		assertTrue(child.waitFor(60, TimeUnit.SECONDS), "it finished");
		assertFalse(child.isAlive());
		assertEquals(0, child.exitValue());
		assertEquals(args, Files.readAllLines(out, StandardCharsets.UTF_8));
	}
}
