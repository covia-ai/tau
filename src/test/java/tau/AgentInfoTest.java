package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import tau.AgentInfo;
import tau.AppConfig;
import tau.Tau;
import tau.TauSkillsAdapter;
import tau.EmbeddedVenue;
import tau.Identity;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Venue;
import covia.adapter.TestAdapter;
import covia.venue.AgentEvents;
import covia.venue.Config;

/**
 * Mechanism test for the agent info screen, new instructions and agent
 * deletion: what {@link AgentInfo} reports comes from a real record, new
 * instructions replace only the prompt, and {@code agent:delete}
 * as Tau calls it removes the record, not just marks it terminated. Stopping
 * an agent ends a tool call that would otherwise hold it for an hour.
 */
class AgentInfoTest {

	private static final long TIMEOUT_SECONDS = 30;

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;
	private static Venue client;
	private static String userDID;

	@BeforeAll
	static void boot() {
		venue = shared.venue();
		userDID = Identity.of("info-test").userDID(venue.did());
		client = venue.clientAs(userDID);
	}

	@Test
	void summaryComesFromTheRecordAndDeletionRemovesIt() throws Exception {
		String agentId = "Bob";
		AMap<AString, ACell> config = Maps.of(
			Fields.OPERATION, AppConfig.DEFAULT_OPERATION,
			"llmOperation", AppConfig.ECHO_LLM_OPERATION,
			"systemPrompt", "You are Bob.",
			"defaultTools", true,
			"tools", Vectors.of(Strings.create("v/ops/memory")),
			"skillsets", Vectors.of(Strings.create("w/skills")),
			Fields.LOADS, Maps.of(TauSkillsAdapter.SKILLS,
				Maps.of("skill", true, "budget", 8000L, "label", "Skills")));
		run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, agentId, Fields.CONFIG, config));

		long jobsBefore = RecordedJobs.of(venue, userDID);
		AgentInfo.Summary info = AgentInfo.load(client, venue.agentRecord(userDID, agentId),
			agentId, userDID, "Info Test", false);
		assertNotNull(info);
		assertEquals(jobsBefore, RecordedJobs.of(venue, userDID), "showing an agent is a read: no job record");
		assertEquals(agentId, info.id());
		assertEquals(agentId, info.name(), "no config.name: the id is the name");
		assertEquals(userDID + ":g:" + agentId, info.did());
		assertEquals("SLEEPING", info.status());
		assertNull(info.error());
		assertEquals(AppConfig.ECHO_LLM_OPERATION, info.model());
		assertEquals(AppConfig.DEFAULT_OPERATION, info.operation());
		assertEquals("You are Bob.", info.systemPrompt());
		assertTrue(info.defaultTools());
		assertEquals(List.of("v/ops/memory"), info.tools());
		assertEquals(List.of("w/skills"), info.skillsets());
		assertEquals(List.of(new AgentInfo.Pin(TauSkillsAdapter.SKILLS, "Skills", "skill", 8000L)), info.pins());
		assertTrue(info.unavailable().isEmpty(), "unavailable: " + info.unavailable());
		assertEquals(0, info.conversations());
		assertEquals(0, info.lastActive());
		assertTrue(listedAgents().contains(agentId));

		Tau.setAgentInstructions(client, agentId, "You are Bob, the editor.");
		AgentInfo.Summary edited = AgentInfo.load(client, venue.agentRecord(userDID, agentId),
			agentId, userDID, "Info Test", false);
		assertEquals("You are Bob, the editor.", edited.systemPrompt());
		assertEquals(List.of("v/ops/memory"), edited.tools(), "new instructions keep the rest of the configuration");
		assertEquals(AppConfig.ECHO_LLM_OPERATION, edited.model());

		Tau.deleteAgent(client, agentId);

		assertNull(venue.agentRecord(userDID, agentId), "the record is removed, not merely terminated");
		try (var logs = ExpectedLogs.warn(AgentInfo.class, "Could not read agent info for Bob:")) {
			assertNull(AgentInfo.load(client, null, agentId, userDID, "Info Test", false));
		}
		assertFalse(listedAgents().contains(agentId));
	}

	@Test
	void stopEndsAToolCallThatWillNotReturnAndLeavesTheAgentFree() throws Exception {
		String agentId = "Hung";
		String gateName = "tau-stop-" + System.nanoTime();
		// The model's tool call holds at the gate, which is never released: only
		// the stop or the hour-long tool limit can end the turn.
		try (TestAdapter.TestGate gate = TestAdapter.createGate(gateName)) {
			run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, agentId, Fields.CONFIG, Maps.of(
				Fields.OPERATION, AppConfig.DEFAULT_OPERATION,
				"llmOperation", "v/test/ops/nevertoolllm",
				Fields.MODEL, "hold:" + gateName,
				"tools", Vectors.of(Strings.create("v/test/ops/hold")),
				"caps", Config.UNRESTRICTED,
				"toolCallTimeoutMs", 3_600_000L)));
			client.invoke("v/ops/agent/chat", Maps.of(Fields.AGENT_ID, agentId, "message", "go"))
				.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertTrue(gate.awaitEntered(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the tool call is in flight");

			// The run loop records the stopped cycle and settles the agent's
			// status before it announces the run's end.
			CountDownLatch runEnded = new CountDownLatch(1);
			try (AutoCloseable events = venue.subscribeAgent(userDID, agentId, e -> {
					if (AgentEvents.RUN_END.equals(e.type())) runEnded.countDown();
				})) {
				Tau.stopAgent(client, agentId);
				assertTrue(runEnded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the run never ended");
			}
			assertEquals("SLEEPING", status(agentId), "freed");
			ACell timeline = RT.getIn(venue.agentRecord(userDID, agentId), "timeline");
			assertTrue(timeline instanceof AVector<?> v && v.count() == 1
				&& CVMBool.TRUE.equals(RT.getIn((ACell) v.get(0), "cancelled")),
				"the one turn was cut short: " + timeline);
		}
	}

	private static String status(String agentId) throws Exception {
		return String.valueOf(RT.getIn(run("v/ops/agent/info", Maps.of(Fields.AGENT_ID, agentId)), "status"));
	}

	private static List<String> listedAgents() throws Exception {
		List<String> ids = new ArrayList<>();
		if (RT.getIn(run("v/ops/agent/list", Maps.empty()), "agents") instanceof AVector<?> agents) {
			for (long i = 0; i < agents.count(); i++) {
				ids.add(String.valueOf(RT.getIn((ACell) agents.get(i), Fields.AGENT_ID)));
			}
		}
		return ids;
	}

	private static ACell run(String operation, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke(operation, input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}
}
