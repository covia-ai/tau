package tau.ui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import tau.ui.LAF;
import tau.ui.ReadsLook;

/** Settings → Theme's mode switch, as the window refreshes it. Headless. */
@ReadsLook
class ThemePanelTest {

	@Test
	void choosingTheModeShownLeavesMatchingTheSystem() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			List<String> modes = new ArrayList<>();
			ThemePanel page = new ThemePanel(new Recorder(modes));
			page.refresh(LAF.DARK, null, null, false);
			button(page, "Dark").doClick();
			assertEquals(List.of(), modes, "Dark already in force: nothing to do");

			// Following a dark system: Dark is the mode shown, and choosing it stops following.
			page.refreshSystem(true);
			page.refresh(LAF.DARK, null, null, false); // as after a change of accent or look
			assertTrue(button(page, "Match the system").isSelected(), "the switch still shows it follows the system");
			button(page, "Dark").doClick();
			assertEquals(List.of(LAF.DARK), modes);
		});
	}

	private static AbstractButton button(Container c, String text) {
		for (Component k : c.getComponents()) {
			if (k instanceof AbstractButton b && text.equals(b.getText())) return b;
			if (k instanceof Container kc) {
				AbstractButton found = button(kc, text);
				if (found != null) return found;
			}
		}
		return null;
	}

	/** A host that notes each mode chosen and does nothing else. */
	private record Recorder(List<String> modes) implements ThemePanel.Host {
		@Override
		public void setMode(String mode) {
			modes.add(mode);
		}

		@Override
		public void setTheme(String id) {
		}

		@Override
		public void setAccent(String accent) {
		}

		@Override
		public void setHigherContrast(boolean on) {
		}

		@Override
		public void openThemesFolder() {
		}
	}
}
