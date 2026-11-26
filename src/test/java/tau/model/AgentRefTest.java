package tau.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import tau.Tau;

class AgentRefTest {

	@Test
	void standardAssistantGoesByTheAppNameUntilNamed() {
		assertEquals(Tau.APP_NAME, AgentRef.displayName(null, "Brightside", "Brightside"),
			"an implicit Brightside id is shown as the app");
		assertEquals("Jarvis", AgentRef.displayName(Maps.of("config", Maps.of("name", "Jarvis")),
			"Brightside", "Brightside"));
		assertEquals(Tau.APP_NAME, AgentRef.displayName(Maps.of("config", Maps.of("name", "  ")),
			"Brightside", "Brightside"), "a blank name is no name");
	}

	@Test
	void otherAgentsGoByTheirId() {
		assertEquals("Helper", AgentRef.displayName(null, "Helper", "Brightside"));
		assertEquals("Helper", AgentRef.displayName(null, "Helper", null));
	}
}
