package tau.ui.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import tau.SessionHistory;
import tau.ui.chat.ChatPanel;
import tau.ui.inspect.SessionViews.View;

/** The chat and its inspector views as the window's width docks and undocks the inspector. Headless. */
class SessionViewsTest {

	@Test
	void narrowingTheWindowKeepsTheChatInFront() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			SessionViews views = new SessionViews(new ChatPanel(), new Idle());
			views.setTabsVisible(true);
			views.setInspectorDocked(true);
			views.select(View.CONTEXT);
			assertEquals(View.CHAT, views.front(), "docked, the inspector shows beside the chat");
			views.setInspectorDocked(false);
			assertEquals(View.CHAT, views.front(), "undocked, the chat stays in front");

			views.select(View.CONTEXT);
			views.setInspectorDocked(true);
			assertEquals(View.CHAT, views.front(), "docking moves the tab in front beside the chat");
		});
	}

	/** A host that reads nothing. */
	private static final class Idle implements SessionViews.Host {
		@Override
		public void loadSession(String sessionId, int version) {
		}

		@Override
		public void loadAgent(String agentId, int version) {
		}

		@Override
		public void saveInstructions(String agentId, String instructions) {
		}

		@Override
		public void stopAgent(String agentId) {
		}

		@Override
		public void showSkill(String path) {
		}

		@Override
		public void sessionsChanged(List<SessionHistory.Session> sessions, String selectedId) {
		}

		@Override
		public void agentsChanged() {
		}
	}
}
