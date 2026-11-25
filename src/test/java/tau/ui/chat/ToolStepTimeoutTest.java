package tau.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import covia.adapter.agent.AbstractLLMAdapter;
import tau.SessionHistory;
import tau.ui.LAF;

/**
 * A tool step reads as Covia reports it: one still running as a job past its
 * time limit is Running, since its outcome is still to come, in either of
 * Covia's wordings; a call given up at its limit has failed. Headless.
 * Isolated: it installs a look and feel, which classes running alongside
 * must not see change under them.
 */
@Isolated
class ToolStepTimeoutTest {

	private static String badge(String result, boolean error) {
		return ExpandableActivity.statusBadge(new SessionHistory.Step(true, "covia_read", result, error, "{}")).getText();
	}

	@Test
	void eachStepSaysWhatHappened() {
		LAF.init(LAF.TAU_DARK, null, null, false, null);
		for (String template : new String[] { AbstractLLMAdapter.TOOL_STILL_RUNNING, AbstractLLMAdapter.TOOL_STILL_RUNNING_UNTRACKED }) {
			assertEquals("Running", badge(String.format(template, "v/ops/http/get", 30000L, "0x01ab"), true));
		}
		assertEquals("Failed", badge("Error: tool call timed out after 30000ms", true));
		assertEquals("Failed", badge("Error: no such path", true));
		assertEquals("Done", badge("{\"ok\":true}", false));
	}
}
