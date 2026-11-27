package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.etch.EtchRebuilder;

/**
 * Copy diagnostics carries nothing secret: every key-shaped string seeded into
 * a log is gone from the report, while the words around it stay. The log
 * viewer filters by level and text; the adapters come from the venue itself;
 * the posture reads the venue's configuration; store repair says what it found.
 */
class DiagnosticsTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@TempDir
	Path dir;

	/** Secrets of every shape the report must not carry. */
	static final List<String> SEEDED = List.of(
		"sk-ant-api03-SeededSeededSeededSeeded",
		"sk-proj-SeededSeededSeededSeededSeed",
		"AKIASEEDEDSEEDED1234",
		"ghp_SeededSeededSeededSeededSeededSeed",
		"xoxb-seeded-seeded-seeded",
		"cvtok_SeededSeededSeeded",
		"eyJhbGciOiJFZERTQSJ9.eyJzdWIiOiJzZWVkZWQifQ.c2VlZGVkc2lnbmF0dXJl",
		"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
		"hunter2-seeded-passphrase",
		"owner.seeded@example.com",
		"AIzaSeededSeededSeededSeededSeededSeed");

	@Test
	void noSeededSecretSurvivesTheReport() throws Exception {
		Path log = dir.resolve("tau.log");
		StringBuilder sb = new StringBuilder();
		sb.append("10:00:00.000 INFO  tau.Tau - started\n");
		sb.append("10:00:01.000 WARN  tau.Model - provider said 401 for key ").append(SEEDED.get(0)).append('\n');
		sb.append("10:00:02.000 INFO  tau.Tools - OpenAI ").append(SEEDED.get(1)).append(" and AWS ").append(SEEDED.get(2)).append('\n');
		sb.append("10:00:03.000 INFO  tau.Git - token ").append(SEEDED.get(3)).append(", slack ").append(SEEDED.get(4)).append('\n');
		sb.append("10:00:04.000 INFO  covia - Authorization: Bearer ").append(SEEDED.get(5)).append('\n');
		sb.append("10:00:05.000 INFO  covia - login ").append(SEEDED.get(6)).append('\n');
		sb.append("10:00:06.000 INFO  tau.Vault - seed ").append(SEEDED.get(7)).append('\n');
		sb.append("10:00:07.000 INFO  tau.Vault - passphrase=").append(SEEDED.get(8)).append('\n');
		sb.append("10:00:08.000 INFO  tau.Mail - to ").append(SEEDED.get(9)).append(" via ").append(SEEDED.get(10)).append('\n');
		sb.append("10:00:09.000 WARN  covia - Capability denied: requires secret/use on s/X. Your capabilities are: "
			+ "invoke on v/ops/, secret/extract on s/\n");
		sb.append("10:00:10.000 INFO  tau.Files - wrote ").append(System.getProperty("user.home")).append("/notes.md\n");
		Files.writeString(log, sb.toString());

		String report = Diagnostics.report("1.0", "0.13", "http://127.0.0.1:8085/", null, List.of("http: running"),
			Diagnostics.tail(log, Diagnostics.LOG_LINES));
		for (String secret : SEEDED) assertFalse(report.contains(secret), "leaked: " + secret);
		assertFalse(report.contains("Seeded"), "no fragment of a seeded key either");
		assertFalse(report.contains("secret/extract"), "no list of the caller's rights");
		assertFalse(report.contains(System.getProperty("user.home")), "the home folder is ~");
		assertTrue(report.contains("provider said 401"), "the words around a secret stay");
		assertTrue(report.contains("~/notes.md"));
		assertTrue(report.contains("Version: 1.0 · Covia 0.13"));
		assertTrue(report.contains("http: running"));
	}

	@Test
	void theLogViewerFiltersByLevelAndText() {
		String tail = "10:00 INFO  a - hello\n10:01 WARN  b - careful\n10:02 ERROR c - broken model\n10:03 INFO  d - model ok\n";
		assertEquals(4, lines(Diagnostics.filter(tail, "All", "")));
		assertEquals(2, lines(Diagnostics.filter(tail, "WARN", "")), "warnings and errors");
		assertEquals(1, lines(Diagnostics.filter(tail, "ERROR", "")));
		assertEquals(2, lines(Diagnostics.filter(tail, "All", "MODEL")), "text, ignoring case");
		assertEquals(1, lines(Diagnostics.filter(tail, "WARN", "model")));
	}

	@Test
	void theAdaptersComeFromTheVenueAndThePostureFromItsConfiguration() throws Exception {
		var engine = shared.venue().engine();
		ACell out = engine.jobs().invokeInternal("v/ops/venue/adapters", Maps.empty(), engine.venueContext())
			.get(20, TimeUnit.SECONDS);
		List<String> lines = Diagnostics.adapterLines(out);
		assertTrue(lines.stream().anyMatch(l -> l.startsWith("http")), String.valueOf(lines));
		assertTrue(lines.stream().anyMatch(l -> l.startsWith("tau") || l.startsWith("brightside")), "Tau's own too");

		Diagnostics.Posture p = Diagnostics.posture(AppConfig.defaultVenue(dir), true, false, false);
		assertEquals("127.0.0.1", p.bind());
		assertFalse(p.publicAccess());
		assertTrue(p.keepTokenKeys());
		assertTrue(p.keepToolLogin());
		assertTrue(p.lines().get(0).contains("this computer only"));
	}

	@Test
	void storeRepairSaysWhatItFound() {
		StoreRepair.Outcome none = new StoreRepair.Outcome(dir.resolve("venue.etch"), dir.resolve("venue.etch.unclean-1"),
			EtchRebuilder.Status.COMPLETE, 10, List.of());
		assertFalse(none.notice().contains("It found"));
		List<String> problems = List.of("bad cell at 0x10", "short index at 0x20", "a", "b", "c", "d", "e");
		StoreRepair.Outcome some = new StoreRepair.Outcome(dir.resolve("venue.etch"), dir.resolve("venue.etch.unclean-1"),
			EtchRebuilder.Status.PARTIAL, 10, problems);
		assertTrue(some.notice().contains("bad cell at 0x10") && some.notice().contains("and 2 more"), some.notice());
		assertTrue(some.summary().contains("short index at 0x20"), "the log names them too");
	}

	private static long lines(String s) {
		return s.lines().filter(l -> !l.isBlank()).count();
	}
}
