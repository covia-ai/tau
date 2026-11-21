package tau;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Blobs;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.ANumeric;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;
import covia.adapter.AAdapter;
import covia.adapter.AgentAdapter;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.grid.Principals;
import covia.lattice.Covia;
import covia.venue.AdapterState;
import covia.venue.RequestContext;
import covia.venue.RouteText;

/**
 * Collaboration rooms: conversations shared across the venue, where people,
 * outside agents reaching Tau over MCP and the venue's own agents talk
 * (docs/ROOMS.md).
 *
 * <p>Every room is in this adapter's own state, at {@code rooms/<name>}, and
 * shown read-only at {@code x/room/<name>}. A room has an owner, who moderates
 * it; posts kept in an {@link Index} under their number as an eight-byte
 * big-endian key, so key order is post order, and a read or a prune is a
 * {@code seek} and a {@code slice}; the rights it grants anyone; and an
 * optional agent session told of each post its owner did not make.</p>
 *
 * <p>Rights are capabilities on {@code x/room/<name>}: {@code crud/read} to
 * read and wait, {@code room/post} to post, {@code room/create} to create, and
 * {@code room} for all three. Moderating takes being the owner, with a request
 * that carries {@code admin/room}. A room's public rights let anyone read, or
 * post, through its operations. Nothing is pushed to anyone but the owner's
 * chosen session: others follow a room by waiting on it.</p>
 */
public class RoomAdapter extends AAdapter {

	private static final Logger log = LoggerFactory.getLogger(RoomAdapter.class);

	public static final String NAME = "room";

	/** Posting in a room. */
	public static final AString ROOM_POST = Strings.intern("room/post");
	/** Creating a room. */
	public static final AString ROOM_CREATE = Strings.intern("room/create");
	/** Every room action: reading through the operations, posting and creating. */
	public static final AString ROOM = Strings.intern("room");
	/** Moderating a room the caller owns: changing it, pruning it, deleting it. */
	public static final AString ADMIN_ROOM = Strings.intern("admin/room");

	/** The public right to read a room through its operations. */
	public static final String PUBLIC_READ = "read";
	/** The public right to post in a room. */
	public static final String PUBLIC_POST = "post";

	static final int DEFAULT_LIMIT = 10;
	static final int MAX_LIMIT = 200;
	static final int MAX_TEXT = 8000;
	static final int MAX_DESCRIPTION = 1000;
	static final long DEFAULT_WAIT_MS = 300_000;
	static final long MAX_WAIT_MS = 86_400_000;

	static final String NOT_FOUND = "No room named %s";
	static final String EXISTS = "There is already a room named %s";
	static final String NOT_OWNER = "Only the owner of room %s moderates it";
	static final String NO_ADMIN = "Moderating room %s takes admin/room, which this request does not carry";
	static final String MAY_NOT = "This request may not %s room %s";
	static final String DELETED = "Room %s was deleted";

	private static final Pattern ROOM_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
	private static final String OP_MESSAGE = "v/ops/agent/message";
	private static final String AGENT_ADAPTER = "agent";
	private static final AString ROUTE_ADAPTER = Strings.intern(NAME);
	private static final String NOTICE = "Posts others make in room %s will be delivered to this session.";
	private static final List<String> OPS = List.of("create", "post", "read", "wait", "update", "prune", "delete");

	private static final AString K_ROOMS = Strings.intern("rooms");
	private static final AString K_ROOM = Strings.intern("room");
	private static final AString K_OWNER = Strings.intern("owner");
	private static final AString K_CREATED = Strings.intern("created");
	private static final AString K_DESCRIPTION = Strings.intern("description");
	private static final AString K_PUBLIC = Strings.intern("public");
	private static final AString K_NOTIFY = Strings.intern("notify");
	private static final AString K_NEXT = Strings.intern("next");
	private static final AString K_POSTS = Strings.intern("posts");
	private static final AString K_TEXT = Strings.intern("text");
	private static final AString K_START = Strings.intern("start");
	private static final AString K_LIMIT = Strings.intern("limit");
	private static final AString K_AFTER = Strings.intern("after");
	private static final AString K_BEFORE = Strings.intern("before");
	private static final AString K_TIMEOUT = Strings.intern("timeout");
	private static final AString K_N = Strings.intern("n");
	private static final AString K_TS = Strings.intern("ts");
	private static final AString K_FROM = Strings.intern("from");
	private static final AString K_VIA = Strings.intern("via");
	private static final AString K_FIRST = Strings.intern("first");
	private static final AString K_END = Strings.intern("end");
	private static final AString K_PRUNED = Strings.intern("pruned");
	private static final AString K_DELETED = Strings.intern("deleted");
	/** A room's settings, which an update changes and nothing else. */
	private static final List<AString> SETTINGS = List.of(Strings.intern("description"), Strings.intern("public"),
		Strings.intern("notify"));

	/** Requests waiting for a room's next post, by room. */
	private final Map<AString, List<CompletableFuture<Boolean>>> waiting = new HashMap<>();

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public String getDescription() {
		return "Collaboration rooms shared across the venue: create a room, post in it, read it, and wait for "
			+ "its next post. A room's owner moderates it.";
	}

	@Override
	protected void installAssets() {
		for (String op : OPS) installAsset(NAME + "/" + op, "/adapters/room/" + op + ".json");
	}

	/** Every room, read-only, whoever's namespace the path is addressed in: rooms are the venue's. */
	@Override
	public AForkableCursor<ACell> latticeView(AString owner, RequestContext ctx) {
		return Covia.entry(adapterState().cursor(), K_ROOMS);
	}

	/** A user's going takes the rooms they or their agents own. */
	@Override
	public void deleteUserState(AString did, AdapterState state) {
		state.update(s -> withRooms(s, rooms(s).filterValues(r -> !did.equals(userOf(owner(r))))));
	}

	/** An agent's going hands the rooms it owns to its user, so each still has a moderator. */
	@Override
	public void deleteAgentState(AString agentDID, AdapterState state) {
		AString user = Principals.userOf(agentDID);
		state.update(s -> {
			Index<AString, ACell> rooms = rooms(s);
			for (var e : rooms.entrySet()) {
				if (agentDID.equals(owner(e.getValue()))) {
					rooms = rooms.assoc(e.getKey(), RT.ensureMap(e.getValue()).assoc(K_OWNER, user).dissoc(K_NOTIFY));
				}
			}
			return withRooms(s, rooms);
		});
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		try {
			return switch (getSubOperation(meta)) {
				case "create" -> create(ctx, input);
				case "post" -> post(ctx, input);
				case "read" -> CompletableFuture.completedFuture(read(ctx, input));
				case "wait" -> await(ctx, input);
				case "update" -> update(ctx, input);
				case "prune" -> CompletableFuture.completedFuture(prune(ctx, input));
				case "delete" -> CompletableFuture.completedFuture(delete(ctx, input));
				default -> throw new InvalidInputException("Unknown room operation: " + getSubOperation(meta));
			};
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	// ========== Operations ==========

	private CompletableFuture<ACell> create(RequestContext ctx, ACell input) {
		AString name = roomName(input);
		requireRight(ctx, name, ROOM_CREATE, "create");
		if (rooms(adapterState().get()).get(name) != null) throw new InvalidInputException(EXISTS.formatted(name));
		AString owner = ctx.getAuthorityDID();
		AMap<AString, ACell> record = withSettings(ctx, Maps.of(K_OWNER, owner, K_CREATED, engine.currentTimestamp(),
			K_NEXT, 0L, K_POSTS, Index.none()), input);
		return withSession(ctx, name, record).thenApply(made -> {
			adapterState().update(s -> {
				Index<AString, ACell> rooms = rooms(s);
				if (rooms.get(name) != null) throw new InvalidInputException(EXISTS.formatted(name));
				return withRooms(s, rooms.assoc(name, made));
			});
			return (ACell) summary(name, made);
		});
	}

	/** Records the post, answers waits, and tells the owner's chosen session, if any. */
	private CompletableFuture<ACell> post(RequestContext ctx, ACell input) {
		AString name = roomName(input);
		String text = text(input, K_TEXT, MAX_TEXT, true);
		AMap<AString, ACell> room = room(name);
		if (!publicly(room, PUBLIC_POST)) requireRight(ctx, name, ROOM_POST, "post in");
		AString from = ctx.getAuthorityDID();
		AString via = via(ctx);
		AtomicReference<AMap<AString, ACell>> made = new AtomicReference<>();
		AtomicReference<AMap<AString, ACell>> after = new AtomicReference<>();
		adapterState().update(s -> {
			Index<AString, ACell> rooms = rooms(s);
			AMap<AString, ACell> r = RT.ensureMap(rooms.get(name));
			if (r == null) throw new NotFoundException(NOT_FOUND.formatted(name), name.toString());
			long n = longIn(r, K_NEXT, 0);
			AMap<AString, ACell> p = Maps.of(K_N, n, K_TS, engine.currentTimestamp(), K_FROM, from,
				K_TEXT, Strings.create(text));
			if (via != null) p = p.assoc(K_VIA, via);
			AMap<AString, ACell> updated = r.assoc(K_NEXT, CVMLong.create(n + 1))
				.assoc(K_POSTS, posts(r).assoc(key(n), p));
			made.set(p);
			after.set(updated);
			return withRooms(s, rooms.assoc(name, updated));
		});
		release(name, true);
		tell(name, after.get(), made.get());
		return CompletableFuture.completedFuture(made.get());
	}

	private ACell read(RequestContext ctx, ACell input) {
		AString name = roomName(input);
		AMap<AString, ACell> room = room(name);
		requireReadable(ctx, name, room);
		int limit = limit(input);
		long end = longIn(room, K_NEXT, 0);
		ACell start = RT.getIn(input, K_START);
		long from = (start == null) ? Math.max(0, end - limit) : number(start, "start");
		return page(name, room, from, limit);
	}

	/** The blocking read: answers with what is after {@code after} now, or once a post or the timeout comes. */
	private CompletableFuture<ACell> await(RequestContext ctx, ACell input) {
		AString name = roomName(input);
		AMap<AString, ACell> room = room(name);
		requireReadable(ctx, name, room);
		long after = number(RT.getIn(input, K_AFTER), "after");
		int limit = limit(input);
		ACell t = RT.getIn(input, K_TIMEOUT);
		long timeout = Math.min(MAX_WAIT_MS, Math.max(0, (t == null) ? DEFAULT_WAIT_MS : number(t, "timeout")));
		CompletableFuture<Boolean> arrived = new CompletableFuture<>();
		synchronized (waiting) {
			waiting.computeIfAbsent(name, k -> new ArrayList<>()).add(arrived);
		}
		// Registered before reading, so a post between the two is not missed
		AMap<AString, ACell> now = page(name, room(name), after + 1, limit);
		if (!RT.ensureVector(now.get(K_POSTS)).isEmpty() || timeout == 0) {
			forget(name, arrived);
			return CompletableFuture.completedFuture(now);
		}
		return arrived.completeOnTimeout(true, timeout, TimeUnit.MILLISECONDS).thenApply(present -> {
			forget(name, arrived);
			if (!present) throw new NotFoundException(DELETED.formatted(name), name.toString());
			return (ACell) page(name, room(name), after + 1, limit);
		});
	}

	private CompletableFuture<ACell> update(RequestContext ctx, ACell input) {
		AString name = roomName(input);
		AMap<AString, ACell> room = room(name);
		requireModerator(ctx, name, room);
		return withSession(ctx, name, withSettings(ctx, room, input)).thenApply(changed -> {
			AtomicReference<AMap<AString, ACell>> after = new AtomicReference<>();
			adapterState().update(s -> {
				Index<AString, ACell> rooms = rooms(s);
				AMap<AString, ACell> r = RT.ensureMap(rooms.get(name));
				if (r == null) throw new NotFoundException(NOT_FOUND.formatted(name), name.toString());
				// Only the settings change: posts made meanwhile stay
				AMap<AString, ACell> updated = r;
				for (AString k : SETTINGS) updated = (changed.get(k) == null) ? updated.dissoc(k) : updated.assoc(k, changed.get(k));
				after.set(updated);
				return withRooms(s, rooms.assoc(name, updated));
			});
			return (ACell) summary(name, after.get());
		});
	}

	private ACell prune(RequestContext ctx, ACell input) {
		AString name = roomName(input);
		requireModerator(ctx, name, room(name));
		long before = number(RT.getIn(input, K_BEFORE), "before");
		AtomicReference<Long> pruned = new AtomicReference<>(0L);
		adapterState().update(s -> {
			Index<AString, ACell> rooms = rooms(s);
			AMap<AString, ACell> r = RT.ensureMap(rooms.get(name));
			if (r == null) throw new NotFoundException(NOT_FOUND.formatted(name), name.toString());
			Index<Blob, ACell> posts = posts(r);
			long cut = posts.seek(key(before));
			pruned.set(cut);
			return withRooms(s, rooms.assoc(name, r.assoc(K_POSTS, posts.slice(cut))));
		});
		return Maps.of(K_ROOM, name, K_PRUNED, pruned.get());
	}

	private ACell delete(RequestContext ctx, ACell input) {
		AString name = roomName(input);
		requireModerator(ctx, name, room(name));
		adapterState().update(s -> withRooms(s, rooms(s).dissoc(name)));
		release(name, false);
		return Maps.of(K_ROOM, name, K_DELETED, true);
	}

	// ========== Rights ==========

	/** The capability resource a room is: {@code x/room/<name>}. */
	static AString resource(AString name) {
		return Strings.create("x/" + NAME + "/" + name);
	}

	private void requireRight(RequestContext ctx, AString name, AString ability, String doing) {
		if (!engine.authorityCovers(ctx, resource(name), ability)) {
			throw new AuthException(MAY_NOT.formatted(doing, name));
		}
	}

	private void requireReadable(RequestContext ctx, AString name, AMap<AString, ACell> room) {
		if (!publicly(room, PUBLIC_READ)) requireRight(ctx, name, Capability.CRUD_READ, "read");
	}

	/** The owner moderates, through a request that carries admin/room: a token of theirs only if it was given it. */
	private void requireModerator(RequestContext ctx, AString name, AMap<AString, ACell> room) {
		if (!owner(room).equals(ctx.getAuthorityDID())) throw new AuthException(NOT_OWNER.formatted(name));
		if (!engine.authorityCovers(ctx, resource(name), ADMIN_ROOM)) throw new AuthException(NO_ADMIN.formatted(name));
	}

	private static boolean publicly(AMap<AString, ACell> room, String right) {
		AVector<ACell> rights = RT.ensureVector(room.get(K_PUBLIC));
		if (rights == null) return false;
		for (ACell r : rights) if (right.equals(String.valueOf(r))) return true;
		return false;
	}

	// ========== Notification ==========

	/**
	 * Gives a room's notify target a session of its own, when it names none,
	 * with a notice the subscriber's own request sends: the session the agent
	 * is then told of posts in. A failure leaves the room unchanged.
	 */
	private CompletableFuture<AMap<AString, ACell>> withSession(RequestContext ctx, AString name,
			AMap<AString, ACell> record) {
		AMap<AString, ACell> notify = RT.ensureMap(record.get(K_NOTIFY));
		if (notify == null || notify.get(Fields.SESSION_ID) != null) return CompletableFuture.completedFuture(record);
		AMap<AString, ACell> notice = Maps.of(Fields.AGENT_ID, notify.get(Fields.AGENT_ID),
			"message", NOTICE.formatted(name));
		return engine.jobs().invokeInternal(OP_MESSAGE, notice, ctx).thenApply(result -> {
			ACell made = RT.getIn(result, Fields.SESSION_ID);
			if (made == null) throw new IllegalStateException("Messaging agent " + notify.get(Fields.AGENT_ID) + " gave no session");
			ACell session = (made instanceof Blob b) ? Strings.create(b.toHexString()) : made;
			return record.assoc(K_NOTIFY, notify.assoc(Fields.SESSION_ID, session));
		});
	}

	/**
	 * Tells a room's notify target of a post neither the room's owner, in
	 * person, nor that agent made, so an agent answering in the room never
	 * wakes itself. The subscriber authorised this when choosing the target,
	 * so the poster's authority plays no part: it is delivered as the venue
	 * delivers a late tool result, into the session. The message is the post's
	 * text; its route starts at the room, vouching for whoever posted, as a
	 * scheduled fire's starts at the scheduler. A failure is logged, never the
	 * post's, which is already made.
	 */
	private void tell(AString name, AMap<AString, ACell> room, AMap<AString, ACell> post) {
		AMap<AString, ACell> notify = RT.ensureMap(room.get(K_NOTIFY));
		if (notify == null) return;
		AString owner = owner(room);
		AString account = userOf(owner);
		AString agentId = RT.ensureString(notify.get(Fields.AGENT_ID));
		AString from = RT.ensureString(post.get(K_FROM));
		boolean ownersOwn = owner.equals(from) && post.get(K_VIA) == null;
		if (ownersOwn || Principals.agentDID(account, agentId).equals(from)) return;
		try {
			AgentAdapter agents = (AgentAdapter) engine.getAdapter(AGENT_ADAPTER);
			Blob session = sessionOf(notify.get(Fields.SESSION_ID));
			ACell n = post.get(K_N);
			// The message starts at the room, vouching for whoever posted: how their post reached the room is not part of it
			AVector<ACell> route = Vectors.of(RequestContext.routeRecord(ROUTE_ADAPTER, from, Maps.of(K_ROOM, name, K_N, n),
				RouteText.builder().name(name).build()));
			Boolean delivered = engine.getVenueState().transact(venue -> agents.deliverToWaiter(venue.users(), account,
				agentId, session, null, from, route, Strings.create(name + "/" + n), post.get(K_TEXT)));
			if (!Boolean.TRUE.equals(delivered)) {
				log.warn("Room {}: post {} could not reach agent {}'s session", name, n, agentId);
				return;
			}
			engine.flush();
			agents.wakeAgent(account, agentId, true);
		} catch (Exception e) {
			log.warn("Room {}: telling agent {} of post {} failed: {}", name, agentId, post.get(K_N), e.toString());
		}
	}

	private static Blob sessionOf(ACell id) {
		String hex = String.valueOf(id);
		return Blob.fromHex(hex.startsWith("0x") ? hex.substring(2) : hex);
	}

	// ========== Waiting ==========

	/** Answers every wait on the room: with its posts, or, when it was deleted, that it is gone. */
	private void release(AString name, boolean present) {
		List<CompletableFuture<Boolean>> released;
		synchronized (waiting) {
			released = waiting.remove(name);
		}
		if (released != null) released.forEach(f -> f.complete(present));
	}

	private void forget(AString name, CompletableFuture<Boolean> f) {
		synchronized (waiting) {
			List<CompletableFuture<Boolean>> list = waiting.get(name);
			if (list != null) list.remove(f);
		}
	}

	// ========== State ==========

	private AMap<AString, ACell> room(AString name) {
		AMap<AString, ACell> room = RT.ensureMap(rooms(adapterState().get()).get(name));
		if (room == null) throw new NotFoundException(NOT_FOUND.formatted(name), name.toString());
		return room;
	}

	@SuppressWarnings("unchecked")
	private static Index<AString, ACell> rooms(ACell state) {
		ACell rooms = RT.getIn(state, K_ROOMS);
		return (rooms instanceof Index<?, ?> i) ? (Index<AString, ACell>) i : Index.none();
	}

	private static ACell withRooms(ACell state, Index<AString, ACell> rooms) {
		AMap<AString, ACell> s = RT.ensureMap(state);
		return ((s == null) ? Maps.of() : s).assoc(K_ROOMS, rooms);
	}

	@SuppressWarnings("unchecked")
	private static Index<Blob, ACell> posts(AMap<AString, ACell> room) {
		ACell posts = room.get(K_POSTS);
		return (posts instanceof Index<?, ?> i) ? (Index<Blob, ACell>) i : Index.none();
	}

	/** A post's key: its number as eight big-endian bytes, so key order is post order. */
	static Blob key(long n) {
		return Blobs.forLong(n);
	}

	/** Up to {@code limit} posts numbered from {@code from}, oldest first, with where the room starts and ends. */
	private static AMap<AString, ACell> page(AString name, AMap<AString, ACell> room, long from, int limit) {
		Index<Blob, ACell> posts = posts(room);
		long at = posts.seek(key(Math.max(0, from)));
		Index<Blob, ACell> slice = posts.slice(at, Math.min(posts.count(), at + limit));
		AVector<ACell> shown = Vectors.empty();
		for (long i = 0; i < slice.count(); i++) shown = shown.conj(slice.entryAt(i).getValue());
		long end = longIn(room, K_NEXT, 0);
		long first = posts.isEmpty() ? end : longIn(RT.ensureMap(posts.entryAt(0).getValue()), K_N, end);
		return Maps.of(K_ROOM, name, K_POSTS, shown, K_FIRST, first, K_END, end);
	}

	/** What a room's settings and size are, without its posts. */
	private static AMap<AString, ACell> summary(AString name, AMap<AString, ACell> room) {
		Index<Blob, ACell> posts = posts(room);
		long end = longIn(room, K_NEXT, 0);
		long first = posts.isEmpty() ? end : longIn(RT.ensureMap(posts.entryAt(0).getValue()), K_N, end);
		return room.dissoc(K_POSTS).assoc(K_ROOM, name).assoc(K_FIRST, CVMLong.create(first))
			.assoc(K_END, CVMLong.create(end)).dissoc(K_NEXT);
	}

	/**
	 * {@code record} with the description, public rights and notify target
	 * {@code input} gives: an empty string or list, or an empty notify, clears
	 * one. Naming an agent to notify takes the right to message it.
	 */
	private AMap<AString, ACell> withSettings(RequestContext ctx, AMap<AString, ACell> record, ACell input) {
		ACell description = RT.getIn(input, K_DESCRIPTION);
		if (description != null) {
			String d = text(input, K_DESCRIPTION, MAX_DESCRIPTION, false);
			record = d.isEmpty() ? record.dissoc(K_DESCRIPTION) : record.assoc(K_DESCRIPTION, Strings.create(d));
		}
		ACell rights = RT.getIn(input, K_PUBLIC);
		if (rights != null) {
			AVector<ACell> given = RT.ensureVector(rights);
			if (given == null) throw new InvalidInputException("public must be a list of rights: read, post");
			AVector<ACell> kept = Vectors.empty();
			for (String right : List.of(PUBLIC_READ, PUBLIC_POST)) {
				for (ACell r : given) {
					if (right.equals(String.valueOf(r))) {
						kept = kept.conj(Strings.create(right));
						break;
					}
				}
			}
			if (kept.count() != given.count()) throw new InvalidInputException("public rights are read and post");
			record = kept.isEmpty() ? record.dissoc(K_PUBLIC) : record.assoc(K_PUBLIC, kept);
		}
		ACell notify = RT.getIn(input, K_NOTIFY);
		if (notify != null) {
			AMap<AString, ACell> n = RT.ensureMap(notify);
			if (n == null) throw new InvalidInputException("notify must be {agentId, sessionId?}");
			if (n.isEmpty()) {
				record = record.dissoc(K_NOTIFY);
			} else {
				AString agent = RT.ensureString(n.get(Fields.AGENT_ID));
				if (agent == null || agent.count() == 0) throw new InvalidInputException("notify needs an agentId");
				if (!engine.authorityCovers(ctx, Strings.create("g/" + agent), Abilities.AGENT_MESSAGE)) {
					throw new AuthException("This request may not message agent " + agent);
				}
				AMap<AString, ACell> target = Maps.of(Fields.AGENT_ID, agent);
				AString session = RT.ensureString(n.get(Fields.SESSION_ID));
				if (session != null && session.count() > 0) target = target.assoc(Fields.SESSION_ID, session);
				record = record.assoc(K_NOTIFY, target);
			}
		}
		return record;
	}

	// ========== Inputs ==========

	private static AString roomName(ACell input) {
		AString r = RT.ensureString(RT.getIn(input, K_ROOM));
		if (r == null || !ROOM_NAME.matcher(r.toString()).matches()) {
			throw new InvalidInputException("room must be 1 to 64 lower-case letters, digits and hyphens, such as covia-release");
		}
		return r;
	}

	private static String text(ACell input, AString key, int max, boolean required) {
		ACell v = RT.getIn(input, key);
		AString s = RT.ensureString(v);
		if (v != null && s == null) throw new InvalidInputException(key + " must be text");
		String text = (s == null) ? "" : s.toString().strip();
		if (required && text.isEmpty()) throw new InvalidInputException(key + " is required");
		if (text.length() > max) throw new InvalidInputException(key + " must be at most " + max + " characters");
		return text;
	}

	private static int limit(ACell input) {
		ACell v = RT.getIn(input, K_LIMIT);
		long limit = (v == null) ? DEFAULT_LIMIT : number(v, "limit");
		if (limit < 1 || limit > MAX_LIMIT) throw new InvalidInputException("limit must be 1 to " + MAX_LIMIT);
		return (int) limit;
	}

	private static long number(ACell v, String what) {
		if (v instanceof CVMLong l && l.longValue() >= 0) return l.longValue();
		throw new InvalidInputException(what + " must be a whole number, zero or more");
	}

	private static long longIn(AMap<AString, ACell> map, AString key, long otherwise) {
		return (map != null && map.get(key) instanceof ANumeric n) ? n.longValue() : otherwise;
	}

	private static AString owner(ACell room) {
		return RT.ensureString(RT.getIn(room, K_OWNER));
	}

	private static AString userOf(AString principal) {
		return Principals.isAgentDID(principal) ? Principals.userOf(principal) : principal;
	}

	/** The token a request came through, by name, or null for a login or an agent. */
	private static AString via(RequestContext ctx) {
		AVector<ACell> tokens = ctx.getTokens();
		if (tokens == null || tokens.isEmpty()) return null;
		return RT.ensureString(RT.getIn(tokens.get(tokens.count() - 1), Fields.NAME));
	}
}
