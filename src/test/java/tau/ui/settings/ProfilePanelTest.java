package tau.ui.settings;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import tau.ui.components.SelectableText;

/** The Identity page as it switches between the owner and the venue operator. Headless. */
class ProfilePanelTest {

	private static final String VENUE = "did:key:z6MkTestVenue";

	@Test
	void theHintsShownUnderTheFieldsFollowWhoTheAppActsAs() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			ProfilePanel page = new ProfilePanel(new Idle());
			page.refresh("Sam", VENUE + ":u:sam", VENUE, "00", false, false, null);
			List<String> asOwner = shown(page);
			page.refresh("Sam", VENUE + ":u:sam", VENUE, "00", false, true, null);
			assertNotEquals(asOwner, shown(page), "the name's hint is the operator's while acting as it");
		});
	}

	/** Every hint spelled out on the page, in order. */
	private static List<String> shown(Component c) {
		List<String> out = new ArrayList<>();
		collect(c, out);
		return out;
	}

	private static void collect(Component c, List<String> out) {
		if (c instanceof SelectableText t && t.isVisible()) out.add(t.getText());
		if (c instanceof Container k) for (Component ch : k.getComponents()) collect(ch, out);
	}

	/** A host that does nothing. */
	private static final class Idle implements ProfilePanel.Host {
		@Override
		public void saveName(String name) {
		}

		@Override
		public String revealPrimarySeed(char[] passphrase) {
			return null;
		}

		@Override
		public char[] rememberedPassphrase() {
			return null;
		}

		@Override
		public void actAs(boolean operator) {
		}
	}
}
