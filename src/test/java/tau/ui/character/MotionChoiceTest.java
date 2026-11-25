package tau.ui.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/**
 * How the level is decided: {@code tau.motion} wins; then the owner's Motion
 * choice, which overrides the system's setting; "Match the system" (no choice)
 * reads the system's setting, and only then. A saved choice reads back as its
 * level. Pure, so no test changes the running app's motion.
 */
class MotionChoiceTest {

	@Test
	void thePropertyWinsThenTheOwnersChoiceThenTheSystem() {
		AtomicBoolean asked = new AtomicBoolean();
		assertEquals(Motion.OFF, Motion.resolve(null, Motion.OFF, () -> { asked.set(true); return true; }),
			"the choice overrides a system that reduces motion");
		assertFalse(asked.get(), "and the system is not asked");
		assertEquals(Motion.REDUCED, Motion.resolve(null, null, () -> true), "matching a system that reduces motion");
		assertEquals(Motion.FULL, Motion.resolve(null, null, () -> false), "matching a system that does not");
		assertEquals(Motion.FULL, Motion.resolve("full", Motion.REDUCED, () -> true), "tau.motion wins over the choice");
		assertEquals(Motion.OFF, Motion.resolve("nonsense", Motion.OFF, () -> false), "an unknown property is ignored");
	}

	@Test
	void aSavedChoiceReadsBackAsItsLevel() {
		assertEquals(Motion.REDUCED, Motion.parse("reduced"));
		assertEquals(Motion.OFF, Motion.parse(" Off "));
		assertNull(Motion.parse("system"), "matches the system");
		assertNull(Motion.parse(null));
	}
}
