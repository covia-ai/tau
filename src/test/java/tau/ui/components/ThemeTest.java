package tau.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The contrast arithmetic behind Tau's derived colours: pure colour maths, no look and feel. */
class ThemeTest {

	/** The accents on offer in Settings → Theme, and a few pale ones an owner might choose. */
	private static final List<Color> ACCENTS = List.of(
		Color.decode("#7C6CF5"), Color.decode("#3B82F6"), Color.decode("#14B8A6"), Color.decode("#3FB950"),
		Color.decode("#F59E0B"), Color.decode("#EC4899"), Color.decode("#FFFF00"), Color.decode("#FFFFFF"));

	@Test
	void contrastIsWcagRatio() {
		assertEquals(21.0, Theme.contrast(Color.BLACK, Color.WHITE), 0.01);
		assertEquals(21.0, Theme.contrast(Color.WHITE, Color.BLACK), 0.01);
		assertEquals(1.0, Theme.contrast(Color.RED, Color.RED), 0.0001);
		// The canonical checks: white on pure blue is 8.59:1, on #767676 is 4.54:1.
		assertEquals(8.59, Theme.contrast(Color.WHITE, Color.BLUE), 0.01);
		assertEquals(4.54, Theme.contrast(Color.WHITE, Color.decode("#767676")), 0.01);
	}

	@Test
	void everyAccentDarkensUntilWhiteReadsOnIt() {
		for (Color accent : ACCENTS) {
			Color dark = Theme.darkenFor(accent, Color.WHITE, Theme.AAA_CONTRAST);
			assertTrue(Theme.contrast(Color.WHITE, dark) >= Theme.AAA_CONTRAST,
				() -> accent + " darkened to " + dark + " reads at only " + Theme.contrast(Color.WHITE, dark));
		}
	}

	@Test
	void darkeningKeepsTheHue() {
		Color amber = Color.decode("#F59E0B");
		Color dark = Theme.darkenFor(amber, Color.WHITE, Theme.AAA_CONTRAST);
		float[] before = Color.RGBtoHSB(amber.getRed(), amber.getGreen(), amber.getBlue(), null);
		float[] after = Color.RGBtoHSB(dark.getRed(), dark.getGreen(), dark.getBlue(), null);
		assertEquals(before[0], after[0], 0.02, "hue");
		assertTrue(after[2] < before[2], "darker");
	}

	@Test
	void anAlreadyDarkColourIsLeftAlone() {
		Color navy = Color.decode("#101040");
		assertEquals(navy, Theme.darkenFor(navy, Color.WHITE, Theme.AAA_CONTRAST));
	}

	@Test
	void everyAccentReadsOnEitherGround() {
		Color darkPanel = Color.decode("#1E1E1E");
		Color lightPanel = Color.decode("#F2F2F2");
		for (Color accent : ACCENTS) {
			for (Color panel : List.of(darkPanel, lightPanel)) {
				Color ground = Theme.blend(panel, accent, 0.22f);
				Color text = Theme.readableOn(accent, Theme.AA_CONTRAST, ground);
				assertTrue(Theme.contrast(ground, text) >= Theme.AA_CONTRAST,
					() -> accent + " on " + ground + " became " + text + " at only " + Theme.contrast(ground, text));
				float[] before = Color.RGBtoHSB(accent.getRed(), accent.getGreen(), accent.getBlue(), null);
				float[] after = Color.RGBtoHSB(text.getRed(), text.getGreen(), text.getBlue(), null);
				// White and yellow have no hue to keep once they pass through grey or black.
				if (before[1] > 0.1f && after[1] > 0.1f) assertEquals(before[0], after[0], 0.02, "hue of " + accent);
			}
		}
	}

	@Test
	void readableMovesTowardsTheLightOnADarkGroundAndAwayOnALightOne() {
		Color purple = Color.decode("#7C6CF5");
		Color onDark = Theme.readableOn(purple, Theme.AA_CONTRAST, Color.decode("#1E1E1E"));
		Color onLight = Theme.readableOn(purple, Theme.AA_CONTRAST, Color.decode("#F2F2F2"));
		assertTrue(Color.RGBtoHSB(onDark.getRed(), onDark.getGreen(), onDark.getBlue(), null)[2] > 0.96f, "lighter");
		assertTrue(Color.RGBtoHSB(onLight.getRed(), onLight.getGreen(), onLight.getBlue(), null)[2] < 0.96f, "darker");
	}

	@Test
	void readableOnSeveralGroundsReadsOnTheHardest() {
		Color red = Color.decode("#E55353");
		// A dark panel and the lighter card on it; then a light panel and its darker card.
		List<List<Color>> sets = List.of(
			List.of(Color.decode("#1E1E1E"), Color.decode("#3A3A3A")),
			List.of(Color.decode("#F2F2F2"), Color.decode("#DCDCDC")));
		for (List<Color> grounds : sets) {
			Color text = Theme.readableOn(red, Theme.AA_CONTRAST, grounds.toArray(Color[]::new));
			for (Color g : grounds) {
				assertTrue(Theme.contrast(text, g) >= Theme.AA_CONTRAST, () -> text + " on " + g);
			}
		}
	}

	@Test
	void aColourThatAlreadyReadsIsLeftAlone() {
		Color white = Color.WHITE;
		assertEquals(white, Theme.readableOn(white, Theme.AA_CONTRAST, Color.decode("#1E1E1E"), Color.decode("#2B2B2B")));
	}

	@Test
	void aGroundStepsBackTowardsThePanelUntilTextReads() {
		Color panel = Color.decode("#1E1E1E");
		Color dim = Color.decode("#8A8A8A");
		Color card = Color.decode("#4A4A4A");
		assertTrue(Theme.contrast(dim, card) < Theme.AA_CONTRAST, "the card starts too light for the text");
		Color ground = Theme.readableGround(panel, card, Theme.AA_CONTRAST, Color.WHITE, dim);
		assertTrue(Theme.contrast(dim, ground) >= Theme.AA_CONTRAST);
		assertTrue(Theme.contrast(ground, panel) > 1.0, "still a step off the panel");
		assertEquals(card, Theme.readableGround(panel, card, Theme.AA_CONTRAST, Color.WHITE), "a card text reads on stays");
	}

	@Test
	void aTranslucentColourShowsAsItsMixWithTheGround() {
		Color half = new Color(255, 255, 255, 128);
		Color shown = Theme.over(half, Color.BLACK);
		assertEquals(128, shown.getRed(), 1);
		assertEquals(255, shown.getAlpha());
		assertEquals(Color.RED, Theme.over(Color.RED, Color.BLUE));
	}
}
