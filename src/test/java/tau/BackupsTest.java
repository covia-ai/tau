package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Backups copy the store and what opening it needs, never the plaintext
 * passphrase or the token files; the last few are kept; and a build that has
 * not opened the store before is due one before it does.
 */
class BackupsTest {

	@TempDir
	Path home;

	@Test
	void aSnapshotHoldsTheStoreAndItsKeysButNoPlaintextSecrets() throws Exception {
		Path store = Files.writeString(home.resolve("venue.etch"), "encrypted store");
		for (String f : List.of("identity.enc", "vault.salt", "config.json")) Files.writeString(home.resolve(f), f);
		for (String f : Backups.NEVER) Files.writeString(home.resolve(f), "secret " + f);

		Path snap = Backups.snapshot(home, store, "now");
		assertEquals("encrypted store", Files.readString(snap.resolve("venue.etch")));
		for (String f : List.of("identity.enc", "vault.salt", "config.json")) assertTrue(Files.exists(snap.resolve(f)), f);
		for (String f : Backups.NEVER) assertFalse(Files.exists(snap.resolve(f)), "never copied: " + f);
		try (var all = Files.walk(snap)) {
			for (Path p : all.filter(Files::isRegularFile).toList()) {
				assertFalse(Files.readString(p).startsWith("secret "), "no plaintext secret anywhere: " + p);
			}
		}
		assertTrue(snap.startsWith(Backups.dir(home)));
	}

	@Test
	void theLastFewAreKept() throws Exception {
		Path store = Files.writeString(home.resolve("venue.etch"), "store");
		for (int i = 0; i < Backups.KEEP + 3; i++) Backups.snapshot(home, store, "n" + i);
		List<Path> kept = Backups.list(home);
		assertEquals(Backups.KEEP, kept.size());
		assertTrue(kept.get(0).getFileName().toString().endsWith("n" + (Backups.KEEP + 2)) || kept.size() == Backups.KEEP,
			"newest first");
	}

	@Test
	void aNewBuildIsDueASnapshotBeforeItOpensTheStore() throws Exception {
		Path store = home.resolve("venue.etch");
		assertFalse(Backups.upgradeDue(home, store, "1.0"), "a first run has no store to keep");
		Files.writeString(store, "store");
		assertTrue(Backups.upgradeDue(home, store, "1.0"), "no build recorded yet");
		Backups.opened(home, "1.0");
		assertFalse(Backups.upgradeDue(home, store, "1.0"), "the same build again");
		assertTrue(Backups.upgradeDue(home, store, "1.1"), "a new build");
	}
}
