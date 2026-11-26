package tau;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.ALatticeComponent;
import covia.adapter.MCPAdapter;
import covia.adapter.token.AccessToken;
import covia.adapter.token.TokenAdapter;
import covia.adapter.token.TokenAdapter.Issued;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * The owner's named access tokens, worked on in-process as the owner in
 * person: Covia's {@link TokenAdapter} called directly, so a key never passes
 * through an operation's result, a job record or the model. Each token here is
 * accepted at the venue's MCP endpoint alone, and lasts for the lifetime it
 * was made with, or until revoked when it was made with none.
 *
 * <p>A named token is not a login: its requests are the owner's, but the agent
 * sees the token named on each turn's From line, and it can do only what its
 * tools and caps allow. The venue keeps each key sealed under its secret store
 * ({@code adapters.token.keepRawTokens}, on by default), so {@link #key} reads
 * it again. Where that is turned off, the key {@link #create} or
 * {@link #newKey} returns is the only copy.</p>
 */
public final class Tokens {

	/** What a token offers at the MCP endpoint: its tools, and the caps its requests act within. */
	public record Scope(AVector<ACell> tools, ACell caps) {
		public Scope {
			Objects.requireNonNull(tools, "tools");
			Objects.requireNonNull(caps, "caps");
		}
	}

	private final Engine engine;
	private final AString owner;

	public Tokens(Engine engine, AString owner) {
		this.engine = Objects.requireNonNull(engine, "engine");
		this.owner = Objects.requireNonNull(owner, "owner");
	}

	/** The owner's tokens, from every access point; none carries its key. */
	public List<AccessToken> list() {
		return adapter().list(inPerson(), token -> true);
	}

	/** How long a named token lasts unless the owner chooses otherwise: 30 days. */
	public static final long DEFAULT_LIFETIME_SECONDS = 30L * 24 * 3600;

	/** Makes token {@code name} with {@code scope}, lasting until revoked, and returns it with its key. */
	public Issued create(String name, Scope scope) {
		return create(name, scope, null);
	}

	/**
	 * Makes token {@code name} with {@code scope}, lasting {@code lifetimeSeconds}
	 * from now, or until revoked when that is null, and returns it with its key.
	 */
	public Issued create(String name, Scope scope, Long lifetimeSeconds) {
		if (lifetimeSeconds != null && lifetimeSeconds <= 0) throw new IllegalArgumentException("A token's lifetime must be positive");
		ACell expires = (lifetimeSeconds == null) ? null
			: CVMLong.create(System.currentTimeMillis() + lifetimeSeconds * 1000);
		return adapter().create(inPerson(), Strings.create(name), access(scope), scope.caps(), expires,
			adapter().administration());
	}

	/** Token {@code name}'s key, as the venue keeps it. */
	public String key(String name) {
		return adapter().bearer(inPerson(), Strings.create(name), adapter().administration()).toString();
	}

	/**
	 * Gives token {@code name} a new key by making it again: the same name,
	 * tools, caps and lifetime, starting now (one made to last until revoked
	 * still does), expired or not. The old key stops working, and so does work
	 * it started, as for a revoked token. The venue keeps a token's making time
	 * however it is changed, so making it afresh is what keeps its lifetime the
	 * same each time. It is two steps, revoking then making: should the making
	 * fail, the token is gone. Only a token the owner made, accepted at the MCP
	 * endpoint alone, as this class makes them, can be made again here; one an
	 * agent made would come back standing for the owner instead.
	 *
	 * @throws IllegalStateException if another made the token, or it is accepted beyond the MCP endpoint
	 */
	public Issued newKey(String name) {
		AccessToken token = adapter().tokens().token(owner, Strings.create(name));
		Scope scope = (token.record() == null) ? null : scope(token);
		if (token.record() != null && !owner.equals(token.issuer())) {
			throw new IllegalStateException("\"" + name + "\" was made by one of your agents, so a new key here would "
				+ "make it yours: revoke it, and have the agent make a new one");
		}
		if (token.record() != null && scope == null) {
			throw new IllegalStateException("\"" + name + "\" is accepted beyond the MCP endpoint, so it can't be made again here");
		}
		Long lifetime = (scope == null) ? null : lifetimeSeconds(token.expires(), created(token));
		revoke(name); // refuses a token that does not exist
		return create(name, scope, lifetime);
	}

	/** When {@code token} was made, in milliseconds since the epoch. */
	public static long created(AccessToken token) {
		return RT.ensureLong(token.record().get(Fields.CREATED)).longValue();
	}

	/**
	 * The lifetime a token was given, in whole seconds, from when it
	 * {@code expires} and was {@code created} (milliseconds); null for one that
	 * lasts until revoked. The venue stamps the making a moment after the
	 * expiry is set, so the difference is rounded.
	 */
	public static Long lifetimeSeconds(Long expires, long created) {
		return (expires == null) ? null : Math.round((expires - created) / 1000.0);
	}

	/** Revokes token {@code name}: its key stops working, on work it already started too. */
	public void revoke(String name) {
		adapter().delete(inPerson(), Strings.create(name), adapter().administration());
	}

	/** The venue's token store, for a view that follows it ({@code Watcher}). */
	public ALatticeComponent<?> store() {
		return adapter().tokens();
	}

	/** What {@code token} offers at the MCP endpoint, or null when it is accepted elsewhere too, or not at MCP. */
	public static Scope scope(AccessToken token) {
		if (!Set.of(MCPAdapter.ACCESS_POINT).equals(token.accessPoints())) return null;
		AVector<ACell> tools = RT.ensureVector(RT.getIn(token.access(), MCPAdapter.ACCESS_POINT, Fields.TOOLS));
		return new Scope((tools == null) ? Vectors.empty() : tools, TokenAdapter.shownCaps(token));
	}

	/** The MCP entry for {@code scope}: the token is accepted there alone, offering its tools. */
	static ACell access(Scope scope) {
		return Maps.of(MCPAdapter.ACCESS_POINT, Maps.of(Fields.TOOLS, scope.tools()));
	}

	/**
	 * The owner in person, as the app vouches for them: making a token, giving
	 * it a new key and reading its key each need that, because a key a user's
	 * token carries stands for the user.
	 */
	private RequestContext inPerson() {
		return RequestContext.of(owner);
	}

	private TokenAdapter adapter() {
		return (TokenAdapter) engine.getAdapter(TokenAdapter.NAME);
	}
}
