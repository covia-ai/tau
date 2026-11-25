package tau.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Principals;
import covia.venue.Engine;
import covia.venue.RequestContext;
import tau.AppConfig;
import tau.Identity;
import tau.SharedVenue;

/**
 * Stop ends the whole run: the chat job, the agent's turn (the agent is left
 * resumed, ready for the next message), and every job the run started that is
 * still going, found by its parent or as the agent's own since the run began;
 * a step that already finished keeps its result; a Claude Code run is known
 * by its session.
 */
class StopRunTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@Test
	void stopEndsTheChatJobTheTurnAndEveryJobItStarted() throws Exception {
		Engine engine = shared.venue().engine();
		AString owner = Strings.create(Identity.of("stop-owner").userDID(shared.venue().did()));
		engine.getVenueState().users().ensure(owner.toString());
		new ChatSession(shared.venue().clientAs(owner.toString()), new AppConfig.Chat("Tau", AppConfig.DEFAULT_OPERATION,
			AppConfig.ECHO_LLM_OPERATION, "Echo."), "Tau").ensureAgent();
		RequestContext me = RequestContext.of(owner);
		RequestContext agent = engine.context(owner, Principals.agentDID(owner, Strings.create("Tau")));

		Job chat = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), me);
		Job done = engine.jobs().invokeOperation("v/test/ops/echo", Maps.of("message", "a finished step"), agent);
		done.awaitResult(20_000);
		Job running = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), agent);
		Job unrelated = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), me);

		StopRun.Result r = StopRun.stop(engine, owner, chat.getID(), "Tau");

		assertEquals("CANCELLED", status(engine, me, chat), "the chat job");
		assertEquals("CANCELLED", status(engine, me, running), "a job the run started, still going");
		assertEquals("COMPLETE", status(engine, me, done), "a finished step keeps its result");
		assertNotEquals("CANCELLED", status(engine, me, unrelated), "nothing outside the run");
		assertTrue(r.jobsCancelled() >= 1);
		assertTrue(r.turnInterrupted(), "the agent's turn was interrupted");
		ACell info = engine.jobs().invokeInternal("v/ops/agent/info", Maps.of("agentId", "Tau"), me).get(20, TimeUnit.SECONDS);
		assertNotEquals("SUSPENDED", String.valueOf(RT.getIn(info, "status")), "and the agent is ready for the next message");
		engine.jobs().cancelJob(unrelated.getID(), me);
	}

	@Test
	void theRunIsTracedByParentAndByTheAgentsOwnJobs() {
		String chat = "aa", agent = "did:key:z6Mk:u:sam:g:Tau";
		Index<Blob, ACell> jobs = Index.none();
		jobs = put(jobs, "aa", Maps.of("caller", "did:key:z6Mk:u:sam", "created", 100L));
		jobs = put(jobs, "b1", Maps.of("parent", "0xaa", "created", 110L)); // a sub-job of the chat job
		jobs = put(jobs, "b2", Maps.of("parent", "b1", "created", 120L)); // its own sub-job
		jobs = put(jobs, "c1", Maps.of("caller", agent, "created", 130L)); // the agent's own, since the run began
		jobs = put(jobs, "c2", Maps.of("parent", "c1", "created", 140L)); // a task it handed on
		jobs = put(jobs, "d1", Maps.of("caller", agent, "created", 50L)); // the agent's, before this run
		jobs = put(jobs, "e1", Maps.of("caller", "did:key:z6Mk:u:sam", "created", 150L)); // someone else's
		List<String> run = StopRun.started(jobs, chat, agent, 100L).stream().map(Blob::toHexString).sorted().toList();
		assertEquals(List.of("b1", "b2", "c1", "c2"), run);
		// Another conversation on the same agent keeps running.
		jobs = put(jobs, "f1", Maps.of("caller", agent, "created", 160L, "sessionId", "0xbb"));
		jobs = put(jobs, "f2", Maps.of("caller", agent, "created", 160L, "sessionId", "0xaa"));
		List<String> inSession = StopRun.started(jobs, chat, agent, 100L, "0xaa").stream().map(Blob::toHexString).sorted()
			.toList();
		assertTrue(inSession.contains("f2") && !inSession.contains("f1"), String.valueOf(inSession));
	}

	@Test
	void aClaudeCodeRunIsKnownByItsSession() {
		assertEquals("s-1", StopRun.claudeSession(Maps.of(Strings.create("op"), Strings.create("v/ops/claudecode/run"),
			Strings.create("input"), Maps.of("session", "s-1"))));
		assertEquals("s-2", StopRun.claudeSession(Maps.of(Strings.create("op"), Strings.create("v/ops/claudecode/run"),
			Strings.create("output"), Maps.of("session", "s-2"))));
		assertNull(StopRun.claudeSession(Maps.of(Strings.create("op"), Strings.create("v/ops/http/get"))));
	}

	private static Index<Blob, ACell> put(Index<Blob, ACell> jobs, String hex, ACell rec) {
		return jobs.assoc(Blob.fromHex(hex), rec);
	}

	private static String status(Engine engine, RequestContext me, Job job) {
		return String.valueOf(RT.getIn(engine.jobs().getJobData(job.getID(), me), "status"));
	}
}
