package tau.ui.components;

import java.awt.Color;

import javax.swing.UIManager;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.ColorFunctions;

/**
 * The colours of the current theme, read from the UI defaults FlatLaf resolved
 * from the theme and from {@code tau/ui/FlatLaf.properties} — never
 * cached, so a component that paints with them follows a theme change.
 *
 * <p>Prefer a {@linkplain Styles style class} where one fits (a muted label, an
 * error note): the theme then owns the colour outright. Read a colour here for
 * custom painting and for states that switch at runtime.
 */
public final class Theme {

	private Theme() {
	}

	/** The Tau accent ({@code @accentColor}). */
	public static Color accent() {
		return FlatUIUtils.getUIColor("Component.accentColor", 0x7C6CF5);
	}

	/** The contrast white text reaches on {@link #accentDark}: WCAG's AAA for body text. */
	public static final double AAA_CONTRAST = 7.0;
	/** The contrast {@link #accentText} reaches on its ground: WCAG's AA for body text. */
	public static final double AA_CONTRAST = 4.5;
	/** WCAG's minimum for what is not text but must be seen: a field's border, a check box's outline. */
	public static final double NON_TEXT_CONTRAST = 3.0;
	private static Color darkFor, darkOf;

	/**
	 * The accent as a background for light text: darkened in HSL, hue and
	 * saturation kept, until white reads on it at {@value #AAA_CONTRAST}:1 —
	 * so every accent on offer, and any chosen, comes out deep. The user's
	 * bubbles paint with it; the accent itself stays for selection, focus and
	 * the main buttons. Painted on the event thread, so memoised without a lock.
	 */
	public static Color accentDark() {
		Color a = accent();
		if (!a.equals(darkFor)) {
			darkOf = darkenFor(a, Color.WHITE, AAA_CONTRAST);
			darkFor = a;
		}
		return darkOf;
	}

	/**
	 * The accent as text or an icon — links, headings, the tinted button, the
	 * navigation's selected look: the accent moved in lightness — lighter on a
	 * dark theme, darker on a light one, hue and saturation kept — until it
	 * reads at the {@linkplain #textContrast text contrast} on the panel, on a
	 * card and on the accent's tint over either. Put into the defaults by the
	 * look-and-feel setup; worked out on the panel alone when it is not there.
	 */
	public static Color accentText() {
		Color put = UIManager.getColor("Tau.accent.text");
		if (put != null) return put;
		return readableOn(accent(), AA_CONTRAST, panel());
	}

	/**
	 * {@code base} moved in lightness, hue and saturation kept, a step at a
	 * time until it reads on every one of the grounds at {@code contrast}:1, or
	 * reaches white or black. It moves the way it already lies from the first
	 * ground — lighter text lighter — when that way can get there, and
	 * otherwise whichever way reads better at the end.
	 */
	public static Color readableOn(Color base, double contrast, Color... grounds) {
		boolean lighten = lightens(base, contrast, grounds);
		Color c = base;
		for (int i = 0; i < 100 && worstContrast(c, grounds) < contrast; i++) {
			c = lighten ? ColorFunctions.lighten(c, 0.01f) : ColorFunctions.darken(c, 0.01f);
		}
		return c;
	}

	private static boolean lightens(Color base, double contrast, Color... grounds) {
		double white = worstContrast(Color.WHITE, grounds);
		double black = worstContrast(Color.BLACK, grounds);
		boolean lighter = luminance(base) >= luminance(grounds[0]);
		if ((lighter ? white : black) >= contrast) return lighter;
		return white >= black;
	}

	private static double worstContrast(Color c, Color... grounds) {
		double worst = Double.MAX_VALUE;
		for (Color g : grounds) worst = Math.min(worst, contrast(c, g));
		return worst;
	}

	/**
	 * The contrast text reaches in the look in use: {@value #AA_CONTRAST}:1,
	 * or {@value #AAA_CONTRAST}:1 with higher contrast chosen in Settings →
	 * Theme. Put into the defaults by the look-and-feel setup.
	 */
	public static double textContrast() {
		return (UIManager.get("Tau.textContrast") instanceof Double d) ? d : AA_CONTRAST;
	}

	/**
	 * {@code shade}, a ground a step off {@code panel}, moved back towards the
	 * panel a step at a time until every one of {@code texts} reads on it at
	 * {@code contrast}:1, or it is the panel.
	 */
	public static Color readableGround(Color panel, Color shade, double contrast, Color... texts) {
		Color c = shade;
		for (int i = 1; i <= 20 && worstContrast(c, texts) < contrast; i++) {
			c = blend(shade, panel, i / 20f);
		}
		return c;
	}

	/**
	 * {@code tint}, a translucent ground painted over each of {@code grounds},
	 * faded a step at a time until {@code text} reads on it over every one at
	 * {@code contrast}:1, or it is clear. For when the text has gone as far as
	 * it can: the tint gives way rather than the text.
	 */
	public static Color readableTint(Color tint, Color text, double contrast, Color... grounds) {
		Color c = tint;
		for (int i = 1; i <= 20 && worstContrastOver(text, c, grounds) < contrast; i++) {
			c = new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), Math.round(tint.getAlpha() * (20 - i) / 20f));
		}
		return c;
	}

	private static double worstContrastOver(Color text, Color tint, Color... grounds) {
		double worst = Double.MAX_VALUE;
		for (Color g : grounds) worst = Math.min(worst, contrast(text, over(tint, g)));
		return worst;
	}

	/**
	 * The panel tinted {@code amount} (0–1) of the way towards the accent — a
	 * selected row, an open request — no further than ordinary and secondary
	 * text still read on it at the {@linkplain #textContrast text contrast}.
	 */
	public static Color accentGround(float amount) {
		Color panel = panel();
		return readableGround(panel, blend(panel, accent(), amount), textContrast(), foreground(), muted());
	}

	/** {@code colour}, which may be translucent, as it shows painted over {@code ground}. */
	public static Color over(Color colour, Color ground) {
		Color opaque = new Color(colour.getRGB() & 0xFFFFFF);
		return blend(ground, opaque, colour.getAlpha() / 255f);
	}

	/** {@code base} darkened a step at a time until {@code text} reads on it at {@code contrast}:1, or it is black. */
	static Color darkenFor(Color base, Color text, double contrast) {
		Color c = base;
		for (int i = 0; i < 100 && contrast(text, c) < contrast; i++) {
			c = ColorFunctions.darken(c, 0.01f);
		}
		return c;
	}

	/** The WCAG contrast ratio between two colours: 1 (the same) to 21 (black on white). */
	public static double contrast(Color a, Color b) {
		double la = luminance(a);
		double lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	/** Relative luminance as WCAG defines it, 0 (black) to 1 (white). */
	private static double luminance(Color c) {
		return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
	}

	private static double linear(int channel) {
		double s = channel / 255.0;
		return (s <= 0.03928) ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
	}

	/** Ordinary text. */
	public static Color foreground() {
		return FlatUIUtils.getUIColor("Label.foreground", 0xDDDDDD);
	}

	/**
	 * Secondary text: captions, metadata, hints. The theme's disabled text
	 * colour, moved until it reads like the other colours here; disabled
	 * controls keep the theme's own.
	 */
	public static Color muted() {
		return FlatUIUtils.getUIColor("Tau.muted", 0x888888);
	}

	/** Hairlines and separators. */
	public static Color line() {
		return FlatUIUtils.getUIColor("Separator.foreground", 0x555555);
	}

	/** The panel background the screens sit on. */
	public static Color panel() {
		return FlatUIUtils.getUIColor("Panel.background", 0x1E1E1E);
	}

	/** An elevated surface a step off the panel — bubbles, cards, chips — no further than ordinary text still reads on it. */
	public static Color surface() {
		return FlatUIUtils.getUIColor("Tau.surface", 0x2B2B2B);
	}

	/** The accent's faint tint the selected row or tab sits on: the navigation's, the tinted button's. */
	public static Color tinted() {
		Color put = UIManager.getColor("Tau.tinted.background");
		return (put != null) ? put : fade(accent(), 0.22f);
	}

	/** Behind code in rendered Markdown: a further step off the surface it sits on. */
	public static Color codeBackground() {
		return FlatUIUtils.getUIColor("Tau.code.background", 0x3A3A3A);
	}

	/**
	 * Tau's red as text or an icon. Like {@link #success} and {@link #warning},
	 * moved in lightness by the look-and-feel setup until it reads at the
	 * {@linkplain #textContrast text contrast} on the panel, on a card and on
	 * its own tint.
	 */
	public static Color error() {
		return FlatUIUtils.getUIColor("Tau.error", 0xE55353);
	}

	/** Tau's green as text or an icon. */
	public static Color success() {
		return FlatUIUtils.getUIColor("Tau.success", 0x3FB950);
	}

	/** Tau's orange as text or an icon. */
	public static Color warning() {
		return FlatUIUtils.getUIColor("Tau.warning", 0xE58A3A);
	}

	public static boolean isDark() {
		return FlatLaf.isLafDark();
	}

	/** {@code base} moved {@code amount} (0–1) of the way towards {@code towards}. */
	public static Color blend(Color base, Color towards, float amount) {
		return ColorFunctions.mix(towards, base, amount);
	}

	/** {@code colour} at {@code alpha} (0–1) opacity. */
	public static Color fade(Color colour, float alpha) {
		return ColorFunctions.fade(colour, alpha);
	}
}
