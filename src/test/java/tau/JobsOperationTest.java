package tau;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.token.TokenAdapter;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.RequestContext;

/** The jobs operation: a user's jobs from a start time or the latest, summarised, under the right to read them. */
class JobsOperationTest {

	private static final long TIMEOUT_SECONDS = 30;
	private static final String MARKER = "a value no listing shows";
	/** The last write's value: too large for the budget, so shown only as inspect renders it. */
	private static final String LARGE = MARKER + " " + "x".repeat(4000);

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;
	private static AString user;
	/** The user's jobs, oldest first: four writes, the last of a large value, then a failure. */
	private static final List<String> made = new ArrayList<>();

	@BeforeAll
	static void boot() throws Exception {
		venue = shared.venue();
		user = Strings.create(Identity.of("jobs-op").userDID(venue.did()));
		venue.engine().getVenueState().users().ensure(user.toString());
		Venue client = venue.clientAs(user.toString());
		for (int i = 0; i < 4; i++) {
			Job job = client.invoke("v/ops/covia/write", Maps.of("path", "w/jobs-op/" + i, "value", (i == 3) ? LARGE : MARKER))
				.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			made.add(job.getID().toHexString());
		}
		Job failed = client.invoke("v/ops/agent/info", Maps.of(Fields.AGENT_ID, "no-such-agent"))
			.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertThrows(Exception.class, () -> failed.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		made.add(failed.getID().toHexString());
	}

	@Test
	void withoutAStartTheLatestJobsComeOldestFirstWithoutInputsOrResults() {
		AMap<AString, ACell> all = list(RequestContext.of(user), Maps.empty());
		assertEquals(made, ids(all), "every job, oldest first");
		assertEquals(made.subList(3, 5), ids(list(RequestContext.of(user), Maps.of("limit", CVMLong.create(2)))),
			"the latest two");
		AMap<AString, ACell> failure = RT.ensureMap(jobs(all).get(4));
		assertEquals(Strings.create("FAILED"), failure.get(Fields.STATUS));
		assertEquals(user, failure.get(Fields.CALLER), "a job started in person is the user's");
		assertNotNull(failure.get(Fields.ERROR), "says why it failed");
		AMap<AString, ACell> write = RT.ensureMap(jobs(all).get(0));
		assertEquals(Strings.create("v/ops/covia/write"), write.get(Fields.OP), "the record's own names");
		assertNotNull(write.get(Fields.ADAPTER));
		OffsetDateTime created = OffsetDateTime.parse(RT.ensureString(write.get(Fields.CREATED)).toString());
		assertEquals(ZoneId.systemDefault().getRules().getOffset(created.toInstant()), created.getOffset(),
			"a local time");
		assertFalse(all.toString().contains(MARKER), "no input or result unless asked for");
		assertNull(failure.get(Fields.INPUT));
	}

	@Test
	void fieldsAddPartsOfTheRecordAndLargeOnesAreShortened() {
		AVector<ACell> shown = jobs(list(RequestContext.of(user), Maps.of("fields", Vectors.of("input", "input/path"))));
		AMap<AString, ACell> first = RT.ensureMap(shown.get(0));
		assertEquals(Strings.create(MARKER), RT.getIn(first, "input", "value"), "a small input whole");
		assertEquals(Strings.create("w/jobs-op/0"), first.get(Strings.create("input/path")), "a subpath");
		assertNotNull(first.get(Fields.STATUS), "with the default fields");
		ACell large = RT.ensureMap(shown.get(3)).get(Fields.INPUT);
		assertInstanceOf(AString.class, large, "a large input rendered as inspect renders it");
		assertTrue(((AString) large).count() < LARGE.length(), "and shortened");
		assertEquals(shown, jobs(list(RequestContext.of(user), Maps.of("fields", "input, input/path"))),
			"or one comma-separated string");
	}

	@Test
	void aStartListsTheFirstJobsCreatedFromThen() {
		long third = created(made.get(2));
		List<String> from = ids(list(RequestContext.of(user), Maps.of("start", CVMLong.create(third))));
		assertTrue(from.contains(made.get(2)), "a job created at the start");
		for (String id : from) assertTrue(created(id) >= third, "nothing created earlier");
		assertEquals(made.subList(made.size() - from.size(), made.size()), from, "and every one after, in order");

		List<String> two = ids(list(RequestContext.of(user), Maps.of("start", CVMLong.create(created(made.get(0))),
			"limit", CVMLong.create(2))));
		assertEquals(2, two.size());
		assertEquals(made.get(0), two.get(0), "the first jobs from the start, not the latest");
		assertEquals(from, ids(list(RequestContext.of(user),
			Maps.of("start", Strings.create(Instant.ofEpochMilli(third).toString())))), "an ISO-8601 start");
		String local = LocalDateTime.ofInstant(Instant.ofEpochMilli(third), ZoneId.systemDefault()).toString();
		assertEquals(from, ids(list(RequestContext.of(user), Maps.of("start", local))), "a local time, without an offset");
	}

	@Test
	void statusAndCallerNarrow() {
		RequestContext me = RequestContext.of(user);
		assertEquals(List.of(made.get(4)), ids(list(me, Maps.of("status", "FAILED"))));
		assertEquals(made.subList(0, 4), ids(list(me, Maps.of("status", Vectors.of("COMPLETE", "CANCELLED")))));
		assertEquals(made, ids(list(me, Maps.of("caller", user))), "the user's own jobs");
		assertEquals(List.of(), ids(list(me, Maps.of("caller", user + ":g:someone"))), "none of an agent's");
	}

	@Test
	void badInputIsRefused() {
		RequestContext me = RequestContext.of(user);
		assertThrows(InvalidInputException.class, () -> list(me, Maps.of("start", "yesterday")));
		assertThrows(InvalidInputException.class, () -> list(me, Maps.of("limit", CVMLong.create(0))));
		assertThrows(InvalidInputException.class, () -> list(me, Maps.of("status", CVMLong.create(1))));
		assertThrows(InvalidInputException.class, () -> list(me, Maps.of("fields", "input,,output")));
	}

	@Test
	void aTokenWithoutTheRightToReadJobsIsRefused() {
		// A user of its own, so issuing the token leaves nothing in the listed user's jobs
		AString issuer = Strings.create(Identity.of("jobs-op-issuer").userDID(venue.did()));
		venue.engine().getVenueState().users().ensure(issuer.toString());
		Tokens.Scope collaborate = TokenPreset.COLLABORATE.scope("Tau");
		String bearer = new Tokens(venue.engine(), issuer).create("jobs-op-collaborator", collaborate).bearer();
		TokenAdapter tokens = (TokenAdapter) venue.engine().getAdapter(TokenAdapter.NAME);
		RequestContext narrow = venue.engine().tokenContext(tokens.tokens().lookup(bearer));
		assertThrows(AuthException.class, () -> list(narrow, Maps.empty()));
	}

	private static AMap<AString, ACell> list(RequestContext ctx, AMap<AString, ACell> input) {
		return new JobsOperation(venue.engine()).invoke(ctx, input);
	}

	private static AVector<ACell> jobs(AMap<AString, ACell> page) {
		return RT.ensureVector(page.get(JobsOperation.K_JOBS));
	}

	private static List<String> ids(AMap<AString, ACell> page) {
		List<String> out = new ArrayList<>();
		for (ACell job : jobs(page)) out.add(RT.ensureString(RT.getIn(job, "id")).toString());
		return out;
	}

	/** When job {@code id} was created by its id, which is what a start seeks on. */
	private static long created(String id) {
		return Job.idTime(Job.parseID(id));
	}
}
