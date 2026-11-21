package tau;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.token.TokenAdapter;
import covia.exception.AuthException;
import covia.exception.NotFoundException;
import covia.grid.Principals;
import covia.venue.Engine;
import covia.venue.RequestContext;
import tau.chat.ChatSession;

/** Collaboration rooms: rights, public rooms, moderation, paged reads, waiting and telling the owner. */
class RoomsTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static final long WAIT = 20;

	@Test
	void aPlainReadGivesTheLatestPostsAndPagingAndPruningKeepNumbers() throws Exception {
		RequestContext me = RequestContext.of(owner("rooms-paging"));
		run("create", Maps.of("room", "paging"), me);
		for (int i = 0; i < 15; i++) post(me, "paging", "post " + i);

		ACell latest = run("read", Maps.of("room", "paging"), me);
		assertEquals(List.of(5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L), numbers(latest), "the latest ten");
		assertEquals(0L, longAt(latest, "first"));
		assertEquals(15L, longAt(latest, "end"));
		assertEquals(List.of(0L, 1L, 2L), numbers(run("read", Maps.of("room", "paging", "start", 0L, "limit", 3L), me)));

		assertEquals(5L, longAt(run("prune", Maps.of("room", "paging", "before", 5L), me), "pruned"));
		ACell pruned = run("read", Maps.of("room", "paging", "start", 0L, "limit", 3L), me);
		assertEquals(List.of(5L, 6L, 7L), numbers(pruned), "pruned posts are skipped and the rest keep their numbers");
		assertEquals(5L, longAt(pruned, "first"));
		assertEquals(15L, longAt(post(me, "paging", "after the prune"), "n"));
	}

	@Test
	void postingIsAsTheCallerAndEachRightIsItsOwn() throws Exception {
		AString owner = owner("rooms-rights");
		RequestContext me = RequestContext.of(owner);
		run("create", Maps.of("room", "rights"), me);
		AMap<AString, ACell> mine = post(me, "rights", "the owner's own post");
		assertEquals(owner, mine.get(Strings.create("from")));
		assertNull(mine.get(Strings.create("via")));

		RequestContext reader = token(owner, "reader", new Tokens.Scope(Vectors.of(Strings.create("v/ops/room/read")),
			Vectors.of(Capability.create(Strings.create("x/room/rights"), Capability.CRUD_READ))));
		assertEquals(1, posts(run("read", Maps.of("room", "rights"), reader)).count(), "reading takes crud/read");
		assertRefused(AuthException.class, () -> post(reader, "rights", "reading is not posting"));

		RequestContext collaborator = token(owner, "grok", TokenPreset.COLLABORATE.scope("Tau"));
		AMap<AString, ACell> theirs = post(collaborator, "rights", "a post through a token, claiming {from: someone}");
		assertEquals(owner, theirs.get(Strings.create("from")), "a token's post is its issuer's");
		assertEquals(Strings.create("grok"), theirs.get(Strings.create("via")), "and names the token it came through");
		assertRefused(AuthException.class, () -> run("create", Maps.of("room", "grok-made"), collaborator));

		RequestContext blind = token(owner, "blind", TokenPreset.READ_ONLY.scope("Tau"));
		assertRefused(AuthException.class, () -> run("read", Maps.of("room", "rights"), blind));
	}

	@Test
	void aRoomsPublicRightsLetAnyoneReadOrPost() throws Exception {
		AString owner = owner("rooms-public");
		RequestContext me = RequestContext.of(owner);
		RequestContext stranger = token(owner, "stranger", TokenPreset.READ_ONLY.scope("Tau"));
		run("create", Maps.of("room", "open", "public", Vectors.of("read")), me);
		post(me, "open", "for everyone to read");
		assertEquals(1, posts(run("read", Maps.of("room", "open"), stranger)).count());
		assertRefused(AuthException.class, () -> post(stranger, "open", "read is not post"));

		run("update", Maps.of("room", "open", "public", Vectors.of("read", "post")), me);
		post(stranger, "open", "now anyone may post");
		run("update", Maps.of("room", "open", "public", Vectors.empty()), me);
		assertRefused(AuthException.class, () -> run("read", Maps.of("room", "open"), stranger));
	}

	@Test
	void onlyTheOwnerModeratesAndATokenOfTheirsNeedsAdminRoom() throws Exception {
		AString owner = owner("rooms-mod");
		RequestContext me = RequestContext.of(owner);
		run("create", Maps.of("room", "mod"), me);
		RequestContext other = RequestContext.of(owner("rooms-mod-other"));
		post(other, "mod", "anyone in person may post in a venue room");
		assertRefused(AuthException.class, () -> run("prune", Maps.of("room", "mod", "before", 1L), other));
		RequestContext collaborator = token(owner, "helper", TokenPreset.COLLABORATE.scope("Tau"));
		assertRefused(AuthException.class, () -> run("delete", Maps.of("room", "mod"), collaborator));
		RequestContext moderator = token(owner, "mod", new Tokens.Scope(Vectors.of(Strings.create("v/ops/room/delete")),
			Vectors.of(Capability.create(Strings.create("x/room"), RoomAdapter.ADMIN_ROOM))));
		run("delete", Maps.of("room", "mod"), moderator);
		assertRefused(NotFoundException.class, () -> run("read", Maps.of("room", "mod"), me));
	}

	@Test
	void waitingAnswersWhenAPostArrivesAndWhenTheRoomGoes() throws Exception {
		RequestContext me = RequestContext.of(owner("rooms-wait"));
		run("create", Maps.of("room", "wait"), me);
		long first = longAt(post(me, "wait", "first"), "n");
		CompletableFuture<ACell> waiting = start("wait", Maps.of("room", "wait", "after", first, "timeout", 60_000L), me);
		assertFalse(waiting.isDone(), "nothing after the first post yet");
		post(me, "wait", "second");
		assertEquals(List.of(first + 1), numbers(waiting.get(WAIT, TimeUnit.SECONDS)));
		assertEquals(List.of(), numbers(run("wait", Maps.of("room", "wait", "after", first + 1, "timeout", 0L), me)),
			"a zero timeout answers at once");

		CompletableFuture<ACell> orphaned = start("wait", Maps.of("room", "wait", "after", first + 1), me);
		run("delete", Maps.of("room", "wait"), me);
		ExecutionFailure.assertCause(NotFoundException.class, orphaned);
	}

	@Test
	void postsTheOwnerDidNotMakeAreToldToTheirChosenSession() throws Exception {
		AString owner = owner("rooms-notify");
		RequestContext me = RequestContext.of(owner);
		new ChatSession(shared.venue().clientAs(owner.toString()), new AppConfig.Chat("watcher", AppConfig.DEFAULT_OPERATION,
			AppConfig.ECHO_LLM_OPERATION, "Echo."), "watcher").ensureAgent();
		ACell made = run("create", Maps.of("room", "told", "notify", Maps.of("agentId", "watcher")), me);
		assertNotNull(RT.getIn(made, "notify", "sessionId"), "the subscriber's notice starts the session posts go to");
		post(me, "told", "the owner's own post tells nobody");
		RequestContext watcher = engine().context(owner, Principals.agentDID(owner, Strings.create("watcher")));
		post(watcher, "told", "nor does the told agent's own answer, so it never wakes itself");
		AString stranger = owner("rooms-notify-other");
		post(RequestContext.of(stranger), "told", "someone else's post");
		post(token(owner, "grok", TokenPreset.COLLABORATE.scope("Tau")), "told", "a post through the owner's token");

		engine().jobs().invokeInternal("v/ops/agent/trigger", Maps.of("agentId", "watcher", "force", false, "wait", 15_000L),
			me).get(WAIT, TimeUnit.SECONDS);
		ACell history = engine().jobs().invokeInternal("v/ops/agent/history", Maps.of("agentId", "watcher",
			"sessionId", RT.getIn(made, "notify", "sessionId")), me).get(WAIT, TimeUnit.SECONDS);
		List<ACell> told = StreamSupport.stream(RT.ensureVector(RT.getIn(history, "entries")).spliterator(), false)
			.filter(e -> "user".equals(String.valueOf(RT.getIn(e, "role")))).toList();
		List<String> texts = told.stream().map(e -> String.valueOf(RT.getIn(e, "content"))).toList();
		assertEquals(3, told.size(), "the notice, then the two posts the owner and the agent did not make: " + texts);
		assertEquals("someone else's post", texts.get(1), "a post arrives as its text alone");
		AString venue = engine().getDIDString();
		assertEquals(venue, RT.getIn(told.get(1), "caller"), "the venue tells the subscriber, not the poster");
		assertEquals(venue, RT.getIn(told.get(2), "caller"), "even of a post through the owner's own token");

		RequestContext creator = token(owner, "creator", new Tokens.Scope(Vectors.of(Strings.create("v/ops/room/create")),
			Vectors.of(Capability.create(Strings.create("x/room"), RoomAdapter.ROOM_CREATE))));
		run("create", Maps.of("room", "made-by-token"), creator);
		assertRefused(AuthException.class, () -> run("create", Maps.of("room", "told-by-token",
			"notify", Maps.of("agentId", "watcher")), creator));
	}

	@Test
	void aCollaborateTokenOverMcpSeesTheRoomToolsAndPostsAsItsIssuer() throws Exception {
		AString owner = owner("rooms-mcp");
		run("create", Maps.of("room", "mcp"), RequestContext.of(owner));
		String bearer = new Tokens(engine(), owner).create("grok", TokenPreset.COLLABORATE.scope("Tau")).bearer();
		try (HttpClient http = HttpClient.newHttpClient()) {
			ACell listed = mcp(http, bearer, Maps.of("jsonrpc", "2.0", "id", 1L, "method", "tools/list"));
			List<String> tools = StreamSupport.stream(RT.ensureVector(RT.getIn(listed, "result", "tools")).spliterator(), false)
				.map(t -> String.valueOf(RT.getIn(t, "name"))).toList();
			assertTrue(tools.containsAll(List.of("room_read", "room_wait", "room_post")), "the room tools: " + tools);
			ACell called = mcp(http, bearer, Maps.of("jsonrpc", "2.0", "id", 2L, "method", "tools/call",
				"params", Maps.of("name", "room_post", "arguments", Maps.of("room", "mcp", "text", "hello from outside"))));
			ACell result = RT.getIn(called, "result", "structuredContent");
			assertEquals(Strings.create("grok"), RT.getIn(result, "via"), String.valueOf(called));
			assertEquals(owner, RT.getIn(result, "from"), String.valueOf(called));
		}
	}

	@Test
	void aNameIsTakenOnceAndAnOwnersGoingSettlesTheirRooms() throws Exception {
		AString owner = owner("rooms-going");
		RequestContext me = RequestContext.of(owner);
		run("create", Maps.of("room", "taken"), me);
		RequestContext other = RequestContext.of(owner("rooms-going-other"));
		assertRefused(covia.exception.InvalidInputException.class, () -> run("create", Maps.of("room", "taken"), other));

		new ChatSession(shared.venue().clientAs(owner.toString()), new AppConfig.Chat("leaver", AppConfig.DEFAULT_OPERATION,
			AppConfig.ECHO_LLM_OPERATION, "Echo."), "leaver").ensureAgent();
		RequestContext leaver = engine().context(owner, Principals.agentDID(owner, Strings.create("leaver")));
		run("create", Maps.of("room", "left-behind"), leaver);
		engine().jobs().invokeInternal("v/ops/agent/delete", Maps.of("agentId", "leaver", "remove", true), me)
			.get(WAIT, TimeUnit.SECONDS);
		assertEquals(owner, view(me, "left-behind/owner"), "an agent's rooms pass to its user");

		engine().deleteUser(RequestContext.of(Strings.create(engine().getDIDString())), owner);
		assertRefused(NotFoundException.class, () -> run("read", Maps.of("room", "taken"), other));
		assertRefused(NotFoundException.class, () -> run("read", Maps.of("room", "left-behind"), other));
	}

	@Test
	void anAgentPostsAsItselfAndOwnsWhatItCreates() throws Exception {
		AString owner = owner("rooms-agent");
		new ChatSession(shared.venue().clientAs(owner.toString()), new AppConfig.Chat("maker", AppConfig.DEFAULT_OPERATION,
			AppConfig.ECHO_LLM_OPERATION, "Echo."), "maker").ensureAgent();
		AString agentDID = Principals.agentDID(owner, Strings.create("maker"));
		RequestContext agent = engine().context(owner, agentDID);
		assertEquals(agentDID, RT.getIn(run("create", Maps.of("room", "agents"), agent), "owner"));
		assertEquals(agentDID, post(agent, "agents", "an agent's post").get(Strings.create("from")));
		assertRefused(AuthException.class, () -> run("prune", Maps.of("room", "agents", "before", 1L), RequestContext.of(owner)));
	}

	/** A failed future's root cause, checked. */
	private static final class ExecutionFailure {
		static void assertCause(Class<? extends Throwable> expected, CompletableFuture<ACell> f) {
			Exception e = assertThrows(Exception.class, () -> f.get(WAIT, TimeUnit.SECONDS));
			assertInstanceOf(expected, rootOf(e), String.valueOf(e));
		}
	}

	private static void assertRefused(Class<? extends Throwable> expected, org.junit.jupiter.api.function.Executable call) {
		Exception e = assertThrows(Exception.class, call);
		assertInstanceOf(expected, rootOf(e), String.valueOf(e));
	}

	private static ACell view(RequestContext ctx, String path) throws Exception {
		return RT.getIn(engine().jobs().invokeInternal("v/ops/covia/read", Maps.of("path", "x/room/" + path), ctx)
			.get(WAIT, TimeUnit.SECONDS), "value");
	}

	private static ACell mcp(HttpClient http, String bearer, ACell body) throws Exception {
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(shared.venue().url() + "mcp"))
			.header("Content-Type", "application/json").header("Accept", "application/json")
			.header("Authorization", "Bearer " + bearer).timeout(Duration.ofSeconds(WAIT))
			.POST(HttpRequest.BodyPublishers.ofString(JSON.toString(body))).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), response.body());
		ACell reply = JSON.parse(response.body());
		assertNull(RT.getIn(reply, "error"), response.body());
		return reply;
	}

	private static AMap<AString, ACell> post(RequestContext ctx, String room, String text) throws Exception {
		return RT.ensureMap(run("post", Maps.of("room", room, "text", text), ctx));
	}

	private static ACell run(String op, ACell input, RequestContext ctx) throws Exception {
		return start(op, input, ctx).get(WAIT, TimeUnit.SECONDS);
	}

	private static CompletableFuture<ACell> start(String op, ACell input, RequestContext ctx) {
		return engine().jobs().invokeInternal("v/ops/room/" + op, input, ctx);
	}

	private static AVector<ACell> posts(ACell page) {
		return RT.ensureVector(RT.getIn(page, "posts"));
	}

	private static List<Long> numbers(ACell page) {
		return StreamSupport.stream(posts(page).spliterator(), false)
			.map(p -> ((CVMLong) RT.getIn(p, "n")).longValue()).toList();
	}

	private static long longAt(ACell map, String key) {
		return ((CVMLong) RT.getIn(map, key)).longValue();
	}

	private static RequestContext token(AString owner, String name, Tokens.Scope scope) {
		String bearer = new Tokens(engine(), owner).create(name, scope).bearer();
		return engine().tokenContext(tokens().tokens().lookup(bearer));
	}

	private static Throwable rootOf(Throwable t) {
		while (t.getCause() != null && t.getCause() != t) t = t.getCause();
		return t;
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
