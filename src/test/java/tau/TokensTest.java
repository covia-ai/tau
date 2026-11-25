package tau;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import tau.chat.ChatSession;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.MCPAdapter;
import covia.adapter.token.AccessToken;
import covia.adapter.token.AccessTokens;
import covia.adapter.token.TokenAdapter;
import covia.adapter.token.TokenAdapter.Issued;
import covia.api.Fields;
import covia.exception.NotFoundException;
import covia.grid.Principals;
import covia.venue.Engine;
import covia.venue.RequestContext;

/** The owner's named tokens, worked on in-process against a real venue that keeps keys. */
class TokensTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@Test
	void aTokenIsTheOwnersAndItsKeyIsKeptRotatedAndRevoked() {
		AString owner = owner("tokens-lifecycle");
		Tokens tokens = new Tokens(engine(), owner);
		Issued issued = tokens.create("claude-code", TokenPreset.READ_ONLY.scope("Tau"));
		AccessToken token = issued.token();
		assertEquals(owner, token.issuer(), "a token the owner makes is issued as the owner");
		assertEquals(java.util.Set.of(MCPAdapter.ACCESS_POINT), token.accessPoints(), "accepted at MCP alone");
		assertNull(token.expires(), "a named token lasts until revoked");
		assertEquals(List.of("claude-code"), names(tokens.list()));
		assertEquals(issued.bearer(), tokens.key("claude-code"), "the venue keeps the key to show again");
		assertEquals(owner, resolves(issued.bearer()));

		Issued rotated = tokens.newKey("claude-code");
		assertNotEquals(issued.bearer(), rotated.bearer());
		assertEquals(rotated.bearer(), tokens.key("claude-code"));
		assertNull(store().lookup(issued.bearer()), "a new key retires the old one");
		assertEquals(owner, resolves(rotated.bearer()));

		tokens.revoke("claude-code");
		assertTrue(tokens.list().isEmpty());
		assertNull(store().lookup(rotated.bearer()), "a revoked token's key stops working");
		assertThrows(NotFoundException.class, () -> tokens.key("claude-code"));
	}

	@Test
	void aTokenGivenALifetimeExpiresThenAndStillWorksBefore() {
		AString owner = owner("tokens-lifetime");
		Tokens tokens = new Tokens(engine(), owner);
		long before = System.currentTimeMillis();
		Issued issued = tokens.create("thirty-days", TokenPreset.READ_ONLY.scope("Tau"), Tokens.DEFAULT_LIFETIME_SECONDS);
		long after = System.currentTimeMillis();
		Long expires = issued.token().expires();
		assertNotNull(expires, "a lifetime sets an expiry");
		assertTrue(expires >= before + Tokens.DEFAULT_LIFETIME_SECONDS * 1000
			&& expires <= after + Tokens.DEFAULT_LIFETIME_SECONDS * 1000, "thirty days from when it was made");
		var reference = store().lookup(issued.bearer());
		assertNotNull(store().resolve(reference, expires - 1), "it works until then");
		assertNull(store().resolve(reference, expires), "and stops at its expiry");
		var row = new tau.ui.settings.AccessPanel.Row("thirty-days", "", 0, "you", false, expires);
		assertFalse(row.expired(expires - 1));
		assertTrue(row.expired(expires), "the list calls it expired when the venue stops its key");
		assertThrows(IllegalArgumentException.class,
			() -> tokens.create("never-lives", TokenPreset.READ_ONLY.scope("Tau"), 0L));
		tokens.revoke("thirty-days");
	}

	@Test
	void aNewKeyStartsTheTokensLifetimeAgainExpiredOrNot() {
		AString owner = owner("tokens-renew");
		Tokens tokens = new Tokens(engine(), owner);
		long day = 24L * 3600 * 1000, week = 7 * day;
		Tokens.Scope scope = TokenPreset.COLLABORATE.scope("Tau");
		var started = store().lookup(tokens.create("weekly", scope, week / 1000).bearer());
		assertAWeekFromNow(tokens, "weekly");
		assertNull(store().resolve(started, now()), "work the old key started stops, as for a revoked token");

		// Made eight days ago, as the store would hold it a day after it expired.
		long then = System.currentTimeMillis() - 8 * day;
		store().update(owner, Strings.create("weekly"), record -> record
			.assoc(Fields.CREATED, CVMLong.create(then)).assoc(Fields.EXPIRES, CVMLong.create(then + week)));
		Issued renewed = assertAWeekFromNow(tokens, "weekly");
		assertNotNull(store().resolve(store().lookup(renewed.bearer()), now()), "its new key works");
		assertEquals(scope, Tokens.scope(renewed.token()), "with the tools and caps it had");
		assertAWeekFromNow(tokens, "weekly"); // and a week again, however often

		tokens.create("standing", scope);
		assertNull(tokens.newKey("standing").token().expires(), "one made until revoked stays so");
	}

	@Test
	void aTokenAcceptedBeyondMcpIsNotMadeAgain() {
		AString owner = owner("tokens-beyond");
		Tokens tokens = new Tokens(engine(), owner);
		TokenAdapter adapter = (TokenAdapter) engine().getAdapter(TokenAdapter.NAME);
		var access = Maps.of(MCPAdapter.ACCESS_POINT, Maps.of(Fields.TOOLS, Vectors.empty()), "test", Maps.empty());
		String bearer = adapter.create(RequestContext.of(owner), Strings.create("wide"), access, null, null,
			adapter.administration()).bearer();
		assertThrows(IllegalStateException.class, () -> tokens.newKey("wide"));
		assertEquals(bearer, tokens.key("wide"), "left as it was");
		assertThrows(NotFoundException.class, () -> tokens.newKey("never-made"));
	}

	@Test
	void aTokenAnAgentMadeIsNotMadeAgainAsTheOwners() throws Exception {
		AString owner = owner("tokens-agent-made");
		new ChatSession(shared.venue().clientAs(owner.toString()), new AppConfig.Chat("maker", AppConfig.DEFAULT_OPERATION,
			AppConfig.ECHO_LLM_OPERATION, "You are a test assistant."), "Maker").ensureAgent();
		RequestContext agent = engine().context(owner, Principals.agentDID(owner, Strings.create("maker")));
		TokenAdapter adapter = (TokenAdapter) engine().getAdapter(TokenAdapter.NAME);
		String bearer = adapter.create(agent, Strings.create("agents"), Tokens.access(TokenPreset.READ_ONLY.scope("maker")),
			null, null, adapter.administration()).bearer();
		Tokens tokens = new Tokens(engine(), owner);
		assertThrows(IllegalStateException.class, () -> tokens.newKey("agents"));
		assertNotNull(store().lookup(bearer), "left as the agent made it");
	}

	/** Gives token {@code name} a new key, and checks it now lasts a week from then. */
	private static Issued assertAWeekFromNow(Tokens tokens, String name) {
		long week = 7L * 24 * 3600 * 1000;
		long before = System.currentTimeMillis();
		Issued issued = tokens.newKey(name);
		long after = System.currentTimeMillis();
		Long expires = issued.token().expires();
		assertTrue(expires != null && expires >= before + week && expires <= after + week,
			() -> name + " lasts a week from its new key, not until " + expires);
		return issued;
	}

	@Test
	void everyPresetMakesATokenWithinTheOwnersAuthority() {
		AString owner = owner("tokens-presets");
		Tokens tokens = new Tokens(engine(), owner);
		for (TokenPreset preset : TokenPreset.values()) {
			String name = preset.name().toLowerCase().replace('_', '-');
			Tokens.Scope scope = preset.scope("Tau");
			AccessToken token = tokens.create(name, scope).token();
			assertEquals(scope, Tokens.scope(token), preset + " reads back as it was made");
			assertEquals(preset, TokenPreset.matching(Tokens.scope(token), "Tau"), "the list names its preset");
		}
		assertEquals(TokenPreset.values().length, tokens.list().size());
	}

	@Test
	void theAssistantRevokesTheOwnersTokenThroughTheMcpTokenOperation() throws Exception {
		AString owner = owner("tokens-assistant");
		new ChatSession(shared.venue().clientAs(owner.toString()), new AppConfig.Chat("helper", AppConfig.DEFAULT_OPERATION,
			AppConfig.ECHO_LLM_OPERATION, "You are a test assistant."), "Helper").ensureAgent();
		Tokens tokens = new Tokens(engine(), owner);
		String bearer = tokens.create("tool", TokenPreset.READ_ONLY.scope("helper")).bearer();
		RequestContext assistant = engine().context(owner, Principals.agentDID(owner, Strings.create("helper")));
		engine().jobs().invokeInternal("v/ops/mcp/delete-token", Maps.of(Fields.NAME, "tool"), assistant)
			.get(5, TimeUnit.SECONDS);
		assertTrue(tokens.list().isEmpty(), "the skill's revoke deletes the owner's token");
		assertNull(store().lookup(bearer));
	}

	@Test
	void aCustomScopeMatchesNoPreset() {
		Tokens tokens = new Tokens(engine(), owner("tokens-custom"));
		Tokens.Scope custom = new Tokens.Scope(Vectors.of(Strings.create("v/ops/covia/read")), Strings.create("unrestricted"));
		AccessToken token = tokens.create("custom", custom).token();
		assertEquals(custom.tools(), Tokens.scope(token).tools());
		assertNull(TokenPreset.matching(Tokens.scope(token), "Tau"));
		assertNull(TokenPreset.matching(TokenPreset.ASK_ASSISTANT.scope("Tau"), "Another"),
			"asking the assistant names the chat agent");
	}

	/** The namespace a key resolves to, or null when no token stands for it. */
	private static AString resolves(String bearer) {
		var reference = store().lookup(bearer);
		return (reference == null) ? null : RT.ensureString(reference.get(Fields.OWNER));
	}

	private static List<String> names(List<AccessToken> tokens) {
		return tokens.stream().map(t -> t.name().toString()).toList();
	}

	private static AccessTokens store() {
		return ((TokenAdapter) engine().getAdapter(TokenAdapter.NAME)).tokens();
	}

	/** The venue's clock, which judges expiry. */
	private static long now() {
		return engine().currentTimestamp().longValue();
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
