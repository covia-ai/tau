package tau.ui.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The owner's Motion choice overrides the system's setting and takes effect at
 * once; "Match the system" goes back to it; {@code tau.motion} still wins over
 * both; a saved choice reads back as its level.
 */
class MotionChoiceTest {

	@Test
	void theOwnersChoiceOverridesTheSystemAndThePropertyStillWins() {
		Motion before = Motion.chosen();
		String property = System.getProperty(Motion.PROPERTY);
		try {
			System.clearProperty(Motion.PROPERTY);
			Motion.choose(Motion.OFF);
			assertEquals(Motion.OFF, Motion.get(), "the choice, at once");
			Motion.choose(null);
			// Headless, the system's setting can't be read: Full.
			assertEquals(Motion.FULL, Motion.get(), "matching the system again");
			Motion.choose(Motion.REDUCED);
			System.setProperty(Motion.PROPERTY, "full");
			Motion.choose(Motion.REDUCED); // finds it again
			assertEquals(Motion.FULL, Motion.get(), "tau.motion wins over the choice");
		} finally {
			if (property == null) System.clearProperty(Motion.PROPERTY);
			else System.setProperty(Motion.PROPERTY, property);
			Motion.choose(before);
		}
	}

	@Test
	void aSavedChoiceReadsBackAsItsLevel() {
		assertEquals(Motion.REDUCED, Motion.parse("reduced"));
		assertEquals(Motion.OFF, Motion.parse(" Off "));
		assertNull(Motion.parse("system"), "matches the system");
		assertNull(Motion.parse(null));
	}
}
