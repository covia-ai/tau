package tau.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A message holding something shaped like a key is caught before it is sent:
 * stored as a secret on request (and not sent), sent only when the owner
 * says so, kept otherwise; a message with no key is never asked about.
 * Headless.
 */
@tau.ui.ReadsLook
class KeyCatchTest {

	private static final String KEY = "sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWx";

	@Test
	void aKeyIsCaughtBeforeItIsSent() throws Exception {
		List<String> stored = new ArrayList<>();
		List<String> asked = new ArrayList<>();
		ChatPanel[] made = new ChatPanel[1];
		javax.swing.SwingUtilities.invokeAndWait(() -> made[0] = new ChatPanel());
		ChatPanel chat = made[0];
		chat.setHost(new ChatPanel.Host() {
			public void newConversation() { }
			public void inspectConversation(String sessionId) { }
			public void showInSessions(String sessionId) { }
			@Override public boolean storeSecret(String name, String value) {
				stored.add(name + "=" + value);
				return true;
			}
		});
		chat.askAboutKey = found -> {
			asked.add(found.secretName());
			return ChatPanel.KeyChoice.STORE;
		};
		assertTrue(chat.keyChecked("summarise my notes"), "no key: sent, nothing asked");
		assertTrue(asked.isEmpty());

		assertFalse(chat.keyChecked("use " + KEY + " for this"), "stored instead of sent");
		assertEquals(List.of("ANTHROPIC_API_KEY=" + KEY), stored);

		chat.askAboutKey = found -> ChatPanel.KeyChoice.SEND;
		assertTrue(chat.keyChecked("use " + KEY), "sent only when the owner says so");
		chat.askAboutKey = found -> ChatPanel.KeyChoice.KEEP;
		assertFalse(chat.keyChecked("use " + KEY), "kept in the box otherwise");
		assertEquals(1, stored.size());
	}
}
