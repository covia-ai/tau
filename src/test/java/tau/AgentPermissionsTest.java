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
 * Agents Tau makes start with their template's caps, not Unrestricted: one
 * still completes a turn with its context loads, its caps read back from its
 * record, what lies outside its template is refused (the internet is not a
 * separate grant in Covia, so no template keeps an agent off it), and setting up an existing
 * agent never touches its caps. Its writes and deletes pass the change gate: a
 * new item goes straight through; an overwrite or a delete raises one Inbox
 * card, is refused as waiting until the owner answers, and then goes ahead
 * (approved) or stays refused (declined); its own notes and the shared memory
 * never ask. The calls here are made as the agent through the venue, as its
 * tools make them (the test model cannot script a tool call).
 */
class AgentPermissionsTest {

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
	void aTemplateAgentStillCompletesATurnAndItsCapsReadBack() throws Exception {
		AString owner = owner("perm-turn");
		ChatSession writer = session(owner, "writer").withCaps(AgentPermissions.caps(AgentTemplate.WRITING));
		assertTrue(writer.ensureAgent());
		ChatSession.Reply reply = writer.send("Hello there");
		assertNotNull(reply.text(), "the turn ran, its memory and context loads within its caps");
		ACell caps = RT.getIn(shared.venue().agentRecord(owner.toString(), "writer"), "config", "caps");
		assertEquals(AgentPermissions.caps(AgentTemplate.WRITING), caps, "the template's caps, not Unrestricted");
		assertFalse(AgentPermissions.unrestricted(caps));
	}

	@Test
	void whatLiesOutsideTheTemplateIsRefused() throws Exception {
		AString owner = owner("perm-limits");
		assertTrue(session(owner, "writing").withCaps(AgentPermissions.caps(AgentTemplate.WRITING)).ensureAgent());
		assertTrue(session(owner, "research").withCaps(AgentPermissions.caps(AgentTemplate.RESEARCH)).ensureAgent());
		RequestContext writing = agent(owner, "writing");
		RequestContext research = agent(owner, "research");
		// Covia has no grant for the internet apart from running operations: what stops a fetch to this
		// computer is the venue's address policy, for every template alike.
		Exception fetch = assertThrows(Exception.class, () -> run("v/ops/http/get", Maps.of("url", "http://127.0.0.1:9/"), writing));
		assertTrue(rootMessage(fetch).contains("private/internal"), rootMessage(fetch));
		run("v/ops/covia/write", Maps.of("path", "w/research/a", "value", "x"), research); // new: goes ahead
		assertThrows(Exception.class, () -> run("v/ops/covia/delete", Maps.of("path", "w/research/a"), research),
			"Research may not delete");
	}

	@Test
	void settingUpAnExistingAgentLeavesItsCapsAlone() throws Exception {
		AString owner = owner("perm-existing");
		assertTrue(session(owner, "kept").withCaps(AgentPermissions.caps(AgentTemplate.BLANK)).ensureAgent());
		assertTrue(new ChatSession(shared.venue().clientAs(owner.toString()), chat("kept"), "Sam", true).ensureAgent());
		assertEquals(AgentPermissions.caps(AgentTemplate.BLANK),
			RT.getIn(shared.venue().agentRecord(owner.toString(), "kept"), "config", "caps"));
	}

	@Test
	void overwritesAndDeletesWaitForTheOwnerAndNewItemsDoNot() throws Exception {
		AString owner = owner("perm-gate");
		assertTrue(session(owner, "editor").withCaps(AgentPermissions.caps(AgentTemplate.WRITING)).ensureAgent());
		RequestContext editor = agent(owner, "editor");
		Venue client = shared.venue().clientAs(owner.toString());

		run("v/ops/covia/write", Maps.of("path", "w/notes/plan", "value", "first"), editor);
		assertEquals(0, open(owner), "a new item goes straight through, no card");
		run("v/ops/covia/write", Maps.of("path", "n/scratch", "value", "mine"), editor);
		run("v/ops/covia/write", Maps.of("path", "w/memory", "value", Maps.of("likes", "tea")), editor);
		run("v/ops/covia/write", Maps.of("path", "w/memory", "value", Maps.of("likes", "coffee")), editor);
		assertEquals(0, open(owner), "its own notes and the shared memory never ask");

		Exception waiting = assertThrows(Exception.class,
			() -> run("v/ops/covia/write", Maps.of("path", "w/notes/plan", "value", "second"), editor));
		assertTrue(String.valueOf(rootMessage(waiting)).contains("Waiting for the owner's approval"), rootMessage(waiting));
		assertEquals("first", String.valueOf(read(owner, "w/notes/plan")), "nothing changed while it waits");
		assertEquals(1, open(owner), "one card");
		answer(client, owner, true);
		run("v/ops/covia/write", Maps.of("path", "w/notes/plan", "value", "second"), editor);
		assertEquals("second", String.valueOf(read(owner, "w/notes/plan")), "approved: the same call goes ahead");
		assertEquals(0, open(owner), "and only the one card was raised");

		assertThrows(Exception.class, () -> run("v/ops/covia/delete", Maps.of("path", "w/notes/plan"), editor));
		answer(client, owner, false);
		Exception declined = assertThrows(Exception.class,
			() -> run("v/ops/covia/delete", Maps.of("path", "w/notes/plan"), editor));
		assertTrue(String.valueOf(rootMessage(declined)).contains("declined"), rootMessage(declined));
		assertEquals("second", String.valueOf(read(owner, "w/notes/plan")), "declined: still there");
		assertEquals(0, open(owner), "and the declined call raised no new card");
	}

	@Test
	void eachTemplatesGrantsReachWhatItsWorkNeeds() throws Exception {
		AString owner = owner("perm-reach");
		assertTrue(session(owner, "builder").withCaps(AgentPermissions.caps(AgentTemplate.SOFTWARE)).ensureAgent());
		assertTrue(session(owner, "finder").withCaps(AgentPermissions.caps(AgentTemplate.RESEARCH)).ensureAgent());
		assertTrue(session(owner, "helper").withCaps(AgentPermissions.caps(AgentTemplate.GENERAL)).ensureAgent());
		assertTrue(session(owner, "other").ensureAgent());

		// Software development: a new file in a file root goes ahead; overwriting it waits for one card.
		RequestContext builder = agent(owner, "builder");
		String name = "perm-reach-" + System.nanoTime() + ".txt";
		run("v/ops/file/write", Maps.of("root", "files", "path", name, "content", "one"), builder);
		assertEquals(0, open(owner), "a new file, no card");
		Exception waiting = assertThrows(Exception.class,
			() -> run("v/ops/file/write", Maps.of("root", "files", "path", name, "content", "two"), builder));
		assertTrue(rootMessage(waiting).contains("Waiting for the owner's approval"), rootMessage(waiting));
		assertEquals(1, open(owner), "one card for the overwrite");
		answer(shared.venue().clientAs(owner.toString()), owner, false);

		// Research: what stops a fetch to this computer is the address policy, not caps.
		Exception fetch = assertThrows(Exception.class,
			() -> run("v/ops/http/get", Maps.of("url", "http://127.0.0.1:9/"), agent(owner, "finder")));
		assertFalse(rootMessage(fetch).contains("Capability denied"), rootMessage(fetch));

		// Personal assistant: it may hand work to another agent.
		ACell asked = run("v/ops/agent/request", Maps.of("agentId", "other", "input", "hello", "wait", 15_000L),
			agent(owner, "helper"));
		assertNotNull(asked);
	}

	// ------------------------------------------------------------------

	private static ChatSession session(AString owner, String id) {
		return new ChatSession(shared.venue().clientAs(owner.toString()), chat(id), "Sam");
	}

	private static AppConfig.Chat chat(String id) {
		return new AppConfig.Chat(id, AppConfig.DEFAULT_OPERATION, AppConfig.ECHO_LLM_OPERATION, "Echo.");
	}

	private static RequestContext agent(AString owner, String id) {
		return engine().context(owner, Principals.agentDID(owner, Strings.create(id)));
	}

	private static ACell run(String op, ACell input, RequestContext ctx) throws Exception {
		return engine().jobs().invokeInternal(op, input, ctx).get(20, TimeUnit.SECONDS);
	}

	private static ACell read(AString owner, String path) throws Exception {
		return RT.getIn(run("v/ops/covia/read", Maps.of("path", path), RequestContext.of(owner)), "value");
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
