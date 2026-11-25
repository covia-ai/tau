package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.RequestContext;

/**
 * The Moltbook adapter on the shared venue, which points at Moltbook itself, so
 * nothing here calls an operation: every operation is installed, and the
 * record kept for a person is read without writing and goes with them. What
 * the operations do, including what they refuse before any request, is tested
 * against a stand-in for Moltbook ({@link MoltbookStubTest}). Each test is its
 * own user, so they run side by side.
 */
@Execution(ExecutionMode.CONCURRENT)
class MoltbookAdapterTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static EmbeddedVenue venue;

	@BeforeAll
	static void boot() {
		venue = shared.venue();
	}

	@Test
	void everyOperationIsInstalled() {
		RequestContext ctx = RequestContext.of(Strings.create(venue.did()));
		for (String op : MoltbookAdapter.OPS) {
			ACell asset = venue.engine().resolvePath(Strings.create("v/ops/moltbook/" + op), ctx);
			assertNotNull(asset, "operation present: " + op);
			assertTrue(RT.getIn(asset, Fields.OPERATION) instanceof AMap,
				"operation metadata is an operation: " + op);
		}
	}

	@Test
	void readingWithoutARecordWritesNothing() {
		AString did = Strings.create(Identity.of("moltbook-none").userDID(venue.did()));
		covia.venue.User user = venue.engine().getVenueState().users().ensure(did);
		ACell before = user.get();
		assertNull(venue.moltbook().record(did));
		assertEquals(before, venue.engine().getVenueState().users().user(did).get(), "the person's record is untouched");
		covia.venue.AdapterState state = venue.engine().adapterState("moltbook");
		assertNull(state.read(state.userPath(did, "")), "and nothing is kept for them");
	}

	@Test
	void aDeletedPersonsRecordGoesWithThem() {
		AString did = Strings.create(Identity.of("moltbook-deleted").userDID(venue.did()));
		venue.engine().getVenueState().users().ensure(did);
		venue.moltbook().saveRecord(did, Moltbook.record("Molty", null, null));
		covia.venue.Engine engine = venue.engine();
		engine.deleteUser(engine.venueContext(), did);
		covia.venue.AdapterState state = engine.adapterState("moltbook");
		assertNull(state.read(state.userPath(did, "")));
	}
}
