package tau.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import javax.swing.UIManager;

import org.junit.jupiter.api.Test;

import tau.ui.ChangesLook;
import tau.ui.LAF;

/**
 * The tones' grounds come from Tau's UI defaults through a real theme
 * install, a step stronger on a dark theme than a light one — not from the
 * fallback {@link Tone#tint} works out when a key is missing — and the
 * tones' colours read on them whatever the theme.
 */
@ChangesLook
class ToneTest {

	@Test
	void tintsAreTheThemesAndFollowItsMode() {
		LAF.init(LAF.FLAT_DARK, LAF.DARK, null, false, null);
		assertTints(0.18f);
		LAF.init(LAF.FLAT_LIGHT, LAF.LIGHT, null, false, null);
		assertTints(0.14f);
	}

	@Test
	void anAccentTintIsTheAccentItself() {
		LAF.init(LAF.FLAT_DARK, LAF.DARK, "#3B82F6", false, null);
		Color tint = Tone.ACCENT.tint();
		assertEquals(Color.decode("#3B82F6").getRGB() & 0xFFFFFF, tint.getRGB() & 0xFFFFFF);
	}

	/**
	 * After a real install, every tone's text reads at AA on the panel, on a
	 * card and on its own tint over either, and ordinary and secondary text
	 * read on a card and on a selected row: on FlatLaf's own themes and on an
	 * IntelliJ one, Nord, whose secondary text and card fall short as the
	 * theme ships them.
	 */
	@Test
	void tonesReadOnEveryGroundTheyPaintOn() {
		for (String theme : List.of(LAF.FLAT_DARK, LAF.FLAT_LIGHT, NORD)) {
			LAF.init(theme, LAF.DARK, null, false, null);
			assertTauColoursRead(theme, Theme.AA_CONTRAST);
		}
	}

	/**
	 * With higher contrast, Tau's own text reaches AAA wherever it reads at AA
	 * without; ordinary text on the panel too, placeholders reach AA and field
	 * borders the 3:1 asked of what must be seen.
	 */
	@Test
	void higherContrastRaisesTextToAaaAndBordersToBeSeen() {
		for (String theme : List.of(LAF.FLAT_DARK, LAF.FLAT_LIGHT, NORD)) {
			LAF.init(theme, LAF.DARK, null, true, null);
			assertTrue(LAF.higherContrast());
			assertEquals(Theme.AAA_CONTRAST, Theme.textContrast());
			assertTauColoursRead(theme, Theme.AAA_CONTRAST);
			Color panel = Theme.panel();
			assertReads(Theme.foreground(), panel, Theme.AAA_CONTRAST, theme + " text on the panel");
			assertReads(UIManager.getColor("TextField.placeholderForeground"), UIManager.getColor("TextField.background"),
				Theme.AA_CONTRAST, theme + " placeholder");
			assertReads(UIManager.getColor("Component.borderColor"), panel, Theme.NON_TEXT_CONTRAST, theme + " field border");
		}
	}

	/** Turning higher contrast off gives back the theme's own colours exactly, Tau's derived ones too. */
	@Test
	void higherContrastOffRestoresTheTheme() {
		LAF.init(NORD, LAF.DARK, null, false, null);
		List<Color> plain = themeColours();
		LAF.init(NORD, LAF.DARK, null, true, null);
		assertNotEquals(plain, themeColours(), "higher contrast changes Nord");
		LAF.init(NORD, LAF.DARK, null, false, null);
		assertEquals(plain, themeColours());
		assertEquals(Theme.AA_CONTRAST, Theme.textContrast());
	}

	/** The colours start afresh from each theme: switching away and back gives the same, never a further step. */
	@Test
	void readableColoursDoNotCarryOverBetweenThemes() {
		LAF.init(NORD, LAF.DARK, null, false, null);
		List<Color> first = colours();
		LAF.init(LAF.FLAT_LIGHT, LAF.LIGHT, null, false, null);
		LAF.init(NORD, LAF.DARK, null, false, null);
		assertEquals(first, colours());
	}

	private static final String NORD = "ij:FlatNordIJTheme";

	/** Every tone on the grounds it paints on, and ordinary and secondary text on a card and a selected row, at {@code contrast}. */
	private static void assertTauColoursRead(String theme, double contrast) {
		Color panel = Theme.panel();
		Color card = Theme.surface();
		Color selected = Theme.accentGround(0.14f);
		assertReads(Theme.foreground(), card, contrast, theme + " text on a card");
		assertReads(Theme.foreground(), selected, contrast, theme + " text on a selected row");
		assertReads(Theme.muted(), selected, contrast, theme + " secondary text on a selected row");
		for (Tone tone : Tone.values()) {
			if (tone.isStatus()) {
				// A named status paints only on its own ground: its text there, its icon as a mark.
				for (Color ground : List.of(Theme.over(tone.tint(), panel), Theme.over(tone.tint(), card))) {
					assertReads(tone.text(), ground, contrast, theme + " " + tone + " text");
					assertReads(tone.colour(), ground, Theme.NON_TEXT_CONTRAST, theme + " " + tone + " icon");
				}
				continue;
			}
			for (Color ground : List.of(panel, card, Theme.over(tone.tint(), panel), Theme.over(tone.tint(), card))) {
				assertReads(tone.colour(), ground, contrast, theme + " " + tone);
			}
		}
		for (Color ground : List.of(panel, card)) {
			assertReads(Theme.accentText(), Theme.over(Theme.tinted(), ground), contrast, theme + " the tinted button");
		}
	}

	private static List<Color> colours() {
		return List.of(Theme.muted(), Theme.error(), Theme.success(), Theme.warning(), Theme.accentText(), Theme.surface());
	}

	private static List<Color> themeColours() {
		List<Color> all = new ArrayList<>(colours());
		for (String key : List.of("Label.foreground", "TextField.placeholderForeground", "Component.borderColor")) {
			all.add(UIManager.getColor(key));
		}
		return all;
	}

	private static void assertReads(Color text, Color ground, double contrast, String what) {
		double c = Theme.contrast(text, ground);
		assertTrue(c >= contrast, () -> what + ": " + text + " on " + ground + " reads at only " + c);
	}

	private static void assertTints(float alpha) {
		for (Tone tone : Tone.values()) {
			if (tone.isStatus()) continue; // grounds of their own, read in assertTauColoursRead
			String key = "Tau." + tone.name().toLowerCase() + ".tint";
			assertNotNull(UIManager.getColor(key), key);
			assertEquals(Math.round(alpha * 255), tone.tint().getAlpha(), 1, tone.name());
		}
	}
}
