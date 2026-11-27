package tau;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.prim.CVMBool;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Status;

/** How the change gate reads the owner's answer from the approval request's job. */
class ChangeGateAnswerTest {

	private static final ACell YES = Maps.of("answers", Maps.of("ok", CVMBool.TRUE));

	/** A request whose record says it is finished before its answer is published, as Covia allows. */
	private static Job publishedLate(AString status, ACell output, long afterMillis) {
		AMap<AString, ACell> record = Maps.of(Fields.STATUS, status);
		if (output != null) record = record.assoc(Fields.OUTPUT, output);
		CompletableFuture<ACell> published = (afterMillis < 0) ? new CompletableFuture<>()
			: CompletableFuture.supplyAsync(() -> output,
				CompletableFuture.delayedExecutor(afterMillis, TimeUnit.MILLISECONDS));
		return new Job(record) {
			@Override
			public CompletableFuture<ACell> future() {
				return published;
			}
		};
	}

	@Test
	void anApprovalPublishedJustAfterItsRecordIsAYes() {
		assertEquals(Boolean.TRUE, ChangeGate.answer(publishedLate(Status.COMPLETE, YES, 200), 0),
			"an answer that lands at the edge of the wait is the owner's yes, never a no");
	}

	@Test
	void anApprovalNotYetReadableIsReadFromItsRecord() {
		assertEquals(Boolean.TRUE, ChangeGate.answer(publishedLate(Status.COMPLETE, YES, -1), 0));
		assertNull(ChangeGate.answer(publishedLate(Status.COMPLETE, null, -1), 0),
			"an answer that can't be read yet is waited on again, not taken as a no");
	}

	@Test
	void aRequestThatEndedWithoutAYesIsANo() {
		assertEquals(Boolean.FALSE, ChangeGate.answer(publishedLate(Status.FAILED, null, -1), 0));
		assertEquals(Boolean.FALSE, ChangeGate.answer(publishedLate(Status.COMPLETE,
			Maps.of("answers", Maps.of("ok", CVMBool.FALSE)), 0), 0));
	}

	@Test
	void anUnansweredRequestIsNoAnswerYet() {
		assertNull(ChangeGate.answer(publishedLate(Status.STARTED, null, -1), 0));
		assertNull(ChangeGate.answer(publishedLate(Status.STARTED, null, -1), 1));
	}
}
