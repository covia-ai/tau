package tau;

import static org.junit.jupiter.api.Assertions.*;

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

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;
	private static AString user;
	/** The user's jobs, oldest first: four writes, then a failure. */
	private static final List<String> made = new ArrayList<>();

	@BeforeAll
	static void boot() throws Exception {
		venue = shared.venue();
		user = Strings.create(Identity.of("jobs-op").userDID(venue.did()));
		venue.engine().getVenueState().users().ensure(user.toString());
		Venue client = venue.clientAs(user.toString());
		for (int i = 0; i < 4; i++) {
			Job job = client.invoke("v/ops/covia/write", Maps.of("path", "w/jobs-op/" + i, "value", MARKER))
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
		assertEquals(user, failure.get(JobsOperation.K_CALLER), "a job started in person is the user's");
		assertNotNull(failure.get(Strings.create("error")), "says why it failed");
		assertFalse(all.toString().contains(MARKER), "no input or result is shown");
		assertNull(failure.get(Fields.INPUT));
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
			Maps.of("start", Strings.create(java.time.Instant.ofEpochMilli(third).toString())))), "an ISO-8601 start");
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
