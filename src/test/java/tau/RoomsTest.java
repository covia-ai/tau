package tau;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.adapter.token.TokenAdapter;
import covia.exception.AuthException;
import covia.grid.Principals;
import covia.venue.Engine;
import covia.venue.RequestContext;
import tau.chat.ChatSession;

/** Collaboration rooms: who a post is from, waiting for one, and mentions that wake agents within a budget. */
class RoomsTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static final long WAIT = 20;

	@Test
	void aPostIsItsCallersAndATokensPostNamesTheToken() throws Exception {
		AString owner = owner("rooms-author");
		AMap<AString, ACell> mine = post(RequestContext.of(owner), "notes", "the owner's own post");
		assertEquals(owner, mine.get(Strings.create("from")));
		assertNull(mine.get(Strings.create("via")));

		RequestContext viaToken = tokenContext(owner, "grok", TokenPreset.READ_ONLY);
		AMap<AString, ACell> theirs = post(viaToken, "notes", "a post through a token, claiming {from: someone}");
		assertEquals(owner, theirs.get(Strings.create("from")), "a token's post is its issuer's");
		assertEquals(Strings.create("grok"), theirs.get(Strings.create("via")), "and names the token it came through");

		AVector<ACell> read = posts(run("room-read", Maps.of("room", "notes"), viaToken));
		assertEquals(2, read.count());
		assertEquals(1L, ((convex.core.data.prim.CVMLong) RT.getIn(read.get(1), "n")).longValue());

		String blind = new Tokens(engine(), owner).create("blind", new Tokens.Scope(
			convex.core.data.Vectors.of(Strings.create("v/ops/tau/room-post")), convex.core.data.Vectors.empty())).bearer();
		RequestContext none = engine().tokenContext(tokens().tokens().lookup(blind));
		Exception refused = assertThrows(Exception.class, () -> post(none, "notes", "may not read, so may not post"));
		assertInstanceOf(AuthException.class, rootOf(refused));
	}

	@Test
	void waitingAnswersWhenAPostArrives() throws Exception {
		AString owner = owner("rooms-wait");
		RequestContext me = RequestContext.of(owner);
		post(me, "wait", "first");
		CompletableFuture<ACell> waiting = engine().jobs().invokeInternal("v/ops/tau/room-wait",
			Maps.of("room", "wait", "after", 0L, "timeout", 60_000L), me);
		assertFalse(waiting.isDone(), "nothing after post 0 yet");
		post(me, "wait", "second");
		AVector<ACell> arrived = posts(waiting.get(WAIT, TimeUnit.SECONDS));
		assertEquals(Strings.create("second"), RT.getIn(arrived.get(0), "text"));
		AVector<ACell> none = posts(run("room-wait", Maps.of("room", "wait", "after", 1L, "timeout", 0L), me));
		assertEquals(0, none.count(), "a zero timeout answers at once");
	}

	@Test
	void aMentionWakesTheAgentAndItsAnswerIsPostedAsIt() throws Exception {
		AString owner = owner("rooms-wake");
		agent(owner, "helper");
		AMap<AString, ACell> sent = post(RequestContext.of(owner), "work", "@helper please look");
		assertEquals(List.of("helper"), names(sent.get(Strings.create("woke"))));
		rooms().whenIdle().get(WAIT, TimeUnit.SECONDS);
		AVector<ACell> all = posts(run("room-read", Maps.of("room", "work"), RequestContext.of(owner)));
		assertEquals(2, all.count(), "the agent's answer follows: " + all);
		assertEquals(Principals.agentDID(owner, Strings.create("helper")), RT.getIn(all.get(1), "from"));
		assertEquals(0L, ((convex.core.data.prim.CVMLong) RT.getIn(all.get(1), "thread")).longValue(),
			"in the thread the owner's post started");
	}

	@Test
	void agentsMentioningEachOtherStopWhenTheThreadsBudgetIsSpent() throws Exception {
		AString owner = owner("rooms-loop");
		agent(owner, "alpha");
		agent(owner, "beta");
		// Each echoed answer repeats the post it was woken by, mentions and all, so the two
		// would wake each other for ever
		post(RequestContext.of(owner), "loop", "please, @alpha @beta, talk to each other");
		rooms().whenIdle().get(WAIT, TimeUnit.SECONDS);
		AVector<ACell> all = posts(run("room-read", Maps.of("room", "loop", "limit", 200L), RequestContext.of(owner)));
		long byAgents = all.count() - 1;
		assertEquals(Rooms.THREAD_BUDGET, byAgents, "one answer per wake-up the thread could spend: " + all.count());

		// A new post from the owner gives a new thread its own budget, and only that
		post(RequestContext.of(owner), "loop", "once more, @alpha @beta");
		rooms().whenIdle().get(WAIT, TimeUnit.SECONDS);
		AVector<ACell> after = posts(run("room-read", Maps.of("room", "loop", "limit", 200L), RequestContext.of(owner)));
		assertEquals(all.count() + 1 + Rooms.THREAD_BUDGET, after.count());
	}

	@Test
	void anAgentsOwnPostWakesNobody() throws Exception {
		AString owner = owner("rooms-agent");
		agent(owner, "gamma");
		agent(owner, "delta");
		RequestContext gamma = engine().context(owner, Principals.agentDID(owner, Strings.create("gamma")));
		AMap<AString, ACell> sent = post(gamma, "quiet", "@delta wake up");
		assertEquals(List.of("delta"), names(sent.get(Strings.create("notWoken"))),
			"a post an agent makes outside a wake-up starts a thread with nothing to spend");
	}

	@Test
	void aMentionWakesOnlyAnAgentThePosterMayAsk() throws Exception {
		AString owner = owner("rooms-caps");
		agent(owner, "asked");
		RequestContext reader = tokenContext(owner, "reader", TokenPreset.READ_ONLY);
		assertEquals(List.of("asked"), names(post(reader, "caps", "@asked hello").get(Strings.create("notWoken"))),
			"a token that may only read cannot ask an agent");
		RequestContext collaborator = tokenContext(owner, "grok", TokenPreset.COLLABORATE);
		assertEquals(List.of("asked"), names(post(collaborator, "caps", "@asked hello").get(Strings.create("woke"))));
		rooms().whenIdle().get(WAIT, TimeUnit.SECONDS);
		AVector<ACell> all = posts(run("room-read", Maps.of("room", "caps"), collaborator));
		assertEquals(3, all.count(), "the agent answered the collaborator: " + all);
	}

	@Test
	void anAnswersMentionsAskAsTheThreadsPosterNotAsTheAgent() throws Exception {
		AString owner = owner("rooms-narrow");
		agent(owner, "near");
		agent(owner, "far");
		String bearer = new Tokens(engine(), owner).create("narrow", new Tokens.Scope(
			convex.core.data.Vectors.of(Strings.create("v/ops/tau/room-post")),
			convex.core.data.Vectors.of(
				convex.auth.ucan.Capability.create(Strings.create("g/near"), covia.api.Abilities.AGENT_REQUEST),
				convex.auth.ucan.Capability.create(Strings.create("w/"), convex.auth.ucan.Capability.CRUD_READ)))).bearer();
		RequestContext narrow = engine().tokenContext(tokens().tokens().lookup(bearer));
		// near's echoed answer mentions far again, which the token may not ask
		AMap<AString, ACell> sent = post(narrow, "narrow", "hello, @near @far");
		assertEquals(List.of("near"), names(sent.get(Strings.create("woke"))));
		rooms().whenIdle().get(WAIT, TimeUnit.SECONDS);
		AVector<ACell> all = posts(run("room-read", Maps.of("room", "narrow"), RequestContext.of(owner)));
		assertEquals(2, all.count(), "far is never woken in the token's thread: " + all);
	}

	@Test
	void aCollaborateTokenOverMcpSeesTheRoomToolsAndItsMentionWakesAnAgent() throws Exception {
		AString owner = owner("rooms-mcp");
		agent(owner, "mcp-helper");
		post(RequestContext.of(owner), "mcp", "the owner speaks first, @mcp-helper");
		rooms().whenIdle().get(WAIT, TimeUnit.SECONDS);
		String bearer = new Tokens(engine(), owner).create("grok", TokenPreset.COLLABORATE.scope("Tau")).bearer();
		try (java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient()) {
			ACell listed = mcp(http, bearer, Maps.of("jsonrpc", "2.0", "id", 1L, "method", "tools/list"));
			List<String> tools = java.util.stream.StreamSupport.stream(
				RT.ensureVector(RT.getIn(listed, "result", "tools")).spliterator(), false)
				.map(t -> String.valueOf(RT.getIn(t, "name"))).toList();
			assertTrue(tools.stream().filter(t -> t.contains("room")).count() == 3, "the room tools: " + tools);
			String postTool = tools.stream().filter(t -> t.contains("room") && t.contains("post")).findFirst().orElseThrow();
			ACell called = mcp(http, bearer, Maps.of("jsonrpc", "2.0", "id", 2L, "method", "tools/call",
				"params", Maps.of("name", postTool, "arguments", Maps.of("room", "mcp", "text", "and now, @mcp-helper"))));
			ACell result = RT.getIn(called, "result", "structuredContent");
			assertEquals(Strings.create("grok"), RT.getIn(result, "via"), String.valueOf(called));
			assertEquals(List.of("mcp-helper"), names(RT.getIn(result, "woke")), String.valueOf(called));
		}
		rooms().whenIdle().get(WAIT, TimeUnit.SECONDS);
		AVector<ACell> all = posts(run("room-read", Maps.of("room", "mcp"), RequestContext.of(owner)));
		assertEquals(4, all.count(), "the agent answered the owner and then the token, in one session: " + all);
		assertEquals(Principals.agentDID(owner, Strings.create("mcp-helper")), RT.getIn(all.get(3), "from"));
	}

	private static ACell mcp(java.net.http.HttpClient http, String bearer, ACell body) throws Exception {
		var response = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(shared.venue().url() + "mcp"))
			.header("Content-Type", "application/json").header("Accept", "application/json")
			.header("Authorization", "Bearer " + bearer).timeout(java.time.Duration.ofSeconds(WAIT))
			.POST(java.net.http.HttpRequest.BodyPublishers.ofString(convex.core.util.JSON.toString(body))).build(),
			java.net.http.HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), response.body());
		ACell reply = convex.core.util.JSON.parse(response.body());
		assertNull(RT.getIn(reply, "error"), response.body());
		return reply;
	}

	private static RequestContext tokenContext(AString owner, String name, TokenPreset preset) {
		String bearer = new Tokens(engine(), owner).create(name, preset.scope("Tau")).bearer();
		return engine().tokenContext(tokens().tokens().lookup(bearer));
	}

	private static AMap<AString, ACell> post(RequestContext ctx, String room, String text) throws Exception {
		return RT.ensureMap(run("room-post", Maps.of("room", room, "text", text), ctx));
	}

	private static ACell run(String op, ACell input, RequestContext ctx) throws Exception {
		return engine().jobs().invokeInternal("v/ops/tau/" + op, input, ctx).get(WAIT, TimeUnit.SECONDS);
	}

	private static AVector<ACell> posts(ACell page) {
		return RT.ensureVector(RT.getIn(page, "posts"));
	}

	private static List<String> names(ACell vector) {
		AVector<ACell> v = RT.ensureVector(vector);
		return (v == null) ? List.of() : java.util.stream.StreamSupport.stream(v.spliterator(), false)
			.map(ACell::toString).toList();
	}

	private static void agent(AString owner, String id) throws Exception {
		new ChatSession(shared.venue().clientAs(owner.toString()), new AppConfig.Chat(id, AppConfig.DEFAULT_OPERATION,
			AppConfig.ECHO_LLM_OPERATION, "Echo."), id).ensureAgent();
	}

	private static Throwable rootOf(Throwable t) {
		while (t.getCause() != null && t.getCause() != t) t = t.getCause();
		return t;
	}

	private static Rooms rooms() {
		return shared.venue().tau().rooms();
	}

	private static TokenAdapter tokens() {
		return (TokenAdapter) engine().getAdapter(TokenAdapter.NAME);
	}

	private static Engine engine() {
		return shared.venue().engine();
	}

	private static AString owner(String name) {
		AString did = Strings.create(Identity.of(name).userDID(shared.venue().did()));
		engine().getVenueState().users().ensure(did.toString());
		return did;
	}
}
