package tau;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import covia.grid.Job;
import covia.grid.Status;

/**
 * A HITL request as an inbox shows it, followed through the job that opened
 * it rather than by reading the inbox until it appears.
 */
final class InboxRequests {

	private InboxRequests() {
	}

	/**
	 * The request {@code job} opened in {@code inboxDID}'s inbox. Covia opens
	 * the request in the transaction that parks its job awaiting input, so the
	 * request is there once the job says it awaits input.
	 */
	static Inbox.Request await(EmbeddedVenue venue, String inboxDID, Job job, long timeoutSeconds) throws Exception {
		awaitingInput(job).get(timeoutSeconds, TimeUnit.SECONDS);
		String id = job.getID().toHexString();
		return Inbox.parse(venue.inbox(inboxDID), inboxDID).stream().filter(r -> id.equals(r.id())).findFirst()
			.orElseThrow(() -> new AssertionError("request " + id + " is not in the inbox, though its job awaits input"));
	}

	/**
	 * Completes once {@code job} awaits input, and fails if it finishes first.
	 * The listener is in place before the status is read, so a change made
	 * before the subscription is read and one made after it is heard.
	 */
	private static CompletableFuture<Job> awaitingInput(Job job) {
		CompletableFuture<Job> parked = new CompletableFuture<>();
		Consumer<Job> listener = changed -> check(changed, parked);
		job.subscribe(listener);
		parked.whenComplete((done, failure) -> job.unsubscribe(listener));
		check(job, parked);
		return parked;
	}

	private static void check(Job job, CompletableFuture<Job> parked) {
		if (Status.INPUT_REQUIRED.equals(job.getStatus())) {
			parked.complete(job);
		} else if (job.isFinished()) {
			parked.completeExceptionally(new AssertionError("job " + job.getID().toHexString() + " ended "
				+ job.getStatus() + " without awaiting input: " + job.getErrorMessage()));
		}
	}
}
