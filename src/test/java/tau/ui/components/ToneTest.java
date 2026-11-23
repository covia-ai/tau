package tau.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.List;

import javax.swing.UIManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import tau.ui.LAF;

/**
 * The tones' grounds come from Tau's UI defaults through a real theme
 * install, a step stronger on a dark theme than a light one — not from the
 * fallback {@link Tone#tint} works out when a key is missing — and the
 * tones' colours read on them whatever the theme. Isolated: it
 * swaps the JVM's look and feel, which the concurrent classes measuring text
 * must not see change under them.
 */
@Isolated
class ToneTest {

	@Test
	void tintsAreTheThemesAndFollowItsMode() {
		LAF.init(LAF.FLAT_DARK, LAF.DARK, null, null);
		assertTints(0.18f);
		LAF.init(LAF.FLAT_LIGHT, LAF.LIGHT, null, null);
		assertTints(0.14f);
	}

	@Test
	void anAccentTintIsTheAccentItself() {
		LAF.init(LAF.FLAT_DARK, LAF.DARK, "#3B82F6", null);
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
			LAF.init(theme, LAF.DARK, null, null);
			Color panel = Theme.panel();
			Color card = Theme.surface();
			Color selected = Theme.accentGround(0.14f);
			assertReads(Theme.foreground(), card, theme + " text on a card");
			assertReads(Theme.foreground(), selected, theme + " text on a selected row");
			assertReads(Theme.muted(), selected, theme + " secondary text on a selected row");
			for (Tone tone : Tone.values()) {
				for (Color ground : List.of(panel, card, Theme.over(tone.tint(), panel), Theme.over(tone.tint(), card))) {
					assertReads(tone.colour(), ground, theme + " " + tone);
				}
			}
		}
	}

	/** The colours start afresh from each theme: switching away and back gives the same, never a further step. */
	@Test
	void readableColoursDoNotCarryOverBetweenThemes() {
		LAF.init(NORD, LAF.DARK, null, null);
		List<Color> first = colours();
		LAF.init(LAF.FLAT_LIGHT, LAF.LIGHT, null, null);
		LAF.init(NORD, LAF.DARK, null, null);
		assertEquals(first, colours());
	}

	private static final String NORD = "ij:FlatNordIJTheme";

	private static List<Color> colours() {
		return List.of(Theme.muted(), Theme.error(), Theme.success(), Theme.warning(), Theme.accentText(), Theme.surface());
	}

	private static void assertReads(Color text, Color ground, String what) {
		double c = Theme.contrast(text, ground);
		assertTrue(c >= Theme.AA_CONTRAST, () -> what + ": " + text + " on " + ground + " reads at only " + c);
	}

	private static void assertTints(float alpha) {
		for (Tone tone : Tone.values()) {
			String key = "Tau." + tone.name().toLowerCase() + ".tint";
			assertNotNull(UIManager.getColor(key), key);
			assertEquals(Math.round(alpha * 255), tone.tint().getAlpha(), 1, tone.name());
		}
	}
}
