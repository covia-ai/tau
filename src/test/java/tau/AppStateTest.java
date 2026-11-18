package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The chosen model and the app's settings are kept in Tau's adapter
 * state in the venue store, one set for the installation, outside every
 * person's namespace.
 */
class AppStateTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@Test
	void theChosenModelIsKeptInTheAdaptersState() {
		EmbeddedVenue venue = shared.venue();
		TauAdapter app = venue.tau();
		app.saveModel("v/models/anthropic/claude-opus-5");
		assertEquals("v/models/anthropic/claude-opus-5", app.model());

		covia.venue.AdapterState state = venue.engine().adapterState("brightside");
		assertEquals(convex.core.data.Strings.create("v/models/anthropic/claude-opus-5"),
			state.read(TauAdapter.MODEL));
	}

	@Test
	void settingsAreKeptInTheAdaptersState() {
		TauAdapter app = shared.venue().tau();
		app.saveFlag(TauAdapter.READ_ALOUD, true);
		app.saveSendAfterPauseSeconds(-3);
		assertEquals(Boolean.TRUE, app.flag(TauAdapter.READ_ALOUD));
		assertEquals(null, app.flag(TauAdapter.KEEP_TOOL_TOKEN), "unset until chosen");
		assertEquals(0, app.sendAfterPauseSeconds(), "a negative pause is never");
		app.saveSendAfterPauseSeconds(4);
		assertEquals(4, app.sendAfterPauseSeconds());
	}

}
