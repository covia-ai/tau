package tau.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.RequestContext;
import tau.Ids;

/**
 * Stop on a reply in flight ends the whole run, not only the wait for it
 * (docs/JOBS.md, "Stopping a run"): the chat job; the agent's turn, by
 * suspending the agent for a moment, which cancels its transition and its
 * backend jobs, and resuming it straight away; and every job the run started
 * that is still going, found by tracing each job's {@code parent} to the chat
 * job and by the agent's own jobs since it began. A Claude Code session among
 * them is stopped with {@code claudecode:stop} as well as cancelled. Steps
 * that already finished keep their results: only jobs still going are touched.
 *
 * <p>Runs as the owner in person, in process; never on the event thread.</p>
 */
public final class StopRun {

	private static final Logger log = LoggerFactory.getLogger(StopRun.class);

	static final String OP_SUSPEND = "v/ops/agent/suspend";
	static final String OP_RESUME = "v/ops/agent/resume";
	static final String OP_CLAUDE_STOP = "v/ops/claudecode/stop";
	static final String REASON = "Stopped by the owner from the chat";
	private static final AString K_PARENT = Strings.intern("parent");
	private static final Set<String> GOING = Set.of("PENDING", "STARTED", "PAUSED", "INPUT_REQUIRED", "AUTH_REQUIRED");
	private static final long WAIT_SECONDS = 20;

	/** What a stop did: jobs cancelled besides the chat job, Claude Code sessions stopped, and the turn interrupted. */
	public record Result(int jobsCancelled, int claudeSessionsStopped, boolean turnInterrupted) {
	}

	private StopRun() {
	}

	/**
	 * Stops the run behind chat job {@code chatJob}, the owner {@code owner}'s
	 * turn with agent {@code agentId}.
	 */
	public static Result stop(Engine engine, AString owner, Blob chatJob, String agentId) {
		RequestContext me = RequestContext.of(owner);
		Index<Blob, ACell> before = engine.jobs().getJobs(me);
		AMap<AString, ACell> chat = record(before, chatJob);
		long since = (chat == null) ? Long.MAX_VALUE : num(chat.get(Fields.CREATED));
		try {
			engine.jobs().cancelJob(chatJob, me);
		} catch (Exception e) {
			log.info("The chat job had already ended: {}", e.toString());
		}
		boolean interrupted = interrupt(engine, me, agentId);
		String agentDID = owner + ":g:" + agentId;
		List<Blob> run = started(engine.jobs().getJobs(me), chatJob.toHexString(), agentDID, since);
		int cancelled = 0, stopped = 0;
		for (Blob id : run) {
			AMap<AString, ACell> rec = record(engine.jobs().getJobs(me), id);
			if (rec == null || !GOING.contains(str(rec.get(Fields.STATUS)))) continue;
			String session = claudeSession(rec);
			if (session != null && stopClaude(engine, me, session)) stopped++;
			try {
				engine.jobs().cancelJob(id, me);
				cancelled++;
			} catch (Exception e) {
				log.info("Could not cancel job {} of the stopped run: {}", id.toHexString(), e.toString());
			}
		}
		log.info("Stopped the run of {}: {} jobs cancelled, {} Claude Code sessions stopped", agentId, cancelled, stopped);
		return new Result(cancelled, stopped, interrupted);
	}

	/**
	 * The jobs of the run, still going or not: every job whose {@code parent}
	 * chain reaches the chat job, and every job the agent made since the chat
	 * job began, with theirs in turn.
	 */
	static List<Blob> started(Index<Blob, ACell> jobs, String chatJob, String agentDID, long since) {
		Map<String, String> parent = new HashMap<>();
		Map<String, Blob> ids = new HashMap<>();
		Set<String> roots = new HashSet<>(Set.of(chatJob));
		for (long i = 0; i < jobs.count(); i++) {
			MapEntry<Blob, ACell> e = jobs.entryAt(i);
			AMap<AString, ACell> rec = RT.ensureMap(e.getValue());
			if (rec == null) continue;
			String id = e.getKey().toHexString();
			ids.put(id, e.getKey());
			String p = Ids.text(rec.get(K_PARENT));
			if (p != null) parent.put(id, p.startsWith("0x") ? p.substring(2) : p);
			if (agentDID.equals(str(rec.get(Fields.CALLER))) && num(rec.get(Fields.CREATED)) >= since) roots.add(id);
		}
		List<Blob> out = new ArrayList<>();
		for (Map.Entry<String, Blob> e : ids.entrySet()) {
			String id = e.getKey();
			if (id.equals(chatJob)) continue;
			for (String at = id; at != null; at = parent.get(at)) {
				if (roots.contains(at)) {
					out.add(e.getValue());
					break;
				}
				if (out.size() > 100_000) break;
			}
		}
		return out;
	}

	/** Suspends the agent, which cancels its turn and backend jobs, then resumes it so the next message goes through. */
	private static boolean interrupt(Engine engine, RequestContext me, String agentId) {
		try {
			engine.jobs().invokeInternal(OP_SUSPEND, Maps.of(Fields.AGENT_ID, agentId, "reason", REASON), me)
				.get(WAIT_SECONDS, TimeUnit.SECONDS);
		} catch (Exception e) {
			log.info("Could not interrupt {}'s turn: {}", agentId, e.toString());
			return false;
		} finally {
			try {
				engine.jobs().invokeInternal(OP_RESUME, Maps.of(Fields.AGENT_ID, agentId), me).get(WAIT_SECONDS, TimeUnit.SECONDS);
			} catch (Exception e) {
				log.warn("Could not resume {} after stopping its turn: {}", agentId, e.toString());
			}
		}
		return true;
	}

	/** The Claude Code session a job runs in, or null when it is not a Claude Code run. */
	static String claudeSession(AMap<AString, ACell> rec) {
		String op = str(rec.get(Fields.OP));
		if (op == null || !op.contains("claudecode")) return null;
		String s = str(RT.getIn(rec.get(Fields.INPUT), "session"));
		if (s == null) s = str(RT.getIn(rec.get(Fields.OUTPUT), "session"));
		return s;
	}

	private static boolean stopClaude(Engine engine, RequestContext me, String session) {
		try {
			ACell out = engine.jobs().invokeInternal(OP_CLAUDE_STOP, Maps.of("session", session), me)
				.get(WAIT_SECONDS, TimeUnit.SECONDS);
			return !convex.core.data.prim.CVMBool.FALSE.equals(RT.getIn(out, "stopped"));
		} catch (Exception e) {
			log.info("Could not stop Claude Code session {}: {}", session, e.toString());
			return false;
		}
	}

	private static AMap<AString, ACell> record(Index<Blob, ACell> jobs, Blob id) {
		ACell v = (jobs == null) ? null : jobs.get(id);
		return RT.ensureMap(v);
	}

	private static String str(ACell c) {
		return (c instanceof AString s) ? s.toString() : null;
	}

	private static long num(ACell c) {
		return (c instanceof convex.core.data.prim.CVMLong l) ? l.longValue() : 0;
	}
}
