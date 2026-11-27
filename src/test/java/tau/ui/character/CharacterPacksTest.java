package tau.ui.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The data folder's character pack: found when nothing names another, a named
 * one winning even when broken, and Add character pack copying a folder or a
 * zip in only when it is a readable pack, never anything outside it. Stand-in
 * shapes only; the real art is never in this repository.
 */
class CharacterPacksTest {

	@TempDir
	Path dir;

	@Test
	void theDataFolderPackIsUsedWhenNothingNamesOne() throws IOException {
		Path home = dir.resolve("home");
		assertSame(CharacterArt.NONE, CharacterArt.find(null, null, null), "no data folder");
		assertSame(CharacterArt.NONE, CharacterArt.find(null, "", home), "no pack in the data folder");
		pack(CharacterArt.folderIn(home));
		assertTrue(CharacterArt.find(null, null, home).present(), "the data folder's pack");
		assertTrue(CharacterArt.find(" ", "", home).present(), "a blank name names nothing");

		Path named = dir.resolve("named");
		pack(named);
		assertTrue(CharacterArt.find(named.toString(), null, home).present(), "the property's pack");
		assertSame(CharacterArt.NONE, CharacterArt.find(dir.resolve("absent").toString(), null, home),
			"a named pack wins over the data folder's even when it is missing");
		assertSame(CharacterArt.NONE, CharacterArt.find(null, dir.resolve("absent").toString(), home),
			"so does one the environment names");

		Files.writeString(CharacterArt.folderIn(home).resolve(CharacterArt.INDEX), "{ not json");
		try (var logs = tau.ExpectedLogs.warn(CharacterArt.class, "Could not read the character pack")) {
			assertSame(CharacterArt.NONE, CharacterArt.find(null, null, home), "a broken pack: the glyph");
		}
	}

	@Test
	void addingAFolderOrAZipCopiesThePackIn() throws IOException {
		Path home = dir.resolve("home");
		Path folder = dir.resolve("pack");
		pack(folder);
		Files.writeString(folder.resolve(".DS_Store"), "x");
		Files.createDirectories(folder.resolve("extra"));
		CharacterArt art = CharacterPacks.install(folder, home);
		assertTrue(art.present() && art.has("thinking"));
		Path target = CharacterArt.folderIn(home);
		assertTrue(Files.isRegularFile(target.resolve(CharacterArt.INDEX)));
		assertFalse(Files.exists(target.resolve(".DS_Store")), "hidden files stay behind");
		assertFalse(Files.exists(target.resolve("extra")), "subdirectories stay behind");

		// A folder holding the pack one level down, as an unzipped download is.
		Path outer = dir.resolve("download");
		pack(outer.resolve("tau-character"));
		assertTrue(CharacterPacks.install(outer, home).present());

		// A zip whose pack sits in a directory, replacing the pack there.
		Path zip = dir.resolve("pack.zip");
		zip(folder, "tau-character/", zip, null);
		assertTrue(CharacterPacks.install(zip, home).present());
		assertTrue(CharacterArt.find(null, null, home).present());
		try (var files = Files.list(home)) {
			assertEquals(1, files.count(), "nothing left over beside the pack");
		}
	}

	@Test
	void whatIsNotAReadablePackLeavesTheOldOneInPlace() throws IOException {
		Path home = dir.resolve("home");
		Path good = dir.resolve("good");
		pack(good);
		CharacterPacks.install(good, home);
		Path index = CharacterArt.folderIn(home).resolve(CharacterArt.INDEX);
		String before = Files.readString(index);

		Path empty = Files.createDirectories(dir.resolve("empty"));
		assertThrows(CharacterPacks.NotAPack.class, () -> CharacterPacks.install(empty, home), "no index");
		assertThrows(CharacterPacks.NotAPack.class, () -> CharacterPacks.install(dir.resolve("absent"), home), "nothing there");
		Path text = Files.writeString(dir.resolve("notes.zip"), "not a zip");
		assertThrows(CharacterPacks.NotAPack.class, () -> CharacterPacks.install(text, home), "not a zip");

		Path noRest = dir.resolve("no-rest");
		pack(noRest);
		Files.writeString(noRest.resolve(CharacterArt.INDEX), "{\"poses\": {\"thinking\": {\"base\": \"thinking.png\"}}}");
		try (var logs = tau.ExpectedLogs.warn(CharacterArt.class, "The character pack has no rest pose")) {
			assertThrows(CharacterPacks.NotAPack.class, () -> CharacterPacks.install(noRest, home));
		}

		Path badImage = dir.resolve("bad-image");
		pack(badImage);
		Files.writeString(badImage.resolve("thinking.png"), "not a png");
		try (var logs = tau.ExpectedLogs.warn(CharacterArt.class, "Could not read the thinking pose")) {
			assertThrows(CharacterPacks.NotAPack.class, () -> CharacterPacks.install(badImage, home), "every pose is decoded first");
		}

		// A zip that tries to write outside the pack: the stray entry is never written.
		Path evil = dir.resolve("evil.zip");
		zip(good, "", evil, "../escaped.txt");
		CharacterPacks.install(evil, home);
		assertFalse(Files.exists(home.resolve("escaped.txt")));
		assertFalse(Files.exists(dir.resolve("escaped.txt")));

		assertEquals(before, Files.readString(index), "the pack there is untouched");
		try (var files = Files.list(home)) {
			assertEquals(1, files.count(), "no staging left behind");
		}
	}

	/** A stand-in pack of plain shapes in {@code to}. */
	static void pack(Path to) throws IOException {
		Files.createDirectories(to);
		for (String pose : new String[] { "rest", "thinking" }) {
			BufferedImage b = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = b.createGraphics();
			g.setColor(new Color(0x6B, 0x46, 0xC1));
			g.fillRect(8, 4, 16, 24);
			g.dispose();
			ImageIO.write(b, "png", to.resolve(pose + ".png").toFile());
		}
		Files.writeString(to.resolve(CharacterArt.INDEX), "{\"version\": 1, \"size\": 32, \"poses\": {"
			+ "\"rest\": {\"base\": \"rest.png\"}, \"thinking\": {\"base\": \"thinking.png\"}}}");
	}

	private static void zip(Path folder, String prefix, Path zip, String stray) throws IOException {
		try (OutputStream os = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(os)) {
			if (!prefix.isEmpty()) {
				z.putNextEntry(new ZipEntry(prefix));
				z.closeEntry();
			}
			try (var files = Files.list(folder)) {
				for (Path p : files.filter(Files::isRegularFile).toList()) {
					z.putNextEntry(new ZipEntry(prefix + p.getFileName()));
					Files.copy(p, z);
					z.closeEntry();
				}
			}
			if (stray != null) {
				z.putNextEntry(new ZipEntry(stray));
				z.write("outside".getBytes());
				z.closeEntry();
			}
		}
	}
}
