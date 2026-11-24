package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;

/**
 * Pasted keys and job titles: a key-shaped string is found (and named as the
 * secret it would be stored under) while ordinary prose is left alone; a
 * job's title comes from its operation and agent, never from the words it was
 * handed; an outside tool's message is said to come via its token.
 */
class KeyShapesAndTitlesTest {

	@Test
	void keyShapedTextIsFoundAndProseIsNot() {
		KeyShapes.Found a = KeyShapes.find("here it is: sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWx please use it");
		assertNotNull(a);
		assertEquals("ANTHROPIC_API_KEY", a.secretName());
		assertFalse(a.masked().contains("AbCdEfGhIj"), "said back masked");
		assertEquals("OPENAI_API_KEY", KeyShapes.find("sk-proj-abcdefghijklmnopqrstuvwxyz012345").secretName());
		assertEquals("AWS_ACCESS_KEY_ID", KeyShapes.find("AKIAIOSFODNN7EXAMPLE").secretName());
		assertEquals("GITHUB_TOKEN", KeyShapes.find("ghp_" + "a".repeat(36)).secretName());
		assertEquals("PRIVATE_KEY", KeyShapes.find("-----BEGIN OPENSSH PRIVATE KEY-----\nabc").secretName());
		for (String prose : new String[] { "Ask the sk-team about it", "risk-assessment for the desk-top move",
			"AKIA is not a key", "Please summarise the ghp report", "" }) {
			assertNull(KeyShapes.find(prose), prose);
		}
		assertFalse(KeyShapes.mask("key sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWx").contains("AbCdEf"));
	}

	@Test
	void aJobsTitleIsItsOperationAndAgentNeverItsWords() {
		convex.core.data.AMap<convex.core.data.AString, convex.core.data.ACell> record = Maps.of(Strings.create("op"), Strings.create("v/ops/agent/request"), Strings.create("name"),
			Strings.create("Request Agent Task"), Strings.create("agentId"), Strings.create("Tau"), Strings.create("status"),
			Strings.create("COMPLETE"), Strings.create("input"),
			Maps.of("agentId", "Tau", "input", "use my key sk-ant-api03-AbCdEfGhIjKlMnOpQrStUvWx to call it"));
		JobList.Job job = JobList.project(Blob.fromHex("01"), record, "did:key:z6Mk:u:sam");
		assertEquals("Request Agent Task · Tau", job.title());
		assertFalse(job.title().contains("sk-ant"), "the handed words are not the title");
		assertNotNull(job.purpose(), "still there for the detail view, which masks keys");
		convex.core.data.AMap<convex.core.data.AString, convex.core.data.ACell> plain = Maps.of(Strings.create("op"), Strings.create("v/ops/http/get"), Strings.create("status"),
			Strings.create("COMPLETE"));
		assertEquals("get", JobList.project(Blob.fromHex("02"), plain, "did:key:z6Mk:u:sam").title());
	}

	@Test
	void anOutsideToolsMessageComesViaItsToken() {
		String task = "[Tasks assigned to you]\n- Task 0x01 (from: your owner via token (access token x/token/claude-code, "
			+ "held by someone the venue does not know)): hello";
		assertEquals("claude-code", SessionHistory.origin(Strings.create(task), task));
		assertNull(SessionHistory.origin(Strings.create("typed here"), "typed here"));
	}
}
