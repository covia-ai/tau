package tau.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The screenshot harness's verdict: an ERROR line in the walk's log fails it, other levels do not, and no log at all does. */
class ScreenShotsCheckTest {

	@TempDir
	Path temp;

	@Test
	void anErrorInTheLogFailsTheWalkAndOtherLevelsDoNot() throws Exception {
		Path log = temp.resolve("tau.log");
		Files.writeString(log, """
			2026-11-25T13:00:00.000Z INFO  [main] tau.Tau - Configuration loaded
			2026-11-25T13:00:01.000Z WARN  [main] tau.Tau - A warning is not a failure
			""");
		assertEquals(List.of(), ScreenShots.unexpectedErrors(log));

		Files.writeString(log, "2026-11-25T13:00:02.000Z ERROR [AWT-EventQueue-0] tau.Tau - Uncaught exception\n",
			java.nio.file.StandardOpenOption.APPEND);
		assertEquals(1, ScreenShots.unexpectedErrors(log).size());

		List<String> missing = ScreenShots.unexpectedErrors(temp.resolve("absent.log"));
		assertEquals(1, missing.size());
		assertTrue(missing.get(0).startsWith("no log at"), missing.get(0));
	}
}
