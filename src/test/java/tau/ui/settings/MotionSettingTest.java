package tau.ui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import tau.ui.character.Motion;

/** Settings → Theme's Motion list reports the owner's choice, and shows a saved one without reporting it back. */
class MotionSettingTest {

	@Test
	void choosingReportsTheLevelAndRefreshingDoesNot() throws Exception {
		List<Motion> told = new ArrayList<>();
		List<Boolean> toldSystem = new ArrayList<>();
		SwingUtilities.invokeAndWait(() -> {
			ThemePanel panel = new ThemePanel(new ThemePanel.Host() {
				public void setMode(String mode) {
				}

				public void setTheme(String id) {
				}

				public void setAccent(String accent) {
				}

				public void openThemesFolder() {
				}

				@Override
				public void setMotion(Motion motion) {
					if (motion == null) toldSystem.add(true);
					else told.add(motion);
				}
			});
			panel.refreshMotion(Motion.REDUCED);
			assertEquals("Reduced", String.valueOf(panel.motion.getSelectedItem()), "a saved choice shows");
			panel.motion.setSelectedIndex(3);
			panel.motion.setSelectedIndex(0);
		});
		assertEquals(List.of(Motion.OFF), told, "refreshing told nothing; choosing Off told Off");
		assertEquals(List.of(true), toldSystem, "and Match the system told null");
	}
}
