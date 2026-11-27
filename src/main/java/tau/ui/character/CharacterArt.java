package tau.ui.character;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * The character's art, read at run time from a pack outside the app: the
 * character is never in this repository or in a public build
 * (docs/CHARACTER.md). The pack's directory is named by the system property
 * {@value #PROPERTY} or, failing that, the environment variable {@value #ENV}.
 * With neither, the pack in the data folder's {@value #FOLDER} directory is
 * used when there is one. With none, or with a pack that is missing or
 * unreadable, {@link #get()} is {@link #NONE} and every slot shows the τ glyph
 * instead.
 *
 * <p>A pack is a directory holding {@code character.json} and its images:
 * per pose a transparent square base and, for poses whose eyes move, a
 * screen-blend sprite per eye with its box as fractions of the frame. Images
 * are decoded the first time a pose is shown, never on the start-up path.
 */
public final class CharacterArt {

	public static final String PROPERTY = "tau.character.dir";
	public static final String ENV = "TAU_CHARACTER_DIR";
	/** The pack's directory inside the data folder, used when nothing names one. */
	public static final String FOLDER = "character";
	/** The pack's index file. */
	public static final String INDEX = "character.json";

	private static final Logger log = LoggerFactory.getLogger(CharacterArt.class);

	/** No art: every slot falls back to the τ glyph. */
	public static final CharacterArt NONE = new CharacterArt(null, Map.of());

	/** One eye: its box as fractions {x0, y0, x1, y1} of the frame, and its sprite's file. */
	public record Eye(double[] box, String sprite) {
	}

	/** One pose as the pack names it. */
	public record PoseSpec(String name, String base, List<Eye> eyes) {
	}

	/** A pose's decoded images: the base and each eye's sprite, at the pack's size. */
	public record Pose(PoseSpec spec, BufferedImage base, List<BufferedImage> sprites) {
	}

	private static volatile CharacterArt current;
	private static volatile Path dataFolder;

	private final Path dir;
	private final Map<String, PoseSpec> specs;
	private final Map<String, Pose> decoded = new HashMap<>();

	private CharacterArt(Path dir, Map<String, PoseSpec> specs) {
		this.dir = dir;
		this.specs = specs;
	}

	/** The art the app runs with, found once. */
	public static CharacterArt get() {
		CharacterArt a = current;
		if (a == null) {
			synchronized (CharacterArt.class) {
				if (current == null) current = find();
				a = current;
			}
		}
		return a;
	}

	/** Uses {@code art} from now on: for tests, and for a pack named after start-up. */
	public static void use(CharacterArt art) {
		current = (art == null) ? NONE : art;
	}

	/**
	 * The data folder whose {@value #FOLDER} pack is used when neither the
	 * property nor the environment names one. Set before the first {@link #get()}.
	 */
	public static void useDataFolder(Path home) {
		dataFolder = home;
	}

	/** Where the data folder's pack goes: {@code <home>/}{@value #FOLDER}. */
	public static Path folderIn(Path home) {
		return home.resolve(FOLDER);
	}

	/** Whether the property or the environment names a pack, so the data folder's is not used. */
	public static boolean namedElsewhere() {
		return named(System.getProperty(PROPERTY), System.getenv(ENV)) != null;
	}

	/** Finds the pack named by the property or the environment, else the data folder's, or {@link #NONE}. */
	static CharacterArt find() {
		return find(System.getProperty(PROPERTY), System.getenv(ENV), dataFolder);
	}

	/**
	 * The pack named by {@code property}, else by {@code env}; with neither, the
	 * one in {@code home}'s {@value #FOLDER} directory when it has an index. A
	 * named pack wins even when it can't be read, so naming one is never
	 * silently overridden.
	 */
	static CharacterArt find(String property, String env, Path home) {
		String named = named(property, env);
		if (named != null) return load(Path.of(named));
		if (home == null) return NONE;
		Path dir = folderIn(home);
		// No pack in the data folder is the usual case: no log line for it.
		if (!Files.isRegularFile(dir.resolve(INDEX))) return NONE;
		return load(dir);
	}

	private static String named(String property, String env) {
		if (property != null && !property.isBlank()) return property;
		if (env != null && !env.isBlank()) return env;
		return null;
	}

	/** The pack in {@code dir}, or {@link #NONE} (logged once) when it is missing or unreadable. */
	public static CharacterArt load(Path dir) {
		Path index = dir.resolve(INDEX);
		if (!Files.isRegularFile(index)) {
			log.info("No character pack at the named directory: the τ glyph stands in");
			return NONE;
		}
		try {
			ACell json = JSON.parse(Files.readString(index));
			Map<String, PoseSpec> specs = new HashMap<>();
			ACell poses = RT.getIn(json, "poses");
			for (String name : Presence.POSES) {
				ACell p = RT.getIn(poses, name);
				if (p == null) continue;
				List<Eye> eyes = new ArrayList<>();
				ACell es = RT.getIn(p, "eyes");
				long n = (es == null) ? 0 : RT.count(es);
				for (long i = 0; i < n; i++) {
					ACell e = RT.nth(es, i);
					ACell b = RT.getIn(e, "box");
					double[] box = new double[4];
					for (int k = 0; k < 4; k++) box[k] = RT.ensureNumber(RT.nth(b, k)).doubleValue();
					eyes.add(new Eye(box, String.valueOf(RT.getIn(e, "sprite"))));
				}
				specs.put(name, new PoseSpec(name, String.valueOf(RT.getIn(p, "base")), List.copyOf(eyes)));
			}
			if (!specs.containsKey("rest")) {
				log.warn("The character pack has no rest pose: the τ glyph stands in");
				return NONE;
			}
			return new CharacterArt(dir, Map.copyOf(specs));
		} catch (Exception e) {
			log.warn("Could not read the character pack: the τ glyph stands in ({})", e.toString());
			return NONE;
		}
	}

	/** Whether there is art at all. */
	public boolean present() {
		return dir != null;
	}

	/** Whether the pack has {@code pose}. */
	public boolean has(String pose) {
		return specs.containsKey(pose);
	}

	/** The pose, decoded on first use, or null when the pack lacks it or it can't be read. */
	public synchronized Pose pose(String name) {
		if (!present() || name == null || !specs.containsKey(name)) return null;
		Pose p = decoded.get(name);
		if (p != null) return p;
		PoseSpec s = specs.get(name);
		try {
			BufferedImage base = argb(ImageIO.read(dir.resolve(s.base()).toFile()));
			List<BufferedImage> sprites = new ArrayList<>();
			for (Eye e : s.eyes()) sprites.add(argb(ImageIO.read(dir.resolve(e.sprite()).toFile())));
			p = new Pose(s, base, List.copyOf(sprites));
			decoded.put(name, p);
			return p;
		} catch (Exception e) {
			log.warn("Could not read the {} pose: the τ glyph stands in ({})", name, e.toString());
			return null;
		}
	}

	private static BufferedImage argb(BufferedImage src) {
		if (src == null) throw new IllegalStateException("not an image");
		if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
		BufferedImage b = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = b.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.drawImage(src, 0, 0, null);
		g.dispose();
		return b;
	}
}
