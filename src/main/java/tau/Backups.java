package tau;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Snapshots of the data store (docs/SECURITY.md, "Backups"): taken before a
 * new build first opens the store, which is when a schema upgrade runs; before
 * relaunching into a new build; and when the owner asks (Back up now). Each is
 * a folder under {@code backups/} holding the encrypted store and what opening
 * it needs, and the last {@value #KEEP} are kept. The plaintext passphrase and
 * the token files are never copied.
 */
public final class Backups {

	private static final Logger log = LoggerFactory.getLogger(Backups.class);

	/** How many snapshots are kept; older ones go when a new one is made. */
	public static final int KEEP = 5;

	/** What a snapshot copies from the data folder, besides the store, when present. */
	static final List<String> FILES = List.of("identity.enc", "vault.salt", "config.json", "prefs.properties",
		"file-roots.json");

	/** What a snapshot never copies: plaintext secrets and transient key staging. */
	public static final Set<String> NEVER = Set.of("unlock.passphrase", "tool-token.json", "operator-token.json",
		"keys.enc");

	/** The file naming the build that last opened the store. */
	static final String LAST_BUILD = "last-build";

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private Backups() {
	}

	/** Where snapshots go: {@code backups/} in the data folder. */
	public static Path dir(Path home) {
		return home.resolve("backups");
	}

	/**
	 * Snapshots {@code store} and the files beside it in {@code home} into a
	 * new folder named for now and {@code reason}, then keeps the last
	 * {@value #KEEP}. A store open meanwhile should be flushed first; one
	 * restored from a running copy is repaired at the next start, as after an
	 * unclean close.
	 *
	 * @return the snapshot's folder
	 */
	public static Path snapshot(Path home, Path store, String reason) throws IOException {
		String name = LocalDateTime.now().format(STAMP) + "-" + reason.replaceAll("[^A-Za-z0-9.-]+", "-");
		Path to = dir(home).resolve(name);
		for (int i = 2; Files.exists(to); i++) to = dir(home).resolve(name + "-" + i);
		Files.createDirectories(to);
		if (store != null && Files.isRegularFile(store)) {
			Files.copy(store, to.resolve(store.getFileName()), StandardCopyOption.REPLACE_EXISTING);
		}
		for (String f : FILES) {
			Path from = home.resolve(f);
			if (Files.isRegularFile(from)) Files.copy(from, to.resolve(f), StandardCopyOption.REPLACE_EXISTING);
		}
		prune(home);
		log.info("Backed up the data store to {}", to);
		return to;
	}

	/** The snapshots there are, newest first. */
	public static List<Path> list(Path home) {
		List<Path> out = new ArrayList<>();
		Path d = dir(home);
		if (!Files.isDirectory(d)) return out;
		try (DirectoryStream<Path> s = Files.newDirectoryStream(d, Files::isDirectory)) {
			for (Path p : s) out.add(p);
		} catch (IOException e) {
			log.warn("Could not list backups: {}", e.toString());
		}
		out.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
		return out;
	}

	/** Keeps the newest {@value #KEEP} snapshots. */
	static void prune(Path home) {
		List<Path> all = list(home);
		for (Path old : all.subList(Math.min(KEEP, all.size()), all.size())) {
			try (var walk = Files.walk(old)) {
				for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
			} catch (IOException e) {
				log.warn("Could not remove the old backup {}: {}", old.getFileName(), e.toString());
			}
		}
	}

	/**
	 * Whether build {@code version} has not opened the store in {@code home}
	 * before: the moment for a snapshot, before any schema upgrade it brings.
	 * A first run, with no store yet, needs none.
	 */
	public static boolean upgradeDue(Path home, Path store, String version) {
		if (store == null || !Files.isRegularFile(store)) return false;
		try {
			Path f = home.resolve(LAST_BUILD);
			return !Files.isRegularFile(f) || !Files.readString(f).strip().equals(version);
		} catch (IOException e) {
			return true;
		}
	}

	/** Records that build {@code version} has opened the store. */
	public static void opened(Path home, String version) {
		try {
			Files.writeString(home.resolve(LAST_BUILD), version + "\n");
		} catch (IOException e) {
			log.warn("Could not record the build that opened the store: {}", e.toString());
		}
	}
}
