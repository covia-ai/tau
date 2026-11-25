package tau;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.hitl.Hitl;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * The gate on an agent's writes and deletes ({@code v/ops/tau/confirm-change},
 * docs/SKILLS.md, "Deletes and overwrites wait for you"): enforced by Covia on
 * every call through a gated grant, so no prompt can talk past it. A write
 * that makes something new goes ahead. An overwrite or a delete raises an
 * approval in the owner's Inbox and waits a short while; approved, the call
 * goes ahead; declined, it is refused; unanswered, it is refused with "waiting
 * for your approval", and the same call goes ahead once the owner approves.
 * Nothing changes until then. Scheduled runs, with nobody at the window, wait
 * in the Inbox the same way.
 */
final class ChangeGate {

	private static final Logger log = LoggerFactory.getLogger(ChangeGate.class);

	/** How long a call waits for an answer before it is refused as waiting; Covia allows a gate 30 seconds. */
	static volatile long waitMillis = 20_000;

	static final String OP_HITL_REQUEST = "v/ops/hitl/request";

	/** What a call would do to what is there. */
	enum Change {
		NEW, OVERWRITE, DELETE,
		/** Giving an agent permissions (an agent made or updated with caps): it always asks. */
		PERMISSIONS
	}

	/** How long an approval holds once given: long enough for the one call's checks, never days. */
	static volatile long approvedMillis = 60_000;
	/** How long an unanswered request stands before a new call asks afresh. */
	static final long ASK_EXPIRES = 24L * 3600 * 1000;

	private final Engine engine;
	/** The approval asked for each pending change (the agent, the operation, and its target), and when. */
	private final Map<String, Pending> asked = new ConcurrentHashMap<>();
	/** Changes the owner approved, until when the approval holds. */
	private final Map<String, Long> approved = new ConcurrentHashMap<>();
	/** Changes the owner declined, until when the answer stands for the same call's other checks. */
	private final Map<String, Long> declined = new ConcurrentHashMap<>();

	/** An approval asked, when, and until when a check last waited on it. */
	private static final class Pending {
		final Job job;
		final long since;
		volatile long waitedUntil;

		Pending(Job job, long since) {
			this.job = job;
			this.since = since;
		}

		Job job() {
			return job;
		}

		long since() {
			return since;
		}
	}

	/** A check within this long of the last wait on the same request refuses at once rather than waiting again. */
	static final long REWAIT_MILLIS = 5_000;

	ChangeGate(Engine engine) {
		this.engine = engine;
	}

	/** Rules on one invocation, {@code {operation, input, caller}} as Covia hands a gate. */
	CompletableFuture<ACell> check(ACell gateInput) {
		return CompletableFuture.supplyAsync(() -> decide(gateInput), r -> Thread.ofVirtual().start(r));
	}

	private ACell decide(ACell gateInput) {
		String op = str(RT.getIn(gateInput, Fields.OPERATION));
		ACell input = RT.getIn(gateInput, Fields.INPUT);
		String caller = str(RT.getIn(gateInput, Fields.CALLER));
		if (caller == null) throw new AuthException("The change gate needs its caller");
		String owner = owner(caller);
		String target = target(input);
		// No operation named (a call by its metadata): what it does can't be told, so ask.
		Change change = (op == null) ? Change.OVERWRITE : change(op, input, owner);
		if (change == Change.NEW) return Maps.of("allowed", CVMBool.TRUE);
		String key = caller + "|" + op + "|" + target;
		long now = System.currentTimeMillis();
		Long until = approved.get(key);
		if (until != null && until > now) return Maps.of("allowed", CVMBool.TRUE, "approved", CVMBool.TRUE);
		approved.remove(key);
		String what = switch (change) {
			case DELETE -> "delete " + target;
			case PERMISSIONS -> "give " + target + " permissions";
			default -> "change " + target;
		};
		Long no = declined.get(key);
		if (no != null && no > now) throw new AuthException("The owner declined to " + what + ". Nothing was changed.");
		declined.remove(key);
		Pending pending = asked.compute(key, (k, p) -> (p == null || now - p.since() > ASK_EXPIRES)
			? new Pending(ask(caller, owner, change, op == null ? "an operation" : op, target), now) : p);
		// A call checks more than once: only the first check waits; the next refuses at once if still unanswered.
		boolean waitedJustNow = System.currentTimeMillis() - pending.waitedUntil < REWAIT_MILLIS;
		Boolean answer = answer(pending.job(), waitedJustNow ? 0 : waitMillis);
		pending.waitedUntil = System.currentTimeMillis();
		if (answer == null) {
			throw new AuthException("Waiting for the owner's approval in the Inbox to " + what
				+ ". Nothing was changed; try the same call again once they approve.");
		}
		asked.remove(key);
		if (!answer) {
			// The same call checks again: it hears the same no, and no new card is raised for it.
			declined.put(key, System.currentTimeMillis() + approvedMillis);
			throw new AuthException("The owner declined to " + what + ". Nothing was changed.");
		}
		// One approval covers this call's checks (an adapter may check twice), for a short while only.
		approved.put(key, System.currentTimeMillis() + approvedMillis);
		return Maps.of("allowed", CVMBool.TRUE, "approved", CVMBool.TRUE);
	}

	/**
	 * What {@code op} would do: a delete always counts; a write or copy over
	 * something already there is an overwrite; anything else is new.
	 */
	Change change(String op, ACell input, String owner) {
		String name = op.substring(op.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
		// Making or updating an agent with caps confers authority (Covia checks the author holds each
		// grant, through this gate): asked every time, so no agent gets a write or delete that does not ask.
		if (op.contains("/agent/") && RT.getIn(input, "config", "caps") != null) return Change.PERMISSIONS;
		if (name.startsWith("delete") || name.equals("remove") || name.equals("prune")) return Change.DELETE;
		// Any call told to overwrite (a generated key over a stored one, say) is an overwrite.
		if (CVMBool.TRUE.equals(RT.getIn(input, "overwrite"))) return Change.OVERWRITE;
		// A stored key replaced is an overwrite; secrets have no read, so their names are listed.
		if (op.contains("/secret/") && name.equals("set")) {
			return secretExists(op, input, owner) ? Change.OVERWRITE : Change.NEW;
		}
		if (name.equals("write") || name.equals("copy") || name.equals("move") || name.equals("rename")
			|| name.equals("put") || name.equals("assoc")) {
			return exists(op, input, owner) ? Change.OVERWRITE : Change.NEW;
		}
		return Change.NEW;
	}

	/**
	 * Whether the target of a write is already there, read as the owner
	 * through the same adapter, with only the fields that say where. When it
	 * can't be told (no read answers, or one fails for another reason than the
	 * target missing), it counts as there: the owner is asked rather than a
	 * change slipping through.
	 */
	boolean exists(String op, ACell input, String owner) {
		String base = op.substring(0, op.lastIndexOf('/') + 1);
		convex.core.data.AMap<AString, ACell> probe = Maps.empty();
		for (String k : new String[] { "root", "drive" }) {
			ACell v = RT.getIn(input, k);
			if (v != null) probe = probe.assoc(Strings.create(k), v);
		}
		ACell where = RT.getIn(input, "to");
		if (where == null) where = RT.getIn(input, "path");
		if (where == null) return true;
		probe = probe.assoc(Strings.create("path"), where);
		RequestContext me = RequestContext.of(Strings.create(owner));
		boolean missing = false;
		for (String read : new String[] { "stat", "read" }) {
			try {
				ACell got = engine.jobs().invokeInternal(base + read, probe, me).get(5, TimeUnit.SECONDS);
				if (got == null) continue;
				if (CVMBool.FALSE.equals(RT.getIn(got, "exists"))) return false;
				if (got instanceof convex.core.data.AMap<?, ?> m && m.containsKey(Strings.create("value"))) {
					return RT.getIn(got, "value") != null;
				}
				return true;
			} catch (Exception e) {
				if (isMissing(e)) missing = true;
			}
		}
		return !missing;
	}

	/** Whether the secret {@code set} names is already stored; when it can't be told, it counts as there. */
	boolean secretExists(String op, ACell input, String owner) {
		String name = str(RT.getIn(input, "name"));
		if (name == null) return true;
		String list = op.substring(0, op.lastIndexOf('/') + 1) + "list";
		try {
			ACell got = engine.jobs().invokeInternal(list, Maps.of("prefix", name), RequestContext.of(Strings.create(owner)))
				.get(5, TimeUnit.SECONDS);
			convex.core.data.AVector<ACell> names = RT.ensureVector(RT.getIn(got, "names"));
			if (names == null) return true;
			for (ACell n : names) if (name.equals(str(n))) return true;
			return false;
		} catch (Exception e) {
			return true;
		}
	}

	/** Whether a read failed because nothing is there, rather than for some other reason. */
	private static boolean isMissing(Throwable e) {
		for (Throwable c = e; c != null; c = (c.getCause() == c) ? null : c.getCause()) {
			if (c instanceof covia.exception.NotFoundException || c instanceof java.nio.file.NoSuchFileException) return true;
			String m = String.valueOf(c.getMessage()).toLowerCase(java.util.Locale.ROOT);
			if (m.contains("not found") || m.contains("no such") || m.contains("does not exist") || m.contains("not exist")) {
				return true;
			}
		}
		return false;
	}

	/** Raises the approval in the owner's Inbox, as the agent, and returns its job. */
	private Job ask(String caller, String owner, Change change, String op, String target) {
		String agent = caller.contains(":g:") ? caller.substring(caller.indexOf(":g:") + 3) : caller;
		String verb = switch (change) {
			case DELETE -> "Delete";
			case PERMISSIONS -> "Give permissions to";
			default -> "Overwrite";
		};
		RequestContext as = engine.context(Strings.create(owner), Strings.create(caller));
		String title = (change == Change.PERMISSIONS) ? "Give " + target + " permissions?" : verb + " " + target + "?";
		ACell request = Hitl.request(title)
			.description(agent + " wants to " + verb.toLowerCase(java.util.Locale.ROOT) + " " + target + " (" + op
				+ "). Nothing changes until you approve.")
			.ask(Hitl.approval("ok", "Allow it?"))
			.build();
		log.info("{} asks to {} {}: waiting for the owner", agent, verb.toLowerCase(java.util.Locale.ROOT), target);
		return engine.jobs().invokeOperation(OP_HITL_REQUEST, request, as);
	}

	/** True approved, false declined (or withdrawn, expired, refused), null not answered within {@code millis}. */
	static Boolean answer(Job job, long millis) {
		try {
			if (millis <= 0 && !job.isFinished() && !job.future().isDone()) return null;
			ACell out = job.awaitResult(Math.max(1, millis));
			return CVMBool.TRUE.equals(RT.getIn(out, "answers", "ok"));
		} catch (Exception e) {
			// Declined, rejected, withdrawn or expired: the request's job has ended without a yes.
			if (job.isFinished() || job.future().isDone()) return false;
			return null;
		}
	}

	/** The owner of an agent ({@code <owner>:g:<id>}), or the caller itself. */
	static String owner(String caller) {
		int g = caller.indexOf(":g:");
		return (g > 0) ? caller.substring(0, g) : caller;
	}

	/** What a call acts on, in words: its path, with the root or drive it names. */
	static String target(ACell input) {
		StringBuilder sb = new StringBuilder();
		for (String k : new String[] { "root", "drive" }) {
			String v = str(RT.getIn(input, k));
			if (v != null) sb.append(v).append(':');
		}
		for (String k : new String[] { "path", "to", "file", "agentId", "name", "id" }) {
			String v = str(RT.getIn(input, k));
			if (v != null) return sb.append(v).toString();
		}
		return sb.length() > 0 ? sb.toString() : "an item";
	}

	private static String str(ACell c) {
		return (c instanceof AString s) ? s.toString() : (c == null ? null : c.toString());
	}
}
