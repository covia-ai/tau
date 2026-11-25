package tau.ui.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import tau.SessionHistory;
import tau.ui.ReadsLook;
import tau.ui.chat.ChatPanel;
import tau.ui.inspect.SessionViews.View;

/** The chat and its inspector views as the window's width docks and undocks the inspector. Headless. */
@ReadsLook
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

			views.setInspectorDocked(true);
			assertEquals(View.CONTEXT, views.beside(), "docked again, the inspector keeps its page");

			views.setInspectorDocked(false);
			views.select(View.AGENT);
			views.setInspectorDocked(true);
			assertEquals(View.CHAT, views.front());
			assertEquals(View.AGENT, views.beside(), "docking moves the tab in front beside the chat");
		});
	}

	@Test
	void leavingAndComingBackKeepsWhatWasChosen() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			SessionViews views = new SessionViews(new ChatPanel(), new Idle());
			views.setTabsVisible(true);
			views.setInspectorDocked(true);
			views.select(View.CONTEXT);
			// Home: the tabs go, and the inspector with them, as the window does.
			views.setTabsVisible(false);
			views.setInspectorDocked(false);
			views.setInspectorDocked(true);
			views.setTabsVisible(true);
			assertEquals(View.CHAT, views.front());
			assertEquals(View.CONTEXT, views.beside(), "back on a wide window, the inspector's page");

			views.setInspectorDocked(false);
			views.select(View.AGENT);
			views.setTabsVisible(false);
			views.setTabsVisible(true);
			assertEquals(View.AGENT, views.front(), "on a narrow one, the tab that was in front");
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
