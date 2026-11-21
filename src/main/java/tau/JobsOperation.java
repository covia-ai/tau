package tau;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.lattice.Namespace;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * The jobs operation ({@code v/ops/tau/jobs}): a page of the jobs of the user
 * the caller acts for, newest first, over a time range, each summarised
 * without its input or result; a failure's message, which may echo input, is
 * shortened.
 *
 * <p>It reads what a {@code covia} list of {@code j} reads, under the same
 * right, {@code crud/read} on the user's {@code j}, so it lends no authority:
 * an agent lists its own user's jobs, and a god lists a user's through its
 * bridge into that user's namespace. The time range is a seek in the job
 * index ({@link JobList#page}).
 */
final class JobsOperation {

	static final int DEFAULT_LIMIT = 50;
	static final int MAX_LIMIT = 200;
	/** A failure's message is cut here: enough to see what went wrong, not a dump. */
	static final int MAX_ERROR = 300;

	static final AString K_SINCE = Strings.intern("since");
	static final AString K_UNTIL = Strings.intern("until");
	static final AString K_BEFORE = Strings.intern("before");
	static final AString K_STATUS = Strings.intern("status");
	static final AString K_CALLER = Strings.intern("caller");
	static final AString K_LIMIT = Strings.intern("limit");
	static final AString K_JOBS = Strings.intern("jobs");
	static final AString K_IN_RANGE = Strings.intern("inRange");
	static final AString K_NEXT = Strings.intern("next");
	private static final AString K_ID = Strings.intern("id");
	private static final AString K_CREATED = Strings.intern("created");
	private static final AString K_UPDATED = Strings.intern("updated");
	private static final AString K_NAME = Strings.intern("name");
	private static final AString K_OPERATION = Strings.intern("operation");
	private static final AString K_AGENT = Strings.intern("agent");
	private static final AString K_SESSION = Strings.intern("session");
	private static final AString K_PARENT = Strings.intern("parent");
	private static final AString K_ERROR = Strings.intern("error");
	private static final AString K_TOKENS = Strings.intern("tokens");

	private final Engine engine;

	JobsOperation(Engine engine) {
		this.engine = engine;
	}

	/** The page {@code input} asks for, of the jobs of the user {@code ctx} acts for. */
	AMap<AString, ACell> invoke(RequestContext ctx, ACell input) {
		AString user = (ctx == null) ? null : ctx.getUserDID();
		if (user == null) throw new AuthException("Listing jobs needs a user to act for");
		engine.requireAuthority(ctx, Strings.create(user + "/" + Namespace.J), Capability.CRUD_READ);
		long since = time(input, K_SINCE);
		long until = time(input, K_UNTIL);
		Blob before = before(input);
		Set<String> statuses = statuses(input);
		AString caller = RT.ensureString(RT.getIn(input, K_CALLER));
		int limit = limit(input);
		Index<Blob, ACell> index = engine.getVenueState().users().at(user).jobs().getAll();
		JobList.Page page = JobList.page(index, user.toString(), since, until, before, statuses,
			(caller == null || caller.count() == 0) ? null : caller.toString(), limit);
		AVector<ACell> jobs = Vectors.empty();
		for (JobList.Job job : page.jobs()) jobs = jobs.conj(summary(job, user));
		AMap<AString, ACell> out = Maps.of(K_JOBS, jobs, K_IN_RANGE, CVMLong.create(page.inRange()));
		return (page.next() != null) ? out.assoc(K_NEXT, Strings.create(page.next())) : out;
	}

	/** What the operation shows of a job: who ran what, when, and how it ended. A job with no caller is the user's own. */
	private static AMap<AString, ACell> summary(JobList.Job job, AString user) {
		AMap<AString, ACell> s = Maps.of(K_ID, Strings.create(job.id()),
			K_CREATED, Strings.create(Instant.ofEpochMilli(job.created()).toString()),
			K_NAME, Strings.create(job.name()));
		if (job.updated() > 0) s = s.assoc(K_UPDATED, Strings.create(Instant.ofEpochMilli(job.updated()).toString()));
		s = with(s, K_OPERATION, job.operation());
		s = with(s, K_STATUS, job.status());
		s = s.assoc(K_CALLER, (job.caller() != null) ? Strings.create(job.caller()) : user);
		s = with(s, K_AGENT, job.agent());
		s = with(s, K_SESSION, job.session());
		s = with(s, K_PARENT, job.parent());
		String error = job.error();
		if (error != null && error.length() > MAX_ERROR) error = error.substring(0, MAX_ERROR) + "…";
		s = with(s, K_ERROR, error);
		return (job.tokens() > 0) ? s.assoc(K_TOKENS, CVMLong.create(job.tokens())) : s;
	}

	private static AMap<AString, ACell> with(AMap<AString, ACell> s, AString key, String value) {
		return (value == null) ? s : s.assoc(key, Strings.create(value));
	}

	/** A time as epoch milliseconds or ISO-8601; 0 when absent. */
	private static long time(ACell input, AString key) {
		ACell v = RT.getIn(input, key);
		if (v == null) return 0;
		if (v instanceof CVMLong l) {
			if (l.longValue() < 0) throw new InvalidInputException(key + " must not be negative");
			return l.longValue();
		}
		AString s = RT.ensureString(v);
		if (s == null) throw new InvalidInputException(key + " must be an ISO-8601 time or epoch milliseconds");
		try {
			return Instant.parse(s.toString().strip()).toEpochMilli();
		} catch (DateTimeParseException e) {
			throw new InvalidInputException(key + " is not an ISO-8601 time, such as 2026-11-21T09:00:00Z: " + s, e);
		}
	}

	private static Blob before(ACell input) {
		AString s = RT.ensureString(RT.getIn(input, K_BEFORE));
		if (s == null) return null;
		Blob id = covia.grid.Job.parseID(s.toString());
		if (id == null) throw new InvalidInputException("before is not a job id: " + s);
		return id;
	}

	/** One status or several; empty for any. */
	private static Set<String> statuses(ACell input) {
		ACell v = RT.getIn(input, K_STATUS);
		Set<String> out = new HashSet<>();
		if (v == null) return out;
		if (v instanceof AString s) {
			out.add(s.toString());
		} else if (v instanceof AVector<?> list) {
			for (long i = 0; i < list.count(); i++) {
				AString s = RT.ensureString(list.get(i));
				if (s == null) throw new InvalidInputException("status must be a status or a list of them");
				out.add(s.toString());
			}
		} else {
			throw new InvalidInputException("status must be a status or a list of them");
		}
		return out;
	}

	private static int limit(ACell input) {
		ACell v = RT.getIn(input, K_LIMIT);
		if (v == null) return DEFAULT_LIMIT;
		if (!(v instanceof CVMLong l) || l.longValue() < 1 || l.longValue() > MAX_LIMIT) {
			throw new InvalidInputException("limit must be from 1 to " + MAX_LIMIT);
		}
		return (int) l.longValue();
	}
}
