package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.grid.Principals;
import covia.grid.Venue;
import covia.venue.Engine;
import covia.venue.RequestContext;
import tau.chat.ChatSession;
import tau.model.AgentPermissions;
import tau.model.AgentTemplate;

/**
 * The main assistant's scoped set reaches everything its skills, tools and
 * loads use: each area is called as the assistant through the venue, as its
 * tools call it, and no call is refused for want of a grant. Its overwrites
 * and deletes wait for the owner, as a template agent's do, and what lies
 * outside the owner's namespace stays refused.
 */
class AssistantScopeTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@BeforeAll
	static void shortWaits() {
		ShortGateWaits.hold();
	}

	@AfterAll
	static void restoreWaits() {
		ShortGateWaits.release();
	}

	@Test
	void aNewAssistantGetsTheScopedSetAndStillCompletesATurn() throws Exception {
		AString owner = owner("scope-turn");
		ChatSession tau = assistant(owner);
		assertNotNull(tau.send("Hello there").text(), "a turn, with its memory and context loads");
		ACell caps = RT.getIn(shared.venue().agentRecord(owner.toString(), "Tau"), "config", "caps");
		assertEquals(AgentPermissions.ASSISTANT, caps, "the scoped set, not Unrestricted");
		assertFalse(AgentPermissions.unrestricted(caps));
	}

	@Test
	void memoryNotesWorkspaceFilesAndDrives() throws Exception {
		AString owner = owner("scope-data");
		assistant(owner);
		RequestContext tau = agent(owner);
		allowed("v/ops/covia/write", Maps.of("path", "n/scratch", "value", "mine"), tau);
		allowed("v/ops/memory", Maps.of("path", "w/memory", "command", "remember", "text", "Likes tea"), tau);
		allowed("v/ops/covia/read", Maps.of("path", "w/memory"), tau);
		allowed("v/ops/memory-recall", Maps.of("path", "n/memory"), tau);
		allowed("v/ops/covia/write", Maps.of("path", "w/notes/plan", "value", "first"), tau);
		allowed("v/ops/covia/list", Maps.of("path", "w/"), tau);
		allowed(ChatSession.INSPECT_OP, Maps.of("path", "w/notes"), tau);
		String name = "scope-" + System.nanoTime() + ".txt";
		allowed("v/ops/file/write", Maps.of("root", "files", "path", name, "content", "one"), tau);
		allowed("v/ops/file/read", Maps.of("root", "files", "path", name), tau);
		allowed("v/ops/file/list", Maps.of("root", "files"), tau);
		allowed("v/ops/dlfs/list-drives", Maps.empty(), tau);
		allowed("v/ops/dlfs/create-drive", Maps.of("drive", "scope"), tau);
		allowed("v/ops/dlfs/write", Maps.of("drive", "scope", "path", "a.txt", "content", "x"), tau);
		allowed("v/ops/dlfs/read", Maps.of("drive", "scope", "path", "a.txt"), tau);
		allowed("v/ops/vault/mkdir", Maps.of("path", "scope"), tau);
		allowed("v/ops/vault/list", Maps.empty(), tau);
		assertEquals(0, open(owner), "new items, its notes and the shared memory never ask");
	}

	@Test
	void overwritesAndDeletesWaitForTheOwner() throws Exception {
		AString owner = owner("scope-gate");
		assistant(owner);
		RequestContext tau = agent(owner);
		Venue client = shared.venue().clientAs(owner.toString());
		run("v/ops/covia/write", Maps.of("path", "w/notes/plan", "value", "first"), tau);
		Exception waiting = assertThrows(Exception.class,
			() -> run("v/ops/covia/write", Maps.of("path", "w/notes/plan", "value", "second"), tau));
		assertTrue(rootMessage(waiting).contains("Waiting for the owner's approval"), rootMessage(waiting));
		assertEquals(1, open(owner), "one card for the overwrite");
		answer(client, owner, true);
		run("v/ops/covia/write", Maps.of("path", "w/notes/plan", "value", "second"), tau);
		assertThrows(Exception.class, () -> run("v/ops/covia/delete", Maps.of("path", "w/notes/plan"), tau));
		assertEquals(1, open(owner), "and one for the delete, a scheduled run's as well");
		answer(client, owner, false);
	}

	@Test
	void theInternetConnectionsAndKeys() throws Exception {
		AString owner = owner("scope-net");
		assistant(owner);
		RequestContext tau = agent(owner);
		// What stops a fetch to this computer is the address policy, not a missing grant.
		for (String op : List.of("v/ops/http/get", "v/ops/http/post")) {
			Exception e = assertThrows(Exception.class, () -> run(op, Maps.of("url", "http://127.0.0.1:9/", "body", "x"), tau));
			assertTrue(rootMessage(e).contains("private/internal"), op + ": " + rootMessage(e));
		}
		allowed("v/ops/secret/set", Maps.of("name", "SCOPE_KEY", "value", "not-a-real-key"), tau);
		allowed("v/ops/secret/list", Maps.empty(), tau);
		// A connection's bearer and Moltbook's key are resolved this way: used without being seen.
		assertEquals("not-a-real-key", engine().resolveSecret("s/SCOPE_KEY", tau), "a stored key, used for a request");
		assertTrue(covers(tau, "oauth/github", "oauth/use"), "a connected account's token, likewise");
		allowed("v/ops/oauth/status", Maps.empty(), tau);
		allowed("v/ops/connections/list", Maps.empty(), tau);
		allowed("v/ops/mcp/list-tokens", Maps.empty(), tau);
	}

	@Test
	void otherAgentsTheGodsSchedulesTheInboxAndRooms() throws Exception {
		AString owner = owner("scope-agents");
		assistant(owner);
		RequestContext tau = agent(owner);
		allowed("v/ops/agent/create", Maps.of("agentId", "child", "config", Maps.of(
			"operation", AppConfig.DEFAULT_OPERATION, "llmOperation", AppConfig.ECHO_LLM_OPERATION, "acceptTasks", true)), tau);
		allowed("v/ops/agent/request", Maps.of("agentId", "child", "input", "hello", "wait", 15_000L), tau);
		allowed("v/ops/agent/message", Maps.of("agentId", "child", "message", "hello again"), tau);
		allowed("v/ops/agent/sessions", Maps.of("agentId", "Tau"), tau);
		Gods.ensureAll(engine(), AppConfig.ECHO_LLM_OPERATION);
		allowed("v/ops/tau/ask-odin", Maps.of("request", "Relaunch into the new build, please.", "timeout", 15_000L), tau);
		allowed("v/ops/scheduler/schedule", Maps.of("operation", "v/ops/tau/info", "input", Maps.empty(),
			"after", 3_600_000L), tau);
		allowed("v/ops/scheduler/list", Maps.empty(), tau);
		allowed("v/ops/hitl/list", Maps.empty(), tau);
		String room = "scope-" + System.nanoTime();
		allowed("v/ops/room/create", Maps.of("room", room), tau);
		allowed("v/ops/room/post", Maps.of("room", room, "text", "Hello, room"), tau);
		allowed("v/ops/room/read", Maps.of("room", room), tau);
	}

	@Test
	void skillsToolsClaudeCodeAndTheLoop() throws Exception {
		AString owner = owner("scope-loop");
		assistant(owner);
		RequestContext tau = agent(owner);
		allowed("v/ops/skills/list", Maps.empty(), tau);
		allowed(ChatSession.CONTEXT_OP, Maps.empty(), tau);
		allowed("v/ops/tau/info", Maps.empty(), tau);
		allowed("v/ops/tau/report-skill-feedback", Maps.of("category", "improvement", "summary", "A test idea",
			"failedAction", "none"), tau);
		allowed("v/ops/speech/engines", Maps.empty(), tau);
		allowed("v/ops/skills/read", Maps.of("skill", "v/skills/root/skills"), tau);
		allowed("v/ops/claudecode/projects", Maps.empty(), tau);
		allowed("v/ops/claudecode/sessions", Maps.empty(), tau);
		// Running Claude Code needs the CLI, so the check its adapter makes (Tau makes the projects the owner's,
		// ClaudeCode.own) is made here as it makes it.
		engine().requireLocalAccess(tau, Strings.create(owner + "/claudecode/" + ClaudeCode.PROJECT), Strings.create("claudecode/run"));
	}

	@Test
	void whatNeedsAnExtraGrantIsRefused() throws Exception {
		AString owner = owner("scope-more");
		assistant(owner);
		RequestContext tau = agent(owner);
		String venue = engine().getDIDString().toString();
		needsMore("v/ops/agent/fork", Maps.of("sourceId", "Tau", "agentId", "copy"), tau);
		needsMore("v/ops/secret/delete", Maps.of("name", "nosuch"), tau);
		needsMore("v/ops/secret/extract", Maps.of("name", "nosuch"), tau);
		needsMore("v/ops/mcp/remove-server", Maps.of("name", "nosuch"), tau);
		needsMore("v/ops/oauth/disconnect", Maps.of("provider", "github"), tau);
		needsMore("v/ops/ucan/issue", Maps.of("aud", venue, "att", convex.core.data.Vectors.of(
			Maps.of("with", "w/", "can", "crud/read"))), tau);
		needsMore("v/ops/venue/show-config", Maps.empty(), tau);
		needsMore("v/ops/venue/adapters", Maps.empty(), tau);
		needsMore("v/ops/user/list", Maps.empty(), tau);
		assertEquals(AgentPermissions.NEEDS_MORE.size(), 8, "the page lists each of these");
	}

	@Test
	void outsideTheOwnersNamespaceStaysRefused() throws Exception {
		AString owner = owner("scope-outside");
		assistant(owner);
		RequestContext tau = agent(owner);
		assertFalse(covers(tau, engine().getDIDString() + "/w/", "crud/write"), "the venue's own workspace");
		assertFalse(covers(tau, "s/SCOPE_KEY", "secret/extract"), "seeing a secret's value");
		assertFalse(covers(tau, "", "*"), "everything");
	}

	@Test
	void itCannotWidenItsOwnCapsOrMakeAnAgentThatDoesNotAsk() throws Exception {
		AString owner = owner("scope-escape");
		assistant(owner);
		RequestContext tau = agent(owner);
		run("v/ops/covia/write", Maps.of("path", "w/keep", "value", "kept"), tau);
		// Unrestricted, or its own set with the gates taken off: refused outright, nothing for the owner to wave through.
		assertThrows(Exception.class, () -> run("v/ops/agent/update", Maps.of("agentId", "Tau",
			"config", Maps.of("caps", covia.venue.Config.UNRESTRICTED)), tau), "it may not make itself Unrestricted");
		Exception ungated = assertThrows(Exception.class, () -> run("v/ops/agent/update", Maps.of("agentId", "Tau",
			"config", Maps.of("caps", withoutGates(AgentPermissions.ASSISTANT))), tau), "nor take its own gates off");
		assertTrue(rootMessage(ungated).contains("Refused"), rootMessage(ungated));
		assertThrows(Exception.class, () -> run("v/ops/agent/create", Maps.of("agentId", "deleter",
			"config", Maps.of("operation", AppConfig.DEFAULT_OPERATION, "caps", convex.core.data.Vectors.of(
				Maps.of("with", "w/", "can", "crud/delete"), Maps.of("with", "v/ops/", "can", "invoke")))), tau),
			"nor give another agent a delete that does not ask");
		assertEquals(0, open(owner), "none of these asks the owner");
		assertEquals(AgentPermissions.ASSISTANT, RT.getIn(shared.venue().agentRecord(owner.toString(), "Tau"), "config", "caps"));
		assertEquals(null, shared.venue().agentRecord(owner.toString(), "deleter"));

		// An agent it makes without caps gets none, so it can do nothing of its own.
		run("v/ops/agent/create", Maps.of("agentId", "bare", "config", Maps.of("operation", AppConfig.DEFAULT_OPERATION)), tau);
		assertEquals(convex.core.data.Vectors.empty(),
			RT.getIn(shared.venue().agentRecord(owner.toString(), "bare"), "config", "caps"));
		assertThrows(Exception.class, () -> run("v/ops/covia/delete", Maps.of("path", "w/keep"), agent(owner, "bare")));

		// Gated grants it may hand on, once the owner approves exactly those; the card says what they are.
		ACell helperCaps = AgentPermissions.caps(AgentTemplate.WRITING);
		AMapInput create = () -> Maps.of("agentId", "helper", "config", Maps.of("operation", AppConfig.DEFAULT_OPERATION,
			"caps", helperCaps));
		assertThrows(Exception.class, () -> run("v/ops/agent/create", create.get(), tau));
		Inbox.Request card = requests(owner).stream().filter(Inbox.Request::open).findFirst().orElseThrow();
		assertTrue(card.description().contains("Delete from your workspace"), card.description());
		answer(shared.venue().clientAs(owner.toString()), owner, true);
		run("v/ops/agent/create", create.get(), tau);
		assertEquals(helperCaps, RT.getIn(shared.venue().agentRecord(owner.toString(), "helper"), "config", "caps"));
		assertEquals("kept", String.valueOf(read(owner, "w/keep")));
	}

	@Test
	void otherVerbsOverSomethingThereAsk() throws Exception {
		AString owner = owner("scope-verbs");
		assistant(owner);
		RequestContext tau = agent(owner);
		Venue client = shared.venue().clientAs(owner.toString());
		String name = "verbs-" + System.nanoTime() + ".txt";
		run("v/ops/file/create", Maps.of("root", "files", "path", name, "content", Maps.of("inline", "one")), tau);
		// create never replaces: over a file that is there the adapter refuses it, and nobody is asked.
		assertThrows(Exception.class, () -> run("v/ops/file/create", Maps.of("root", "files", "path", name,
			"content", Maps.of("inline", "two")), tau));
		assertEquals(0, open(owner));
		// A verb the gate can't read the effect of asks rather than slipping through.
		assertThrows(Exception.class, () -> run("v/ops/archive/extract", Maps.of("root", "files", "path", "none.zip",
			"destRoot", "files", "destPath", "out"), tau));
		assertEquals(1, open(owner), "extracting, whose effect the gate can't tell, asks");
		answer(client, owner, false);
	}

	@Test
	void theOwnerSwitchesItBetweenTheScopedSetAndUnrestricted() throws Exception {
		AString owner = owner("scope-switch");
		ChatSession s = new ChatSession(shared.venue().clientAs(owner.toString()),
			new AppConfig.Chat("Tau", AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo."), "Sam")
			.withCaps(covia.venue.Config.UNRESTRICTED);
		assertTrue(s.ensureAgent(), "an older install's assistant, Unrestricted");
		RequestContext tau = agent(owner);
		Venue client = shared.venue().clientAs(owner.toString());
		run("v/ops/covia/write", Maps.of("path", "w/plan", "value", "one"), tau);
		run("v/ops/covia/write", Maps.of("path", "w/plan", "value", "two"), tau);
		assertEquals(0, open(owner), "Unrestricted: its overwrites don't ask");

		Tau.setAgentCaps(client, "Tau", false);
		assertEquals(AgentPermissions.ASSISTANT, RT.getIn(shared.venue().agentRecord(owner.toString(), "Tau"), "config", "caps"));
		assertThrows(Exception.class, () -> run("v/ops/covia/write", Maps.of("path", "w/plan", "value", "three"), tau));
		assertEquals(1, open(owner), "the scoped set: an overwrite asks");
		answer(client, owner, false);
		assertTrue(s.ensureAgent(), "setting it up again");
		assertEquals(AgentPermissions.ASSISTANT, RT.getIn(shared.venue().agentRecord(owner.toString(), "Tau"), "config", "caps"),
			"keeps what the owner chose");

		Tau.setAgentCaps(client, "Tau", true);
		run("v/ops/covia/write", Maps.of("path", "w/plan", "value", "four"), tau);
		assertEquals(0, open(owner), "Unrestricted again: no card");
		assertEquals("four", String.valueOf(read(owner, "w/plan")));
	}

	@Test
	void replacingAStoredKeyAsks() throws Exception {
		AString owner = owner("scope-secret");
		assistant(owner);
		RequestContext tau = agent(owner);
		run("v/ops/secret/set", Maps.of("name", "SCOPE_KEY", "value", "first"), tau);
		assertEquals(0, open(owner), "a new key, no card");
		assertThrows(Exception.class, () -> run("v/ops/secret/set", Maps.of("name", "SCOPE_KEY", "value", "second"), tau));
		assertEquals(1, open(owner), "replacing it asks");
		answer(shared.venue().clientAs(owner.toString()), owner, false);
		// Deleting a stored key is not in the set (no shipped skill does it): refused outright.
		assertThrows(Exception.class, () -> run("v/ops/secret/delete", Maps.of("name", "SCOPE_KEY"), tau));
		assertEquals(0, open(owner));
	}

	@Test
	void aScheduledDeleteWaitsForTheOwnerToo() throws Exception {
		AString owner = owner("scope-schedule");
		assistant(owner);
		RequestContext tau = agent(owner);
		run("v/ops/covia/write", Maps.of("path", "w/keep", "value", "kept"), tau);
		ACell scheduled = run("v/ops/scheduler/schedule", Maps.of("operation", "v/ops/covia/delete",
			"input", Maps.of("path", "w/keep"), "after", 3_600_000L), tau);
		try {
			run("v/ops/scheduler/trigger", Maps.of("handle", RT.getIn(scheduled, "handle")), tau);
		} catch (Exception refused) {
			// The fired delete is refused while it waits; what matters is the card and the item.
		}
		long until = System.currentTimeMillis() + 10_000;
		while (open(owner) == 0 && System.currentTimeMillis() < until) Thread.onSpinWait();
		assertEquals(1, open(owner), "one card for the scheduled delete");
		assertEquals("kept", String.valueOf(read(owner, "w/keep")), "the item is still there");
		// Approved, the next run of the same delete goes ahead.
		answer(shared.venue().clientAs(owner.toString()), owner, true);
		ACell again = run("v/ops/scheduler/schedule", Maps.of("operation", "v/ops/covia/delete",
			"input", Maps.of("path", "w/keep"), "after", 3_600_000L), tau);
		run("v/ops/scheduler/trigger", Maps.of("handle", RT.getIn(again, "handle")), tau);
		until = System.currentTimeMillis() + 10_000;
		while (read(owner, "w/keep") != null && System.currentTimeMillis() < until) Thread.onSpinWait();
		assertEquals(null, read(owner, "w/keep"), "approved: deleted");
	}

	// ------------------------------------------------------------------

	private static ChatSession assistant(AString owner) throws Exception {
		ChatSession s = new ChatSession(shared.venue().clientAs(owner.toString()),
			new AppConfig.Chat("Tau", AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo."), "Sam")
			.withCaps(AgentPermissions.ASSISTANT);
		assertTrue(s.ensureAgent());
		return s;
	}

	private interface AMapInput {
		ACell get();
	}

	/** {@code caps} with every gate taken off. */
	private static ACell withoutGates(convex.core.data.AVector<ACell> caps) {
		convex.core.data.AVector<ACell> out = convex.core.data.Vectors.empty();
		for (ACell c : caps) out = out.conj(((convex.core.data.AMap<AString, ACell>) c).dissoc(Strings.create("nb")));
		return out;
	}

	private static RequestContext agent(AString owner) {
		return agent(owner, "Tau");
	}

	private static RequestContext agent(AString owner, String id) {
		return engine().context(owner, Principals.agentDID(owner, Strings.create(id)));
	}

	private static ACell read(AString owner, String path) throws Exception {
		return RT.getIn(run("v/ops/covia/read", Maps.of("path", path), RequestContext.of(owner)), "value");
	}

	private static ACell run(String op, ACell input, RequestContext ctx) throws Exception {
		return engine().jobs().invokeInternal(op, input, ctx).get(20, TimeUnit.SECONDS);
	}

	/** Runs {@code op} as the assistant: it must succeed. */
	private static void allowed(String op, ACell input, RequestContext ctx) {
		try {
			run(op, input, ctx);
		} catch (Exception e) {
			throw new AssertionError(op + " failed under the assistant's set: " + rootMessage(e), e);
		}
	}

	/** Runs {@code op} as the assistant: refused for want of a grant, not for its input. */
	private static void needsMore(String op, ACell input, RequestContext ctx) {
		Exception e = assertThrows(Exception.class, () -> run(op, input, ctx), op + " should need an extra grant");
		String m = rootMessage(e);
		assertTrue(m.contains("Capability denied") || m.contains("scope does not include") || m.contains("administration denied")
			|| m.contains("requires a direct owner run"), op + ": " + m);
	}

	private static boolean covers(RequestContext ctx, String resource, String ability) {
		return engine().authorityCovers(ctx, Strings.create(resource), Strings.create(ability));
	}

	private static List<Inbox.Request> requests(AString owner) {
		return Inbox.parse(shared.venue().inbox(owner.toString()), owner.toString());
	}

	private static int open(AString owner) {
		return (int) requests(owner).stream().filter(Inbox.Request::open).count();
	}

	private static void answer(Venue client, AString owner, boolean ok) throws Exception {
		Inbox.Request r = requests(owner).stream().filter(Inbox.Request::open).findFirst().orElseThrow();
		Inbox.answer(client, r.id(), new Inbox.Answer(Map.of("ok", ok), Map.of(), List.of(), null));
		long until = System.currentTimeMillis() + 10_000;
		while (requests(owner).stream().anyMatch(x -> x.id().equals(r.id()) && x.open()) && System.currentTimeMillis() < until) {
			Thread.onSpinWait();
		}
	}

	private static String rootMessage(Throwable t) {
		Throwable c = t;
		while (c.getCause() != null && c.getCause() != c) c = c.getCause();
		return String.valueOf(c.getMessage());
	}

	private static AString owner(String name) {
		AString did = Strings.create(Identity.of(name).userDID(shared.venue().did()));
		engine().getVenueState().users().ensure(did.toString());
		return did;
	}

	private static Engine engine() {
		return shared.venue().engine();
	}
}
