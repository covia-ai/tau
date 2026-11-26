package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class KeyMaskTest {

	@Test
	void masksKeyLikeRuns() {
		String[] keys = {
			"sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789",
			"ghp_abcdefghijklmnopqrstuvwxyz0123",
			"AKIAABCDEFGHIJKLMNOP",
			"Zx9qL2mN7pR4tV8wY1bC3dF5gH6jK0sA",
		};
		for (String key : keys) {
			String masked = KeyMask.mask("use " + key + " please");
			assertFalse(masked.contains(key), masked);
			assertEquals("use " + KeyMask.MASK + " please", masked);
		}
	}

	@Test
	void leavesOrdinaryTextAlone() {
		String text = "Summarise the 2026-11-24 meeting notes for internationalisation work";
		assertEquals(text, KeyMask.mask(text));
		assertNull(KeyMask.mask(null));
	}

	@Test
	void jobTitleMasksItsPurpose() {
		String key = "sk-proj-abcdefghijklmnopqrstuvwxyz0123456789";
		JobList.Job job = new JobList.Job("1", 0, 0, "chat", "v/ops/chat", "COMPLETED",
				key + "\nmore", null, null, null, null, null, null, 0, null);
		assertFalse(job.title().contains(key));
	}
}
