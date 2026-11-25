package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code --self-test}: it refuses any folder but an empty one or its own, and
 * never the default data directory; a first run in a fresh folder passes every
 * step, and a second run there reopens the store it left and finds its chat.
 */
class SelfTestTest {

	@TempDir
	Path temp;

	@Test
	void itRefusesTheDefaultDataDirectoryAndOtherPeoplesFolders() throws Exception {
		assertNotNull(SelfTest.refusal(AppConfig.HOME), "never the real data directory");
		assertNull(SelfTest.refusal(temp.resolve("not-yet")), "a folder still to be made");
		assertNull(SelfTest.refusal(temp), "an empty folder");

		Path someone = Files.createDirectories(temp.resolve("someone"));
		Files.writeString(someone.resolve("config.json"), "{}");
		assertNotNull(SelfTest.refusal(someone), "a folder with something else in it");
		Files.writeString(someone.resolve(SelfTest.MARKER), "");
		assertNull(SelfTest.refusal(someone), "a folder an earlier self-test made");

		Path file = Files.writeString(temp.resolve("a-file"), "");
		assertNotNull(SelfTest.refusal(file), "not a folder");
	}

	@Test
	void theFlagNeedsAFolderAndRefusesWithoutStarting() {
		PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
		assertEquals(SelfTest.USAGE, SelfTest.run(new String[] { "--self-test" }, quiet, quiet));
		assertEquals(SelfTest.REFUSED, SelfTest.run(new String[] { "--self-test", AppConfig.HOME.toString() }, quiet, quiet));
	}

	@Test
	void aFirstRunPassesAndASecondReopensTheStoreItLeft() throws Exception {
		try (StoreTestDirectory dir = new StoreTestDirectory()) {
			Path folder = dir.path().resolve("self-test");
			ByteArrayOutputStream first = new ByteArrayOutputStream();
			assertEquals(SelfTest.PASSED, SelfTest.runIn(folder, new PrintStream(first, true, StandardCharsets.UTF_8)),
				first.toString(StandardCharsets.UTF_8));
			String report = first.toString(StandardCharsets.UTF_8);
			for (String step : new String[] { "venue", "loopback", "onboarding", "chat", "token", "token-revoked", "room", "stop", "close" }) {
				assertTrue(report.contains(" " + step + ":"), "step " + step + " was reported:\n" + report);
			}

			ByteArrayOutputStream second = new ByteArrayOutputStream();
			assertEquals(SelfTest.PASSED, SelfTest.runIn(folder, new PrintStream(second, true, StandardCharsets.UTF_8)),
				second.toString(StandardCharsets.UTF_8));
			String again = second.toString(StandardCharsets.UTF_8);
			assertTrue(again.contains("PASS history:") && again.contains("PASS clean-close:"), again);
			assertTrue(Files.readString(folder.resolve(SelfTest.REPORT)).contains("RESULT PASS"), "the report is written");
		}
	}
}
