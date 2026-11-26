package tau.ui.shell;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.event.MouseEvent;

import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import tau.ui.ReadsLook;

/** The command palette closes on a click elsewhere in its window, not on one inside it. Headless. */
@ReadsLook
class CommandPaletteTest {

	@Test
	void aClickElsewhereClosesIt() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			CommandPalette palette = new CommandPalette(new Commands().add("one", "One", "Here", null, null, () -> {}));
			JRootPane root = new JRootPane();
			root.setSize(800, 600);
			root.getLayeredPane().setSize(800, 600);
			JPanel content = new JPanel();
			root.getContentPane().add(content);
			palette.open(root);
			assertTrue(palette.isOpen());
			press(palette);
			assertTrue(palette.isOpen(), "a click inside keeps it open");
			press(content);
			assertFalse(palette.isOpen(), "a click elsewhere closes it");
		});
	}

	private static void press(Component c) {
		c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 1, 1, 1, false));
	}
}
