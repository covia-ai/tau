package tau.ui.character;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Puts a character pack into the data folder, where {@link CharacterArt}
 * finds it when nothing else names one (docs/CHARACTER.md). The source is the
 * pack's directory or a zip of it; either may hold the pack itself or one
 * directory that does. Only the pack's own files are taken: plain names,
 * no subdirectories, at most {@value #MAX_FILES} files and
 * {@value #MAX_BYTES} bytes. The pack is checked, every pose decoded,
 * before it replaces the one there; a pack that fails leaves the old one in
 * place.
 */
public final class CharacterPacks {

	static final int MAX_FILES = 64;
	static final long MAX_BYTES = 32L * 1024 * 1024;

	private CharacterPacks() {
	}

	/** Not a character pack, or one too large or with names outside itself. */
	public static final class NotAPack extends IOException {
		NotAPack(String message) {
			super(message);
		}
	}

	/**
	 * Copies the pack at {@code source} into {@code home}'s
	 * {@value CharacterArt#FOLDER} directory, replacing any pack there, and
	 * returns it loaded.
	 */
	public static CharacterArt install(Path source, Path home) throws IOException {
		Path target = CharacterArt.folderIn(home);
		Path staging = home.resolve("." + CharacterArt.FOLDER + "-new");
		Path old = home.resolve("." + CharacterArt.FOLDER + "-old");
		Files.createDirectories(home);
		deleteTree(staging);
		Files.createDirectories(staging);
		try {
			if (Files.isDirectory(source)) copyFolder(packFolder(source), staging);
			else if (Files.isRegularFile(source)) unzip(source, staging);
			else throw new NotAPack("There is nothing at " + source);
			CharacterArt art = CharacterArt.load(staging);
			if (!art.present()) throw new NotAPack("This is not a character pack: it needs a character.json with a rest pose");
			for (String pose : Presence.POSES) {
				if (art.has(pose) && art.pose(pose) == null) throw new NotAPack("The pack's " + pose + " pose can't be read");
			}
			deleteTree(old);
			if (Files.exists(target)) Files.move(target, old);
			Files.move(staging, target);
			deleteTree(old);
			return CharacterArt.load(target);
		} finally {
			deleteTree(staging);
		}
	}

	/** The directory holding the index: {@code dir} itself, or its only subdirectory that has one. */
	private static Path packFolder(Path dir) throws IOException {
		if (Files.isRegularFile(dir.resolve(CharacterArt.INDEX))) return dir;
		List<Path> inner = new ArrayList<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, Files::isDirectory)) {
			for (Path p : ds) if (Files.isRegularFile(p.resolve(CharacterArt.INDEX))) inner.add(p);
		}
		if (inner.size() != 1) throw new NotAPack("This folder has no character.json");
		return inner.get(0);
	}

	private static void copyFolder(Path from, Path to) throws IOException {
		int files = 0;
		long bytes = 0;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(from)) {
			for (Path p : ds) {
				String name = p.getFileName().toString();
				if (!Files.isRegularFile(p) || name.startsWith(".")) continue;
				if (++files > MAX_FILES) throw new NotAPack("The pack has too many files");
				bytes += Files.size(p);
				if (bytes > MAX_BYTES) throw new NotAPack("The pack is too large");
				Files.copy(p, to.resolve(name), StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private static void unzip(Path zip, Path to) throws IOException {
		try (ZipFile z = new ZipFile(zip.toFile())) {
			String prefix = null;
			List<? extends ZipEntry> entries = z.stream().filter(e -> !e.isDirectory()).toList();
			for (ZipEntry e : entries) {
				String n = e.getName();
				if (n.equals(CharacterArt.INDEX) || n.endsWith("/" + CharacterArt.INDEX)) {
					String p = n.substring(0, n.length() - CharacterArt.INDEX.length());
					if (prefix != null && !prefix.equals(p)) throw new NotAPack("The zip holds more than one character.json");
					prefix = p;
				}
			}
			if (prefix == null) throw new NotAPack("This zip has no character.json");
			int files = 0;
			long bytes = 0;
			for (ZipEntry e : entries) {
				String n = e.getName();
				if (!n.startsWith(prefix)) continue;
				String name = n.substring(prefix.length());
				if (name.isEmpty() || name.startsWith(".") || name.contains("/") || name.contains("\\") || name.contains(".."))
					continue;
				if (++files > MAX_FILES) throw new NotAPack("The pack has too many files");
				Path out = to.resolve(name).normalize();
				if (!out.getParent().equals(to.normalize())) throw new NotAPack("The zip names a file outside the pack");
				try (InputStream in = z.getInputStream(e)) {
					// Counted as read, not as the entry claims.
					long left = MAX_BYTES - bytes;
					long n2 = Files.copy(limited(in, left + 1), out, StandardCopyOption.REPLACE_EXISTING);
					bytes += n2;
					if (bytes > MAX_BYTES) throw new NotAPack("The pack is too large");
				}
			}
		} catch (java.util.zip.ZipException e) {
			throw new NotAPack("This is neither a folder nor a zip");
		}
	}

	private static InputStream limited(InputStream in, long max) {
		return new InputStream() {
			long left = max;

			@Override
			public int read() throws IOException {
				if (left <= 0) return -1;
				int b = in.read();
				if (b >= 0) left--;
				return b;
			}

			@Override
			public int read(byte[] buf, int off, int len) throws IOException {
				if (left <= 0) return -1;
				int r = in.read(buf, off, (int) Math.min(len, left));
				if (r > 0) left -= r;
				return r;
			}
		};
	}

	private static void deleteTree(Path dir) throws IOException {
		if (!Files.exists(dir)) return;
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
		}
	}
}
