package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * What the Rooms screen stands on: reading {@code x/room} gives the owner, in
 * person, every room on the venue, so no list operation is needed; a wait made
 * as an ordinary invocation (an outside agent over MCP, or an agent's wait past
 * its tool-call limit) is a job the owner sees in Jobs and can cancel there,
 * while the screen's own wait, made in process, leaves no job.
 */
class RoomListingAndWaitsTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@Test
	void theRoomsViewShowsTheOwnerEveryRoom() throws Exception {
		AString me = user("rooms-list-me");
		AString other = user("rooms-list-other");
		internal("create", Maps.of("room", "list-mine"), RequestContext.of(me));
		internal("create", Maps.of("room", "list-theirs"), RequestContext.of(other));

		ACell view = shared.venue().resolve(me.toString(), "x/room");
		assertNotNull(view, "the owner reads the rooms view");
		assertNotNull(RT.getIn(view, "list-mine"), "their own room");
		assertNotNull(RT.getIn(view, "list-theirs"), "and another user's private room: the view is the venue's");
		assertNotNull(RT.getIn(view, "list-theirs", "owner"), "each with its owner, so the screen can tell whose it is");
	}

	@Test
	void aRecordedWaitIsAJobTheOwnerCanCancelAndTheScreensOwnWaitIsNot() throws Exception {
		AString me = user("rooms-wait-me");
		RequestContext ctx = RequestContext.of(me);
		internal("create", Maps.of("room", "wait-jobs"), ctx);

		Job recorded = engine().jobs().invokeOperation("v/ops/room/wait",
			Maps.of("room", "wait-jobs", "after", 0L, "timeout", 60_000L), ctx);
		assertTrue(jobs(ctx).containsKey(recorded.getID()), "a recorded wait shows in Jobs");
		engine().jobs().cancelJob(recorded.getID(), ctx);
		assertEquals(Status.CANCELLED, RT.getIn(engine().jobs().getJobData(recorded.getID(), ctx), "status"),
			"and Jobs cancels it");

		long before = jobs(ctx).count();
		// Waiting with no number: the room has no posts yet, and its first is what the screen wants.
		CompletableFuture<ACell> own = engine().jobs().invokeInternal("v/ops/room/wait",
			Maps.of("room", "wait-jobs", "timeout", 60_000L), ctx);
		assertEquals(before, jobs(ctx).count(), "the screen's own wait leaves no job");
		internal("post", Maps.of("room", "wait-jobs", "text", "wakes it"), ctx);
		assertFalse(RT.ensureVector(RT.getIn(own.get(20, TimeUnit.SECONDS), "posts")).isEmpty(), "and a post answers it");
	}

	private static Index<?, ?> jobs(RequestContext ctx) {
		return engine().jobs().getJobs(ctx);
	}

	private static ACell internal(String op, ACell input, RequestContext ctx) throws Exception {
		return engine().jobs().invokeInternal("v/ops/room/" + op, input, ctx).get(20, TimeUnit.SECONDS);
	}

	private static Engine engine() {
		return shared.venue().engine();
	}

	private static AString user(String name) {
		AString did = Strings.create(Identity.of(name).userDID(shared.venue().did()));
		engine().getVenueState().users().ensure(did.toString());
		return did;
	}
}
