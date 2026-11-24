package tau.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import covia.exception.AuthException;
import tau.ExpectedLogs;

/**
 * Failures mapped to what happened, what to try, and where the log line is:
 * the common ones each get their own kind, a refusal shows only the refused
 * ability and resource and never the caller's other rights (not in the
 * message, not in Details), and a key in the raw text is masked.
 */
class ProblemsTest {

	@Test
	void theCommonFailuresEachGetTheirOwnKind() {
		assertEquals(Problems.Kind.MODEL_AUTH, Problems.classify("HTTP 401: {\"error\":{\"type\":\"authentication_error\","
			+ "\"message\":\"invalid x-api-key\"}}").kind());
		assertEquals(Problems.Kind.QUOTA, Problems.classify("429 You exceeded your current quota, please check your plan "
			+ "and billing details").kind(), "out of quota, not just busy");
		assertEquals(Problems.Kind.QUOTA, Problems.classify("Your credit balance is too low to access the API").kind());
		assertEquals(Problems.Kind.RATE_LIMITED, Problems.classify("429 Too Many Requests").kind());
		assertEquals(Problems.Kind.MODEL_NOT_FOUND, Problems.classify("404 not_found_error: model: claude-9").kind());
		assertEquals(Problems.Kind.MODEL_NOT_FOUND,
			Problems.classify("Cannot resolve operation: v/models/anthropic/claude-9").kind());
		assertEquals(Problems.Kind.NO_KEY, Problems.classify("API key for anthropic is missing").kind());
		assertEquals(Problems.Kind.VENUE_NOT_READY, Problems.classify("The venue is not ready yet").kind());
		assertEquals(Problems.Kind.TOOL_FAILED, Problems.classify("Tool call failed: file_read").kind());
		assertEquals(Problems.Kind.NETWORK, Problems.classify("java.net.UnknownHostException: api.anthropic.com").kind());
		assertEquals(Problems.Kind.PROVIDER_DOWN, Problems.classify("503 Service Unavailable").kind());
		assertEquals(Problems.Kind.OTHER, Problems.classify("Widget sprocket misaligned").kind());
		for (Problems.Kind k : Problems.Kind.values()) {
			// every kind says what happened and what to try
			Problems.Problem p = Problems.classify(sample(k));
			assertFalse(p.what().isBlank() || p.tryThis().isBlank(), k.toString());
			assertTrue(p.where().contains(Problems.LOG), "and where the log is");
		}
	}

	@Test
	void aRefusalShowsOnlyTheRefusedAbilityAndResource() {
		String raw = "Capability denied: requires secret/use on did:key:z6MkVenue:u:sam/s/MODEL_PROVIDER_KEY. "
			+ "Your capabilities are: invoke on v/ops/, crud/read on w/, agent/request on g/Tau, secret/extract on s/";
		Problems.Problem p = Problems.classify(raw);
		assertEquals(Problems.Kind.REFUSED, p.kind());
		assertEquals("secret/use", p.ability());
		assertEquals("s/MODEL_PROVIDER_KEY", p.resource(), "the path, not the owner's identity");
		for (String shown : new String[] { p.what(), p.tryThis(), p.details(), p.line() }) {
			assertFalse(shown.contains("Your capabilities"), shown);
			assertFalse(shown.contains("agent/request") || shown.contains("secret/extract") || shown.contains("crud/read"),
				"no other rights: " + shown);
		}
		assertTrue(p.calm(), "a refusal is calm, never the error colour");
		assertFalse(Problems.classify("401 Unauthorized").calm(), "a refused model key is a fault");
		assertEquals(1, p.line().split("secret/use", -1).length - 1, "the ability is named once: " + p.line());
		Problems.Problem room = Problems.classify("This request may not post in room ci-watch");
		assertEquals("room/post", room.ability());
		assertEquals("room ci-watch", room.resource());
		try (var logs = ExpectedLogs.warn(Problems.class, "problem")) {
			Problems.Problem typed = Problems.describe(new CompletionException(new AuthException("Capability denied: "
				+ "requires invoke on v/ops/claudecode/run. Your capabilities are: invoke on v/ops/covia/read")));
			assertEquals(Problems.Kind.REFUSED, typed.kind());
			assertEquals("invoke", typed.ability());
			assertFalse(typed.details().contains("covia/read"));
			assertTrue(typed.where().contains("under problem "), "names its log line");
		}
	}

	@Test
	void keysInTheRawTextAreMaskedAndTheTypeStillCounts() {
		Problems.Problem p = Problems.classify("401 invalid x-api-key sk-ant-api03-abcdefghijklmnopqrstuvwxyz");
		assertFalse(p.details().contains("abcdefghijklmnop"), p.details());
		try (var logs = ExpectedLogs.warn(Problems.class, "problem")) {
			assertEquals(Problems.Kind.NETWORK, Problems.describe(new ConnectException()).kind(), "no message at all");
		}
	}

	private static String sample(Problems.Kind k) {
		return switch (k) {
			case MODEL_AUTH -> "401 Unauthorized";
			case NO_KEY -> "no api key";
			case QUOTA -> "402 payment required";
			case RATE_LIMITED -> "429";
			case MODEL_NOT_FOUND -> "unknown model";
			case PROVIDER_DOWN -> "500 internal server error";
			case VENUE_NOT_READY -> "venue is not running";
			case TOOL_FAILED -> "tool failure";
			case REFUSED -> "Capability denied: requires invoke on v/ops/x";
			case NETWORK -> "connection refused";
			case OTHER -> "odd";
		};
	}
}
