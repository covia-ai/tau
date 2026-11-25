package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.core.data.Maps;
import covia.grid.Job;
import covia.grid.Venue;

/**
 * The read behind the agents pane: an agent created through the venue's own
 * {@code agent:create} operation — exactly what the assistant's agent tools
 * call — appears in the in-process {@code g/} listing the pane is built from.
 */
class AgentListingTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;

	@BeforeAll
	static void boot() {
		venue = shared.venue();
	}

	@Test
	void anAgentCreatedByToolsAppearsInTheUsersListing() throws Exception {
		String did = Identity.of("lister").userDID(venue.did());
		Venue user = venue.clientAs(did);
		Job job = user.invoke("v/ops/agent/create", Maps.of(
			"agentId", "Amy",
			"config", Maps.of("name", "Amy"))).get(30, TimeUnit.SECONDS);
		job.future().get(30, TimeUnit.SECONDS);

		assertTrue(venue.agents(did).contains("Amy"),
			"the created agent is in the listing the pane reads");
		assertNotNull(venue.agentRecord(did, "Amy"), "its record reads in-process");
	}

	@Test
	void eachAgentsStateIsAsCoviaObservesIt() throws Exception {
		String did = Identity.of("roster").userDID(venue.did());
		Venue user = venue.clientAs(did);
		run(user, "v/ops/agent/create", Maps.of("agentId", "Bea"));
		assertEquals("SLEEPING", venue.agentStatuses(did).get("Bea"), "a new agent waits for work");
		run(user, "v/ops/agent/suspend", Maps.of("agentId", "Bea", "reason", "checking the roster"));
		assertEquals("SUSPENDED", venue.agentStatuses(did).get("Bea"), "a stopped one says so");
	}

	private static void run(Venue user, String op, convex.core.data.ACell input) throws Exception {
		Job job = user.invoke(op, input).get(30, TimeUnit.SECONDS);
		job.future().get(30, TimeUnit.SECONDS);
	}
}
