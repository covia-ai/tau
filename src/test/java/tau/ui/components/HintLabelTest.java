package tau.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import tau.ui.ReadsLook;

/** A hint shown in full beside a label follows the label's hint as the app's state changes it. */
@ReadsLook
class HintLabelTest {

	@Test
	void theShownHintFollowsTheLabelsHint() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			HintLabel label = new HintLabel("label", "first");
			SelectableText shown = new SelectableText("");
			label.showsHintIn(shown);
			assertEquals("first", shown.getText(), "it starts with the label's hint");
			label.setHint("second");
			assertEquals("second", shown.getText());
			assertTrue(shown.isVisible());
			label.setHint(null);
			assertFalse(shown.isVisible(), "no hint, nothing shown");
		});
	}
}
