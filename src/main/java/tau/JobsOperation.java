package tau;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Cells;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.data.util.CellExplorer;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.grid.Attribution;
import covia.lattice.CoviaPath;
import covia.lattice.Namespace;
import covia.lattice.ValuePath;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * The jobs operation ({@code v/ops/tau/jobs}): the jobs of the user the caller
 * acts for, from a start time or the latest, oldest first, each as a view of
 * its record for an agent to read.
 *
 * <p>The view keeps the record's own names. By default it shows who ran what,
 * when and how it ended; {@code fields} adds other parts of the record, such
 * as {@code input} or {@code tokens/total}, as {@code covia} list's
 * {@code fields} does. Times are local ISO-8601 with their offset. A value too
 * large for its budget, such as a long error, is shown as inspect shows it,
 * through {@link CellExplorer}.
 *
 * <p>It reads what a {@code covia} list of {@code j} reads, under the same
 * right, {@code crud/read} on the user's {@code j}, so it lends no authority:
 * an agent lists its own user's jobs, and a god lists a user's through its
 * bridge into that user's namespace. The start is a seek in the job index
 * ({@link JobList#from}). Each request stands alone: to read on, start again
 * from the last job's time.
 */
final class JobsOperation {

	static final int DEFAULT_LIMIT = 50;
	static final int MAX_LIMIT = 200;
	/** At most this many extra fields, as {@code covia} list allows. */
	static final int MAX_FIELDS = 16;
	/** Each value shown whole up to this storage size, else rendered within it; inspect's default budget. */
	static final int BUDGET = 500;

	static final AString K_START = Strings.intern("start");
	static final AString K_STATUS = Strings.intern("status");
	static final AString K_CALLER = Strings.intern("caller");
	static final AString K_LIMIT = Strings.intern("limit");
	static final AString K_FIELDS = Strings.intern("fields");
	static final AString K_JOBS = Strings.intern("jobs");

	/** Times as the agent's own clock reads them: local, with the offset, so never ambiguous. */
	private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
	/** A start as an agent would write it: a date, a local time, or a time with its offset. */
	private static final DateTimeFormatter START = new DateTimeFormatterBuilder()
		.append(DateTimeFormatter.ISO_LOCAL_DATE)
		.optionalStart().appendLiteral('T').append(DateTimeFormatter.ISO_LOCAL_TIME).optionalEnd()
		.optionalStart().appendOffsetId().optionalEnd()
		.toFormatter();

	private final Engine engine;
	/** The zone local times are in: times shown, and a start given without an offset. */
	private final ZoneId zone;

	JobsOperation(Engine engine, ZoneId zone) {
		this.engine = engine;
		this.zone = zone;
	}

	/** The jobs {@code input} asks for, of the user {@code ctx} acts for. */
	AMap<AString, ACell> invoke(RequestContext ctx, ACell input) {
		AString user = (ctx == null) ? null : ctx.getUserDID();
		if (user == null) throw new AuthException("Listing jobs needs a user to act for");
		engine.requireAuthority(ctx, Strings.create(user + "/" + Namespace.J), Capability.CRUD_READ);
		long start = start(input);
		Set<String> statuses = statuses(input);
		AString caller = RT.ensureString(RT.getIn(input, K_CALLER));
		int limit = limit(input);
		List<AString> fields = fields(input);
		Index<Blob, ACell> index = engine.getVenueState().users().at(user).jobs().getAll();
		AVector<ACell> jobs = Vectors.empty();
		for (JobList.Job job : JobList.from(index, user.toString(), start, statuses,
				(caller == null || caller.count() == 0) ? null : caller.toString(), limit)) {
			jobs = jobs.conj(view(job, user, fields));
		}
		return Maps.of(K_JOBS, jobs);
	}

	/**
	 * What the operation shows of a job: the record's own fields that say who
	 * ran what, when and how it ended, then each of {@code fields}. A job with
	 * no caller is named in full as the user's own, as job status names it.
	 */
	private AMap<AString, ACell> view(JobList.Job job, AString user, List<AString> fields) {
		AMap<AString, ACell> record = Attribution.inFull(job.record(), user);
		AMap<AString, ACell> v = Maps.of(Fields.ID, Strings.create(job.id()));
		v = time(v, Fields.CREATED, record);
		v = time(v, Fields.UPDATED, record);
		for (AString key : new AString[] { Fields.NAME, Fields.OP, Fields.ADAPTER, Fields.STATUS, Fields.CALLER,
				Fields.AGENT_ID }) {
			v = shown(v, key, record.get(key));
		}
		v = id(v, Fields.SESSION_ID, record);
		v = id(v, Fields.PARENT, record);
		v = shown(v, Fields.ERROR, record.get(Fields.ERROR));
		v = shown(v, Fields.ERROR_CODE, record.get(Fields.ERROR_CODE));
		for (AString field : fields) {
			v = shown(v, field, ValuePath.get(record, CoviaPath.parse(field.toString())));
		}
		return v;
	}

	/** {@code value} under {@code key}: whole when it fits the budget, else as inspect renders it; nothing when absent. */
	private static AMap<AString, ACell> shown(AMap<AString, ACell> v, AString key, ACell value) {
		if (value == null) return v;
		if (Cells.storageSize(value) <= BUDGET) return v.assoc(key, value);
		return v.assoc(key, new CellExplorer(BUDGET).explore(value));
	}

	private AMap<AString, ACell> time(AMap<AString, ACell> v, AString key, AMap<AString, ACell> record) {
		if (!(record.get(key) instanceof CVMLong ms) || ms.longValue() <= 0) return v;
		return v.assoc(key, Strings.create(TIME.format(Instant.ofEpochMilli(ms.longValue()).atZone(zone))));
	}

	/** An id the record may hold as bytes, as text an agent can pass back. */
	private static AMap<AString, ACell> id(AMap<AString, ACell> v, AString key, AMap<AString, ACell> record) {
		String text = Ids.text(record.get(key));
		return (text == null) ? v : v.assoc(key, Strings.create(text));
	}

	/**
	 * The start as epoch milliseconds; 0 when absent. A time without an offset
	 * is local, and a date alone is its local midnight.
	 */
	private long start(ACell input) {
		ACell v = RT.getIn(input, K_START);
		if (v == null) return 0;
		if (v instanceof CVMLong l) {
			if (l.longValue() < 0) throw new InvalidInputException("start must not be negative");
			return l.longValue();
		}
		AString s = RT.ensureString(v);
		if (s == null) throw new InvalidInputException("start must be an ISO-8601 time or epoch milliseconds");
		TemporalAccessor t;
		try {
			t = START.parseBest(s.toString().strip(), OffsetDateTime::from, LocalDateTime::from, LocalDate::from);
		} catch (DateTimeParseException e) {
			throw new InvalidInputException("start is not an ISO-8601 time, such as 2026-11-21T09:00: " + s, e);
		}
		Instant at = switch (t) {
			case OffsetDateTime o -> o.toInstant();
			case LocalDateTime l -> l.atZone(zone).toInstant();
			case LocalDate d -> d.atStartOfDay(zone).toInstant();
			default -> throw new InvalidInputException("start is not an ISO-8601 time: " + s);
		};
		return at.toEpochMilli();
	}

	/** One status or several; empty for any. */
	private static Set<String> statuses(ACell input) {
		Set<String> out = new HashSet<>();
		for (AString s : strings(RT.getIn(input, K_STATUS), "status must be a status or a list of them")) {
			out.add(s.toString());
		}
		return out;
	}

	/**
	 * The extra parts of the record to show: a list of subpaths or one
	 * comma-separated string, as {@code covia} list takes. A copy of its
	 * parsing, which Covia keeps private (covia#725).
	 */
	private static List<AString> fields(ACell input) {
		ACell v = RT.getIn(input, K_FIELDS);
		List<AString> out = new ArrayList<>();
		String refused = "fields must be a list of subpaths of the job record, or one comma-separated string";
		if (v instanceof AString s) {
			for (String part : s.toString().split(",")) {
				if (part.isBlank()) throw new InvalidInputException("fields contains a blank field name");
				out.add(Strings.create(part.strip()));
			}
		} else {
			out.addAll(strings(v, refused));
		}
		if (out.size() > MAX_FIELDS) {
			throw new InvalidInputException("fields names " + out.size() + " subpaths; the maximum is " + MAX_FIELDS);
		}
		return out;
	}

	/** A string or a list of strings; empty when absent. */
	private static List<AString> strings(ACell v, String refused) {
		List<AString> out = new ArrayList<>();
		if (v == null) return out;
		if (v instanceof AString s) {
			out.add(s);
		} else if (v instanceof AVector<?> list) {
			for (long i = 0; i < list.count(); i++) {
				AString s = RT.ensureString(list.get(i));
				if (s == null || s.count() == 0) throw new InvalidInputException(refused);
				out.add(s);
			}
		} else {
			throw new InvalidInputException(refused);
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
