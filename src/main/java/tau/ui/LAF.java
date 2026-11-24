package tau.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.swing.LookAndFeel;
import javax.swing.UIDefaults;
import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.IntelliJTheme;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.intellijthemes.FlatAllIJThemes;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import com.formdev.flatlaf.util.ColorFunctions;

import tau.ui.components.Theme;

/**
 * Look and feel: Tau's own Dark (the default) and Light, then the themes
 * FlatLaf provides — its own six, the IntelliJ theme
 * pack, and any {@code .theme.json} in the same format the owner drops into
 * the data home's {@code themes} folder — each restyled by Tau's own UI
 * defaults and set in the bundled Inter at a comfortable size. {@link #init}
 * once before any Swing component exists; {@link #apply} to switch the running
 * app (Settings → Theme).
 *
 * <p>Everything about Tau's restyling — the rounded geometry, the slim
 * scrollbars, the named style classes the components wear — lives in
 * {@code src/main/resources/tau/ui/FlatLaf.properties}, which FlatLaf
 * loads after any theme's own defaults. On FlatLaf's core themes the accent is
 * the theme variable {@code @accentColor}, handed over as a global extra
 * default: Tau's purple, or the colour chosen in Settings. Themes in
 * the IntelliJ format bring their own accent, as in FlatLaf's demo. Colours are
 * read back through {@link tau.ui.components.Theme}.
 */
public final class LAF {

	private static final Logger log = LoggerFactory.getLogger(LAF.class);

	/**
	 * A theme on offer. {@code core} themes are FlatLaf's own and take an
	 * accent; the rest set their own colours.
	 */
	public record Choice(String id, String name, String group, boolean dark, boolean core, Supplier<LookAndFeel> laf) {
		@Override
		public String toString() {
			return name;
		}
	}

	public static final String GROUP_TAU = "Tau";
	public static final String GROUP_FLATLAF = "FlatLaf";
	public static final String GROUP_INTELLIJ = "IntelliJ";
	public static final String GROUP_OWN = "Yours";

	/** The two modes: every theme is one or the other, and the owner switches between them. */
	public static final String DARK = "dark";
	public static final String LIGHT = "light";
	/** "Match the system": dark or light as the operating system is set. */
	public static final String SYSTEM = "system";

	/** The mode to show for a chosen one: {@link #SYSTEM} resolves to the operating system's. */
	public static String effective(String mode) {
		return SYSTEM.equalsIgnoreCase(mode) ? (SystemAppearance.dark() ? DARK : LIGHT) : mode;
	}

	/** Tau's own themes: the default of each mode, what {@code config.json}'s {@code "dark"} and {@code "light"} mean. */
	public static final String TAU_DARK = "tau-dark";
	public static final String TAU_LIGHT = "tau-light";
	/** FlatLaf's plain themes, still on offer: a remembered choice of either is kept. */
	public static final String FLAT_DARK = "flat-dark";
	public static final String FLAT_LIGHT = "flat-light";

	/** Tau's accent, Royal Purple: the core themes' default until another is chosen. */
	public static final String TAU_ACCENT = "#6B46C1";

	/** The package FlatLaf searches for Tau's {@code *.properties} UI defaults. */
	private static final String DEFAULTS_PACKAGE = "tau.ui";

	/** The tones whose text colour {@link #installDerived} makes readable, each with a {@code Tau.<tone>} and a {@code Tau.<tone>.tint}. */
	private static final List<String> TONES = List.of("muted", "error", "success", "warning");

	/** The named statuses {@link #installDerived} makes readable on their own grounds, each with {@code Tau.status.<name>.*} keys. */
	private static final List<String> STATUSES = List.of("running", "signed", "waiting", "refused", "timedout");

	/**
	 * The controls whose text {@link #installHigherContrast} raises, by the
	 * prefix of their {@code .foreground}, {@code .background},
	 * {@code .selectionForeground} and {@code .placeholderForeground} keys.
	 */
	private static final List<String> TEXT_CONTROLS = List.of(
		"Button", "Button.default", "ToggleButton", "CheckBox", "RadioButton",
		"TextField", "FormattedTextField", "PasswordField", "TextArea", "TextPane", "EditorPane",
		"ComboBox", "Spinner", "List", "Tree", "Table", "TableHeader", "TabbedPane",
		"MenuBar", "Menu", "MenuItem", "CheckBoxMenuItem", "RadioButtonMenuItem", "PopupMenu",
		"ToolTip", "TitlePane");

	/** Body text, 13 pt: the type scale's body size (caption and small 12, lead 15, section 17, title 24, display 32). */
	private static final int BASE_FONT_SIZE = 13;

	/** The UI font: Inter (SIL Open Font License), bundled so it's identical on every platform. */
	private static final String FONT_FAMILY = "Inter";
	/**
	 * The bundled faces: Inter for the UI, and Inconsolata for code — named
	 * first in {@code monospaced.font} in the UI defaults, with the platform's
	 * own monospaced faces behind it should the resource ever fail to register.
	 */
	private static final String[] FONT_RESOURCES = {
		"/fonts/inter/Inter-Regular.ttf",
		"/fonts/inter/Inter-Medium.ttf",
		"/fonts/inter/Inter-SemiBold.ttf",
		"/fonts/inter/Inter-Bold.ttf",
		"/fonts/inconsolata/Inconsolata-Regular.ttf",
		"/fonts/inconsolata/Inconsolata-Bold.ttf",
	};

	private static boolean registered;
	private static List<Choice> catalogue = List.of();
	private static Choice current;
	private static String accent;
	private static boolean higherContrast;

	private LAF() {
	}

	/**
	 * Installs the look before any component exists.
	 *
	 * @param themeId        the chosen theme's id, or null
	 * @param mode           {@link #LIGHT} or {@link #DARK}: which default to fall back to when
	 *                       there is no chosen theme or it is no longer available
	 * @param accentHex      the chosen accent as {@code #RRGGBB}, or null for the default
	 * @param higherContrast whether higher contrast is chosen (see {@link #installHigherContrast})
	 * @param ownThemes      the folder of the owner's own {@code .theme.json} files, or null
	 */
	public static void init(String themeId, String mode, String accentHex, boolean higherContrast, Path ownThemes) {
		// Bundled Inter: register the faces, then make it the preferred family
		// before the theme installs so components pick it up.
		registerFonts();
		FlatLaf.setPreferredFontFamily(FONT_FAMILY);
		// FlatLaf's semibold class (and the .lead and .section headings) use Inter's own SemiBold cut.
		FlatLaf.setPreferredSemiboldFontFamily("Inter SemiBold");

		// Tau's defaults load after the theme's, so they win. Registered
		// once: the source list is additive.
		if (!registered) {
			FlatLaf.registerCustomDefaultsSource(DEFAULTS_PACKAGE);
			registered = true;
		}

		catalogue = catalogue(ownThemes);
		Choice c = choice(themeId);
		if (c == null) c = choice(defaultTheme(mode));
		setup(c, accentHex);
		installFont();
		installDerived(higherContrast);
		FlatLaf.updateUI();
	}

	/**
	 * Switches the running app to another theme, accent or contrast,
	 * cross-fading every open window from the old look to the new. Event
	 * thread only.
	 */
	public static void apply(String themeId, String accentHex, boolean higherContrast) {
		Choice c = choice(themeId);
		if (c == null) c = (current != null) ? current : choice(TAU_DARK);
		FlatAnimatedLafChange.showSnapshot();
		setup(c, accentHex);
		installFont();
		installDerived(higherContrast);
		FlatLaf.updateUI();
		FlatAnimatedLafChange.hideSnapshotWithAnimation();
	}

	/** Every theme on offer: FlatLaf's, the IntelliJ pack's, then the owner's own. */
	public static List<Choice> themes() {
		return catalogue;
	}

	/** The themes of one mode, in catalogue order. */
	public static List<Choice> themes(String mode) {
		boolean dark = !isLight(mode);
		return catalogue.stream().filter(c -> c.dark() == dark).toList();
	}

	public static boolean isLight(String mode) {
		return LIGHT.equalsIgnoreCase(mode);
	}

	/** The mode of the theme in use. */
	public static String mode() {
		return (current != null && !current.dark()) ? LIGHT : DARK;
	}

	/** The id of a mode's default theme. */
	public static String defaultTheme(String mode) {
		return isLight(mode) ? TAU_LIGHT : TAU_DARK;
	}

	/** The theme with {@code id}, or null. */
	public static Choice choice(String id) {
		if (id == null) return null;
		for (Choice c : catalogue) if (c.id().equals(id)) return c;
		return null;
	}

	/** The theme in use. */
	public static Choice current() {
		return current;
	}

	/** The chosen accent as {@code #RRGGBB}, or null when the default is in use. */
	public static String accent() {
		return accent;
	}

	/** Whether higher contrast is in use. */
	public static boolean higherContrast() {
		return higherContrast;
	}

	/** {@code hex} if it is a colour FlatLaf can read, else null. */
	public static String validAccent(String hex) {
		if (hex == null || hex.isBlank()) return null;
		try {
			Color.decode(hex.trim());
			return hex.trim();
		} catch (NumberFormatException e) {
			log.warn("Ignoring unreadable accent colour {}", hex);
			return null;
		}
	}

	private static void setup(Choice c, String accentHex) {
		String chosen = validAccent(accentHex);
		// The theme variable every accent-derived default resolves from —
		// selection, focus, check marks, the default and primary buttons. Only
		// FlatLaf's own themes take one; the others define their colours outright.
		String effective = c.core() ? ((chosen != null) ? chosen : TAU_ACCENT) : null;
		FlatLaf.setGlobalExtraDefaults((effective != null) ? Map.of("@accentColor", effective) : null);

		LookAndFeel laf;
		try {
			laf = c.laf().get();
		} catch (RuntimeException e) {
			log.warn("Could not load theme {}: {}", c.name(), e.toString());
			c = choice(TAU_DARK);
			laf = c.laf().get();
		}
		if (!FlatLaf.setup(laf)) log.warn("FlatLaf could not be installed; using the default look and feel");
		current = c;
		accent = chosen;
	}

	/**
	 * The colours a properties file cannot work out, because they depend on a
	 * contrast: Tau's own text colours made readable on whichever theme is
	 * installed, and the shades the buttons take from the accent. Each starts
	 * from the value the theme and {@code FlatLaf.properties} resolved and is
	 * put into the look and feel's own defaults, which the next theme setup
	 * replaces, so a value never carries over to another theme or compounds.
	 * The style classes in {@code FlatLaf.properties} refer to them. Tau's
	 * own text reads at {@link Theme#textContrast}: AA, or AAA with higher
	 * contrast. Runs after every theme setup, before the UI updates.
	 */
	private static void installDerived(boolean higher) {
		UIDefaults defaults = UIManager.getLookAndFeelDefaults();
		higherContrast = higher;
		double target = higher ? Theme.AAA_CONTRAST : Theme.AA_CONTRAST;
		defaults.put("Tau.textContrast", target);
		if (higher) installHigherContrast(defaults);
		Color panel = Theme.panel();
		// Cards and code step off the panel no further than ordinary text still reads on them.
		Color text = Theme.foreground();
		Color surface = Theme.readableGround(panel, Theme.surface(), target, text);
		defaults.put("Tau.surface", surface);
		defaults.put("Tau.code.background", Theme.readableGround(panel, Theme.codeBackground(), target, text));
		// Secondary text and Tau's red, green and orange: moved in lightness until each
		// reads on the panel, on a card, and on its own faint tint over either. Where
		// the text can go no further and still falls short, the tint fades instead.
		for (String tone : TONES) {
			Color base = defaults.getColor("Tau." + tone);
			Color tint = defaults.getColor("Tau." + tone + ".tint");
			// Absent only when FlatLaf could not install and Tau's defaults never loaded: Theme's fallbacks stand.
			if (base == null || tint == null) continue;
			Color toneText = Theme.readableOn(base, target, panel, surface, Theme.over(tint, panel), Theme.over(tint, surface));
			defaults.put("Tau." + tone, toneText);
			defaults.put("Tau." + tone + ".tint", Theme.readableTint(tint, toneText, target, panel, surface));
		}
		// The named statuses paint on a ground of their own, over the panel or a card:
		// their text reads there as text does, their icon as a mark does (3:1). A
		// translucent ground fades where the text can go no further, as a tone's tint does.
		for (String status : STATUSES) {
			String key = "Tau.status." + status;
			Color ground = defaults.getColor(key + ".background");
			if (ground == null) continue;
			raise(defaults, key + ".foreground", target, Theme.over(ground, panel), Theme.over(ground, surface));
			Color statusText = defaults.getColor(key + ".foreground");
			if (statusText != null && ground.getAlpha() < 255) {
				ground = Theme.readableTint(ground, statusText, target, panel, surface);
				defaults.put(key + ".background", ground);
			}
			raise(defaults, key + ".icon", Theme.NON_TEXT_CONTRAST, Theme.over(ground, panel), Theme.over(ground, surface));
		}
		Color accent = Theme.accent();
		Color dark = Theme.accentDark();
		Color strongest = ColorFunctions.fade(accent, 0.22f);
		// The accent as text — links, headings, the tinted button — read the same way, on the
		// tinted button's tint; that tint, the selected navigation's and the accent badge's
		// fade where the text can go no further. On Tau's own themes with Tau's own purple it
		// starts from the theme's link colour (#B79BF0 on navy, Royal Purple on white).
		Color start = accent;
		if (current != null && GROUP_TAU.equals(current.group()) && accent.equals(Color.decode(TAU_ACCENT))) {
			Color link = defaults.getColor("Tau.link");
			if (link != null) start = link;
		}
		Color accentText = Theme.readableOn(start, target, panel, surface, Theme.over(strongest, panel), Theme.over(strongest, surface));
		Color tinted = Theme.readableTint(strongest, accentText, target, panel, surface);
		Color accentTint = defaults.getColor("Tau.accent.tint");
		if (accentTint != null) defaults.put("Tau.accent.tint", Theme.readableTint(accentTint, accentText, target, panel, surface));
		defaults.put("Button.toolbar.selectedBackground", tinted);
		defaults.put("ToggleButton.toolbar.selectedBackground", tinted);
		defaults.put("Tau.accent.dark", dark);
		defaults.put("Tau.accent.text", accentText);
		// The primary button: white on the accent, darkened only until white reads on it;
		// hover a step lighter, pressed a step darker.
		Color fill = Theme.primaryFill();
		defaults.put("Tau.primary.background", fill);
		defaults.put("Tau.primary.hoverBackground", ColorFunctions.lighten(fill, 0.05f));
		defaults.put("Tau.primary.pressedBackground", ColorFunctions.darken(fill, 0.06f));
		// The tinted button: the accent as text on its faint tint, the tint deepening under the pointer.
		float rest = tinted.getAlpha() / 255f;
		defaults.put("Tau.tinted.foreground", accentText);
		defaults.put("Tau.tinted.background", tinted);
		defaults.put("Tau.tinted.hoverBackground", ColorFunctions.fade(accent, rest + 0.08f));
		defaults.put("Tau.tinted.pressedBackground", ColorFunctions.fade(accent, rest + 0.16f));
	}

	/**
	 * Higher contrast, chosen in Settings → Theme, on the theme's own colours:
	 * the text of its controls brought to {@value Theme#AAA_CONTRAST}:1 on the
	 * ground each paints on, and selected text on its selection as near as
	 * white or black gets on a mid-tone selection; placeholders
	 * to {@value Theme#AA_CONTRAST}:1, where they stay quieter than what is
	 * typed; and the borders of fields, buttons and check boxes to
	 * {@value Theme#NON_TEXT_CONTRAST}:1 — each moved in lightness, hue kept.
	 */
	private static void installHigherContrast(UIDefaults defaults) {
		double aaa = Theme.AAA_CONTRAST;
		Color panel = Theme.panel();
		// Labels sit on cards as well as on the panel.
		raise(defaults, "Label.foreground", aaa, panel, Theme.surface());
		for (String c : TEXT_CONTROLS) {
			Color ground = defaults.getColor(c + ".background");
			if (ground == null) ground = panel;
			raise(defaults, c + ".foreground", aaa, ground);
			raise(defaults, c + ".selectionForeground", aaa, defaults.getColor(c + ".selectionBackground"));
			raise(defaults, c + ".placeholderForeground", Theme.AA_CONTRAST, ground);
		}
		// A tree's renderer reads its own pair.
		raise(defaults, "Tree.textForeground", aaa, defaults.getColor("Tree.textBackground"));
		double edge = Theme.NON_TEXT_CONTRAST;
		raise(defaults, "Component.borderColor", edge, panel, defaults.getColor("TextField.background"));
		raise(defaults, "Button.borderColor", edge, panel, defaults.getColor("Button.background"));
		raise(defaults, "CheckBox.icon.borderColor", edge, panel, defaults.getColor("CheckBox.icon.background"));
	}

	/** The colour at {@code key} moved until it reads on every ground; an absent key, or an absent ground, leaves it be. */
	private static void raise(UIDefaults defaults, String key, double contrast, Color... grounds) {
		Color colour = defaults.getColor(key);
		if (colour == null) return;
		for (Color g : grounds) if (g == null) return;
		defaults.put(key, Theme.readableOn(colour, contrast, grounds));
	}

	/** The body size in Inter: FlatLaf derives every other font from it and scales the UI to match. */
	private static void installFont() {
		UIManager.put("defaultFont", new Font(FONT_FAMILY, Font.PLAIN, BASE_FONT_SIZE));
	}

	// ------------------------------------------------------------------
	// The catalogue
	// ------------------------------------------------------------------

	private static List<Choice> catalogue(Path ownThemes) {
		List<Choice> out = new ArrayList<>();
		out.add(new Choice(TAU_DARK, TauDarkLaf.NAME, GROUP_TAU, true, true, TauDarkLaf::new));
		out.add(new Choice(TAU_LIGHT, TauLightLaf.NAME, GROUP_TAU, false, true, TauLightLaf::new));
		out.add(core(FLAT_DARK, "Dark", true, FlatDarkLaf::new));
		out.add(core(FLAT_LIGHT, "Light", false, FlatLightLaf::new));
		out.add(core("flat-mac-dark", "macOS Dark", true, FlatMacDarkLaf::new));
		out.add(core("flat-mac-light", "macOS Light", false, FlatMacLightLaf::new));
		out.add(core("flat-darcula", "Darcula", true, FlatDarculaLaf::new));
		out.add(core("flat-intellij", "IntelliJ", false, FlatIntelliJLaf::new));
		for (FlatAllIJThemes.FlatIJLookAndFeelInfo info : FlatAllIJThemes.INFOS) {
			String className = info.getClassName();
			String id = "ij:" + className.substring(className.lastIndexOf('.') + 1);
			out.add(new Choice(id, info.getName(), GROUP_INTELLIJ, info.isDark(), false, () -> instantiate(className)));
		}
		out.addAll(own(ownThemes));
		return List.copyOf(out);
	}

	private static Choice core(String id, String name, boolean dark, Supplier<LookAndFeel> laf) {
		return new Choice(id, name, GROUP_FLATLAF, dark, true, laf);
	}

	/** The owner's {@code *.theme.json} files, each read once for its name and light/dark. */
	private static List<Choice> own(Path folder) {
		List<Choice> out = new ArrayList<>();
		if (folder == null || !Files.isDirectory(folder)) return out;
		try (Stream<Path> files = Files.list(folder)) {
			for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".theme.json")).sorted().toList()) {
				try (InputStream in = Files.newInputStream(f)) {
					IntelliJTheme t = new IntelliJTheme(in);
					String name = (t.name != null && !t.name.isBlank()) ? t.name : f.getFileName().toString();
					out.add(new Choice("file:" + f.getFileName(), name, GROUP_OWN, t.dark, false, () -> load(f)));
				} catch (IOException | RuntimeException e) {
					log.warn("Skipping theme {}: {}", f.getFileName(), e.toString());
				}
			}
		} catch (IOException e) {
			log.warn("Could not list {}: {}", folder, e.toString());
		}
		return out;
	}

	private static LookAndFeel load(Path f) {
		try {
			return IntelliJTheme.createLaf(Files.newInputStream(f));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static LookAndFeel instantiate(String className) {
		try {
			return (LookAndFeel) Class.forName(className).getDeclaredConstructor().newInstance();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Theme " + className + " unavailable", e);
		}
	}

	/** Registers the bundled faces with the AWT graphics environment. */
	private static void registerFonts() {
		GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
		for (String resource : FONT_RESOURCES) {
			try (InputStream in = LAF.class.getResourceAsStream(resource)) {
				if (in == null) {
					log.warn("Bundled font not found on the classpath: {}", resource);
					continue;
				}
				ge.registerFont(Font.createFont(Font.TRUETYPE_FONT, in));
			} catch (Exception e) {
				log.warn("Could not register bundled font {}: {}", resource, e.toString());
			}
		}
	}
}
