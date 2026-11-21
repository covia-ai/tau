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

/** The jobs operation: a user's jobs over a time range, newest first, summarised, under the right to read them. */
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
	void listsTheUsersJobsNewestFirstWithoutInputsOrResults() {
		AMap<AString, ACell> page = list(RequestContext.of(user), Maps.empty());
		assertEquals(made.reversed(), ids(page), "every job, newest first");
		assertEquals(made.size(), num(page, "inRange"));
		assertNull(page.get(JobsOperation.K_NEXT), "no more");
		AMap<AString, ACell> failure = RT.ensureMap(jobs(page).get(0));
		assertEquals(Strings.create("FAILED"), failure.get(Fields.STATUS));
		assertEquals(user, failure.get(JobsOperation.K_CALLER));
		assertNotNull(failure.get(Strings.create("error")), "says why it failed");
		assertFalse(page.toString().contains(MARKER), "no input or result is shown");
		assertNull(failure.get(Fields.INPUT));
	}

	@Test
	void aTimeRangeIsBoundedByCreation() {
		long third = created(made.get(2));
		AMap<AString, ACell> since = list(RequestContext.of(user), Maps.of("since", CVMLong.create(third)));
		assertTrue(ids(since).contains(made.get(2)), "since includes a job created then");
		for (ACell job : jobs(since)) {
			assertTrue(created(RT.ensureString(RT.getIn(job, "id")).toString()) >= third, "nothing created earlier");
		}
		assertEquals(jobs(since).count(), num(since, "inRange"));

		AMap<AString, ACell> until = list(RequestContext.of(user), Maps.of("until", CVMLong.create(third)));
		assertFalse(ids(until).contains(made.get(2)), "until excludes a job created then");
		assertTrue(ids(until).size() + ids(since).size() >= made.size(), "the two ranges cover every job");
	}

	@Test
	void statusAndCallerNarrowAndNextPagesBack() {
		RequestContext me = RequestContext.of(user);
		assertEquals(List.of(made.get(4)), ids(list(me, Maps.of("status", "FAILED"))));
		assertEquals(made.subList(0, 4).reversed(), ids(list(me, Maps.of("status", Vectors.of("COMPLETE", "CANCELLED")))));
		assertEquals(made.size(), ids(list(me, Maps.of("caller", user))).size(), "the user's own jobs");
		assertEquals(List.of(), ids(list(me, Maps.of("caller", user + ":g:someone"))), "none of an agent's");

		AMap<AString, ACell> first = list(me, Maps.of("limit", CVMLong.create(2)));
		assertEquals(made.subList(3, 5).reversed(), ids(first));
		AString next = RT.ensureString(first.get(JobsOperation.K_NEXT));
		assertEquals(made.get(3), next.toString(), "next is the oldest job on the page");
		assertEquals(made.subList(1, 3).reversed(), ids(list(me, Maps.of("limit", CVMLong.create(2), "before", next))));
	}

	@Test
	void badInputIsRefused() {
		RequestContext me = RequestContext.of(user);
		assertThrows(InvalidInputException.class, () -> list(me, Maps.of("since", "yesterday")));
		assertThrows(InvalidInputException.class, () -> list(me, Maps.of("limit", CVMLong.create(0))));
		assertThrows(InvalidInputException.class, () -> list(me, Maps.of("before", "not a job")));
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

	private static long num(AMap<AString, ACell> map, String key) {
		return ((CVMLong) RT.getIn(map, key)).longValue();
	}

	private static long created(String id) {
		return ((CVMLong) venue.jobs(user.toString()).getRecord(Job.parseID(id)).get(Fields.CREATED)).longValue();
	}
}
