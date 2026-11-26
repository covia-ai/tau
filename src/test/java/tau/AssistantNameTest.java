package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import tau.chat.ChatSession;

/**
 * The name the owner's standard assistant's default persona goes by: the one
 * its record sets under the owner's own DID, else the app's, never its id.
 */
@Execution(ExecutionMode.CONCURRENT)
class AssistantNameTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@Test
	void standardAssistantGoesByItsRecordNameElseTheApp() throws Exception {
		EmbeddedVenue venue = shared.venue();
		Identity owner = Identity.of("assistant-name-owner");
		String aid = "Brightside";
		assertEquals(Tau.APP_NAME, Tau.assistantName(null, owner, aid), "before the venue is up");
		assertEquals(Tau.APP_NAME, Tau.assistantName(venue, owner, aid), "no record yet: the app's name, not the id");

		AppConfig.Chat chat = new AppConfig.Chat(aid, AppConfig.DEFAULT_OPERATION,
			AppConfig.ECHO_LLM_OPERATION, "Echo the user.");
		new ChatSession(venue.clientAs(owner.userDID(venue.did())), chat, "Owner").named("Jarvis").ensureAgent();
		assertEquals("Jarvis", Tau.assistantName(venue, owner, aid));
		assertEquals(Tau.APP_NAME, Tau.assistantName(venue, Identity.of("assistant-name-other"), aid),
			"another user's assistant is not read");
	}
}
