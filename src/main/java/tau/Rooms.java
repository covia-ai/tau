package tau;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.InvalidInputException;
import covia.grid.Principals;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.Engine.UserPathTarget;

/**
 * Collaboration rooms: a shared conversation per topic in the owner's
 * workspace, at {@code w/collab/<room>/posts}, where people, outside agents
 * reaching Tau over MCP, and the owner's own agents talk. Anyone who may read
 * a room may post in it, through {@link #post}, which records who posted from
 * the request itself, so a post cannot claim another author.
 *
 * <p>Only a mention wakes an agent ({@code @Name}, one of the owner's own
 * agents, never a god), and the wake-up goes to it as a task under the
 * poster's own authority: a token's post wakes the agent narrowed by that
 * token. The agent's answer is posted back in the room as the agent, and the
 * agents it mentions are asked as that poster too. What
 * keeps that from multiplying is mechanism, not the agents' manners:
 * <ul>
 * <li>a post by anyone but an agent starts a thread with {@link #THREAD_BUDGET}
 *     wake-ups; an answer posted back belongs to the thread that woke it and
 *     spends from it, so the agents' turns are bounded by the budget of the
 *     posts people and outside clients make;</li>
 * <li>a post an agent makes any other way starts a thread with no budget;</li>
 * <li>an agent has one wake-up in flight per room, and mentions that arrive
 *     meanwhile wait and go to it together;</li>
 * <li>a post wakes at most {@link #MAX_MENTIONS} agents, never its own
 *     author and none its poster may not ask, and an agent is woken at most
 *     {@link #WAKES_PER_HOUR} times an hour per room.</li>
 * </ul>
 * Thread budgets live in memory: after a restart an old thread has none, so
 * nothing resumes waking. Clients that cannot be called, such as MCP clients,
 * follow a room with {@link #await}.
 */
final class Rooms {

	private static final Logger log = LoggerFactory.getLogger(Rooms.class);

	/** Wake-ups a thread started by a person or an outside client may spend. */
	static final int THREAD_BUDGET = 6;
	/** Agents one post may wake. */
	static final int MAX_MENTIONS = 3;
	/** Wake-ups of one agent in one room per hour. */
	static final int WAKES_PER_HOUR = 10;
	/** The longest {@link #await} holds a request open. */
	static final long MAX_WAIT_MS = 300_000;
	/** How long a woken agent may take before its answer is no longer awaited. */
	static final long ANSWER_TIMEOUT_MS = 600_000;
	static final int MAX_TEXT = 8000;
	static final int MAX_READ = 200;

	private static final Pattern ROOM = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
	private static final Pattern MENTION = Pattern.compile("(?<![\\w@])@([A-Za-z][A-Za-z0-9_-]{0,63})");
	private static final String OP_AGENT_REQUEST = "v/ops/agent/request";

	private static final AString K_ROOM = Strings.intern("room");
	private static final AString K_TEXT = Strings.intern("text");
	private static final AString K_AFTER = Strings.intern("after");
	private static final AString K_LIMIT = Strings.intern("limit");
	private static final AString K_TIMEOUT = Strings.intern("timeout");
	private static final AString K_N = Strings.intern("n");
	private static final AString K_TS = Strings.intern("ts");
	private static final AString K_FROM = Strings.intern("from");
	private static final AString K_VIA = Strings.intern("via");
	private static final AString K_THREAD = Strings.intern("thread");
	private static final AString K_DEPTH = Strings.intern("depth");
	private static final AString K_POSTS = Strings.intern("posts");
	private static final AString K_NEXT = Strings.intern("next");
	private static final AString K_WOKE = Strings.intern("woke");
	private static final AString K_QUEUED = Strings.intern("queued");
	private static final AString K_NOT_WOKEN = Strings.intern("notWoken");
	private static final AString K_TASK = Strings.intern("task");
	private static final AString K_RESPONSE = Strings.intern("response");
	private static final AString K_RESULT = Strings.intern("result");

	private final Engine engine;

	/** Requests waiting for a room's next post, by owner and room. */
	private final Map<String, List<CompletableFuture<Void>>> waiting = new HashMap<>();
	/** Wake-ups left in each thread that may still spend them, by owner, room and the thread's first post. */
	private final Map<String, Integer> budgets = new HashMap<>();
	/** Each agent's place in each room: its session, whether it is woken, what waits for it. */
	private final Map<String, Seat> seats = new HashMap<>();

	Rooms(Engine engine) {
		this.engine = engine;
	}

	/** One agent in one room. */
	private static final class Seat {
		Blob sessionId;
		boolean woken;
		final Deque<Wake> queued = new ArrayDeque<>();
		final Deque<Long> wakes = new ArrayDeque<>();
	}

	/** Posts to hand an agent, by one poster, in one thread. */
	private record Wake(RequestContext as, long thread, long depth, List<AMap<AString, ACell>> posts) {
	}

	/** {@code room-post}: records a post as its caller and wakes the agents it mentions. */
	CompletableFuture<ACell> post(RequestContext ctx, ACell input) {
		try {
			String room = room(input);
			String text = text(input);
			AString owner = owner(ctx);
			engine.requirePathRead(ctx, Strings.create(path(room)));
			AString author = ctx.getAuthorityDID();
			boolean byAgent = Principals.agentIdOf(author) != null || ctx.getAgentId() != null;
			AMap<AString, ACell> posted = append(owner, room, author, via(ctx), text, -1, 0);
			long n = ((CVMLong) posted.get(K_N)).longValue();
			// A post by an agent other than as an answer starts a thread with nothing to spend
			String thread = key(owner, room, n);
			if (!byAgent) {
				synchronized (this) {
					budgets.put(thread, THREAD_BUDGET);
				}
			}
			AMap<AString, ACell> outcome = mentions(ctx, owner, room, posted);
			if (RT.ensureVector(outcome.get(K_WOKE)).isEmpty() && RT.ensureVector(outcome.get(K_QUEUED)).isEmpty()) {
				// Nothing will answer in this thread, so nothing will spend from it
				synchronized (this) {
					budgets.remove(thread);
				}
			}
			return CompletableFuture.completedFuture(posted.assoc(K_WOKE, outcome.get(K_WOKE))
				.assoc(K_QUEUED, outcome.get(K_QUEUED)).assoc(K_NOT_WOKEN, outcome.get(K_NOT_WOKEN)));
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/** {@code room-read}: the posts after {@code after}, oldest first, at most {@link #MAX_READ}. */
	CompletableFuture<ACell> read(RequestContext ctx, ACell input) {
		try {
			String room = room(input);
			AString owner = owner(ctx);
			engine.requirePathRead(ctx, Strings.create(path(room)));
			return CompletableFuture.completedFuture(page(owner, room, after(input), limit(input)));
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * {@code room-wait}: as {@link #read}, but when there is nothing after
	 * {@code after} it answers once a post arrives or {@code timeout} passes.
	 */
	CompletableFuture<ACell> await(RequestContext ctx, ACell input) {
		try {
			String room = room(input);
			AString owner = owner(ctx);
			engine.requirePathRead(ctx, Strings.create(path(room)));
			long after = after(input);
			int limit = limit(input);
			long timeout = Math.min(MAX_WAIT_MS, Math.max(0, longOf(input, K_TIMEOUT, 60_000)));
			CompletableFuture<Void> arrived = new CompletableFuture<>();
			synchronized (this) {
				waiting.computeIfAbsent(roomKey(owner, room), k -> new ArrayList<>()).add(arrived);
			}
			// Registered first, then read, so a post between the two is not missed
			AMap<AString, ACell> now = page(owner, room, after, limit);
			if (RT.ensureVector(now.get(K_POSTS)).count() > 0 || timeout == 0) {
				forget(owner, room, arrived);
				return CompletableFuture.completedFuture(now);
			}
			return arrived.completeOnTimeout(null, timeout, TimeUnit.MILLISECONDS)
				.thenApply(v -> {
					forget(owner, room, arrived);
					return (ACell) page(owner, room, after, limit);
				});
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/** Wakes the agents {@code posted} mentions, within its thread's budget. */
	private AMap<AString, ACell> mentions(RequestContext as, AString owner, String room, AMap<AString, ACell> posted) {
		AString author = RT.ensureString(posted.get(K_FROM));
		AString self = Principals.agentIdOf(author);
		Set<String> named = new LinkedHashSet<>();
		Matcher m = MENTION.matcher(posted.get(K_TEXT).toString());
		while (m.find() && named.size() < MAX_MENTIONS) {
			String id = m.group(1);
			if (self != null && self.toString().equals(id)) continue;
			if (engine.getAgent(owner, Strings.create(id)) == null) continue;
			named.add(id);
		}
		long thread = ((CVMLong) posted.get(K_THREAD)).longValue();
		long depth = ((CVMLong) posted.get(K_DEPTH)).longValue();
		AVector<ACell> woke = Vectors.empty(), queued = Vectors.empty(), notWoken = Vectors.empty();
		for (String agent : named) {
			// The wake-up asks the agent as the poster, so a poster who may not ask it wakes nothing
			if (!engine.authorityCovers(as, Strings.create("g/" + agent), Abilities.AGENT_REQUEST)) {
				notWoken = notWoken.conj(Strings.create(agent));
				continue;
			}
			switch (offer(owner, room, agent, new Wake(as, thread, depth, List.of(posted)))) {
				case WOKEN -> woke = woke.conj(Strings.create(agent));
				case QUEUED -> queued = queued.conj(Strings.create(agent));
				case REFUSED -> notWoken = notWoken.conj(Strings.create(agent));
			}
		}
		return Maps.of(K_WOKE, woke, K_QUEUED, queued, K_NOT_WOKEN, notWoken);
	}

	private enum Offer { WOKEN, QUEUED, REFUSED }

	/** Hands {@code wake} to {@code agent} now, or queues it behind the wake-up it is answering. */
	private Offer offer(AString owner, String room, String agent, Wake wake) {
		Seat seat;
		synchronized (this) {
			seat = seats.computeIfAbsent(seatKey(owner, room, agent), k -> new Seat());
			if (seat.woken) {
				Wake last = seat.queued.peekLast();
				if (last != null && last.thread() == wake.thread()
						&& last.as().getAuthorityDID().equals(wake.as().getAuthorityDID())) {
					List<AMap<AString, ACell>> posts = new ArrayList<>(last.posts());
					posts.addAll(wake.posts());
					seat.queued.pollLast();
					seat.queued.addLast(new Wake(last.as(), last.thread(), Math.max(last.depth(), wake.depth()), posts));
				} else {
					seat.queued.addLast(wake);
				}
				return Offer.QUEUED;
			}
			if (!spend(owner, room, seat, wake.thread())) return Offer.REFUSED;
			seat.woken = true;
		}
		wake(owner, room, agent, seat, wake);
		return Offer.WOKEN;
	}

	/** Spends one of the thread's wake-ups and one of the agent's hourly ones; false when either is gone. */
	private boolean spend(AString owner, String room, Seat seat, long thread) {
		String key = key(owner, room, thread);
		int left = budgets.getOrDefault(key, 0);
		long now = System.currentTimeMillis();
		while (!seat.wakes.isEmpty() && seat.wakes.peekFirst() < now - 3_600_000) seat.wakes.pollFirst();
		if (left <= 0 || seat.wakes.size() >= WAKES_PER_HOUR) return false;
		// A thread with nothing left needs no entry: a missing one has nothing to spend
		if (left == 1) budgets.remove(key); else budgets.put(key, left - 1);
		seat.wakes.addLast(now);
		return true;
	}

	/** Gives {@code agent} the posts as a task under the poster's authority, and posts its answer back. */
	private void wake(AString owner, String room, String agent, Seat seat, Wake wake) {
		StringBuilder task = new StringBuilder("You were mentioned in the collaboration room '").append(room)
			.append("'. Read the posts below and answer briefly. Your answer is posted back in the room as you; ")
			.append("mention someone with @Name only when you need them to act, since each mention spends the ")
			.append("thread's small budget. Answer with nothing when there is nothing to add.\n");
		for (AMap<AString, ACell> p : wake.posts()) {
			task.append("\n[post ").append(p.get(K_N)).append(" from ").append(p.get(K_FROM));
			if (p.get(K_VIA) != null) task.append(" via token ").append(p.get(K_VIA));
			task.append("]\n").append(p.get(K_TEXT)).append('\n');
		}
		AMap<AString, ACell> input = Maps.of(Fields.AGENT_ID, agent,
			Fields.INPUT, Maps.of(K_TASK, task.toString(), K_ROOM, room),
			K_TIMEOUT, ANSWER_TIMEOUT_MS);
		Blob session;
		synchronized (this) {
			session = seat.sessionId;
		}
		if (session != null) input = input.assoc(Fields.SESSION_ID, Strings.create(session.toHexString()));
		CompletableFuture<ACell> answered;
		try {
			answered = engine.jobs().invokeInternal(OP_AGENT_REQUEST, input, wake.as());
		} catch (Exception e) {
			answered = CompletableFuture.failedFuture(e);
		}
		answered.whenComplete((result, failed) -> {
			try {
				if (failed != null) {
					log.warn("Room {}: waking {} failed: {}", room, agent, failed.toString());
				} else {
					Blob sid = sessionOf(result);
					synchronized (this) {
						if (sid != null) seat.sessionId = sid;
					}
					String reply = answer(result);
					if (reply != null) {
						AString agentDID = Principals.agentDID(owner, Strings.create(agent));
						AMap<AString, ACell> posted = append(owner, room, agentDID, null,
							reply.length() > MAX_TEXT ? reply.substring(0, MAX_TEXT) : reply, wake.thread(), wake.depth() + 1);
						// Its mentions ask as the thread's poster did: an answer reaches no
						// further than the post that woke it
						mentions(wake.as(), owner, room, posted);
					}
				}
			} catch (Exception e) {
				log.warn("Room {}: posting {}'s answer failed: {}", room, agent, e.toString());
			} finally {
				next(owner, room, agent, seat);
			}
		});
	}

	/** Hands the agent what waited for it, or leaves its seat free. */
	private void next(AString owner, String room, String agent, Seat seat) {
		Wake wake;
		List<CompletableFuture<Void>> settled = null;
		synchronized (this) {
			seat.woken = false;
			while ((wake = seat.queued.pollFirst()) != null) {
				if (spend(owner, room, seat, wake.thread())) {
					seat.woken = true;
					break;
				}
			}
			if (wake == null && seats.values().stream().noneMatch(s -> s.woken)) {
				settled = new ArrayList<>(idle);
				idle.clear();
			}
		}
		if (wake != null) wake(owner, room, agent, seat, wake);
		if (settled != null) settled.forEach(f -> f.complete(null));
	}

	/** Requests to hear when no agent is woken in any room. */
	private final List<CompletableFuture<Void>> idle = new ArrayList<>();

	/** Completes once no agent is woken in any room: now, if none is. */
	synchronized CompletableFuture<Void> whenIdle() {
		if (seats.values().stream().noneMatch(s -> s.woken)) return CompletableFuture.completedFuture(null);
		CompletableFuture<Void> f = new CompletableFuture<>();
		idle.add(f);
		return f;
	}

	/**
	 * Appends a post as {@code author}, written as the owner since the caller's
	 * right to post was checked as the right to read. {@code thread} below zero
	 * starts a thread at this post.
	 */
	private AMap<AString, ACell> append(AString owner, String room, AString author, AString via, String text,
			long thread, long depth) {
		UserPathTarget target = engine.requireUserPath(RequestContext.of(owner), Strings.create(path(room) + "/posts"),
			Capability.CRUD_WRITE, true);
		AMap<AString, ACell>[] made = new AMap[1];
		target.user().execute(draft -> {
			var workspace = draft.workspace();
			var relative = target.path().slice(1);
			AVector<ACell> posts = RT.ensureVector(workspace.read(relative));
			if (posts == null) {
				workspace.createPath(relative.slice(0, relative.count() - 1));
				posts = Vectors.empty();
			}
			long n = posts.count();
			AMap<AString, ACell> post = Maps.of(K_N, n, K_TS, engine.currentTimestamp(), K_FROM, author,
				K_TEXT, Strings.create(text), K_THREAD, (thread < 0) ? n : thread, K_DEPTH, depth);
			if (via != null) post = post.assoc(K_VIA, via);
			workspace.write(relative, posts.conj(post));
			made[0] = post;
		});
		List<CompletableFuture<Void>> woken;
		synchronized (this) {
			woken = waiting.remove(roomKey(owner, room));
		}
		if (woken != null) woken.forEach(f -> f.complete(null));
		return made[0];
	}

	private AMap<AString, ACell> page(AString owner, String room, long after, int limit) {
		UserPathTarget target = engine.requireUserPath(RequestContext.of(owner), Strings.create(path(room) + "/posts"),
			Capability.CRUD_READ, false);
		AVector<ACell> posts = RT.ensureVector(target.user().workspace().read(target.path().slice(1)));
		long count = (posts == null) ? 0 : posts.count();
		long from = Math.min(count, Math.max(0, after + 1));
		long to = Math.min(count, from + limit);
		AVector<ACell> slice = (posts == null || from >= to) ? Vectors.empty() : posts.slice(from, to);
		return Maps.of(K_ROOM, room, K_POSTS, slice, K_NEXT, to - 1);
	}

	private synchronized void forget(AString owner, String room, CompletableFuture<Void> f) {
		List<CompletableFuture<Void>> list = waiting.get(roomKey(owner, room));
		if (list != null) list.remove(f);
	}

	/**
	 * The agent's answer as text, or null when it had nothing to add or has not
	 * finished: {@code agent:request} answers with the task's {@code output} once
	 * it is complete, and with a snapshot of its status when the wait ran out.
	 */
	private static String answer(ACell result) {
		if (!Status.COMPLETE.equals(RT.getIn(result, Fields.STATUS))) return null;
		ACell output = RT.getIn(result, Fields.OUTPUT);
		if (output instanceof AString s) return blankToNull(s.toString());
		if (output == null) return null;
		for (AString k : new AString[] {K_RESPONSE, K_RESULT, K_TEXT}) {
			if (RT.getIn(output, k) instanceof AString s) return blankToNull(s.toString());
		}
		// A structured answer of the agent's own shape, posted as it is
		return blankToNull(convex.core.util.JSON.toString(output));
	}

	private static String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s.strip();
	}

	private static Blob sessionOf(ACell result) {
		ACell sid = RT.getIn(result, Fields.SESSION_ID);
		if (sid instanceof Blob b) return b;
		if (sid instanceof AString s) {
			String hex = s.toString();
			return Blob.fromHex(hex.startsWith("0x") ? hex.substring(2) : hex);
		}
		return null;
	}

	/** The token a request came through, by name, or null for a login or an agent. */
	private static AString via(RequestContext ctx) {
		AVector<ACell> tokens = ctx.getTokens();
		if (tokens == null || tokens.isEmpty()) return null;
		return RT.ensureString(RT.getIn(tokens.get(tokens.count() - 1), Fields.NAME));
	}

	private static AString owner(RequestContext ctx) {
		AString owner = (ctx != null) ? ctx.getUserDID() : null;
		if (owner == null) throw new covia.exception.AuthException("Rooms are reached as a user of this venue");
		return owner;
	}

	static String path(String room) {
		return "w/collab/" + room;
	}

	private static String room(ACell input) {
		AString r = RT.ensureString(RT.getIn(input, K_ROOM));
		String room = (r == null) ? null : r.toString().strip();
		if (room == null || !ROOM.matcher(room).matches()) {
			throw new InvalidInputException("room must be lower-case letters, digits and hyphens, such as covia-release");
		}
		return room;
	}

	private static String text(ACell input) {
		AString t = RT.ensureString(RT.getIn(input, K_TEXT));
		String text = (t == null) ? null : t.toString().strip();
		if (text == null || text.isEmpty()) throw new InvalidInputException("text is required");
		if (text.length() > MAX_TEXT) throw new InvalidInputException("text must be at most " + MAX_TEXT + " characters");
		return text;
	}

	private static long after(ACell input) {
		return longOf(input, K_AFTER, -1);
	}

	private static int limit(ACell input) {
		return (int) Math.min(MAX_READ, Math.max(1, longOf(input, K_LIMIT, 50)));
	}

	private static long longOf(ACell input, AString key, long otherwise) {
		ACell v = RT.getIn(input, key);
		if (v instanceof CVMLong l) return l.longValue();
		if (v instanceof convex.core.data.prim.ANumeric num) return num.longValue();
		return otherwise;
	}

	private static String roomKey(AString owner, String room) {
		return owner + "|" + room;
	}

	private static String key(AString owner, String room, long thread) {
		return owner + "|" + room + "|" + thread;
	}

	private static String seatKey(AString owner, String room, String agent) {
		return owner + "|" + room + "|@" + agent;
	}
}
