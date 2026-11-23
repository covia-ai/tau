package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import ch.qos.logback.classic.Level;
import convex.core.data.Strings;
import covia.adapter.TestAdapter;
import covia.grid.Venue;
import tau.KeyCheck.Outcome;
import tau.KeyCheck.Result;

/**
 * The model test reads a call's outcome as Covia reports it, through the
 * owner's in-process client: Covia's test models answer, or fail the way a
 * model call can (a setup problem, a provider out of reach, a failure value,
 * a refused request, a fault).
 */
@Execution(ExecutionMode.CONCURRENT)
class KeyCheckTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static final String FAILING_MODEL = "v/test/ops/failllm";

	private static Venue client(String name) {
		EmbeddedVenue venue = shared.venue();
		return venue.clientAs(Identity.of(name).userDID(venue.did()));
	}

	@Test
	void aModelThatAnswersWorksWithTheStoredKeyOrATypedOne() {
		KeyCheck check = new KeyCheck(client("key-check-works"));
		Result stored = check.check(AppConfig.ECHO_LLM_OPERATION, null);
		assertEquals(Outcome.WORKS, stored.outcome(), stored.message());
		assertNull(stored.message());
		Result typed = check.check(AppConfig.ECHO_LLM_OPERATION, "  typed-key  ");
		assertEquals(Outcome.WORKS, typed.outcome(), typed.message());
	}

	@Test
	void eachFailureIsReadByTheTypeCoviaGivesIt() {
		KeyCheck check = new KeyCheck(client("key-check-fails"));
		assertFailure(check, "setup", Outcome.SETUP);
		assertFailure(check, "unavailable", Outcome.UNAVAILABLE);
		assertFailure(check, "value", Outcome.SETUP);
		assertFailure(check, "content", Outcome.FAILED);
	}

	@Test
	void aFaultFailsWithTheVenuesReport() {
		try (var logs = ExpectedLogs.expect("covia.exception.FaultException", Level.ERROR,
				"Operation " + FAILING_MODEL + " failed")) {
			Result r = failing(new KeyCheck(client("key-check-fault")), "bug");
			assertEquals(Outcome.FAILED, r.outcome());
			assertNotNull(r.message());
		}
	}

	/** A failure scripted by {@code model} gives {@code expected}, with Covia's reason passed on whole. */
	private static void assertFailure(KeyCheck check, String model, Outcome expected) {
		Result r = failing(check, model);
		assertEquals(expected, r.outcome(), model + ": " + r.message());
		assertEquals(TestAdapter.FAILLLM_REASON.formatted(model), r.message(), model);
	}

	private static Result failing(KeyCheck check, String model) {
		return check.run(FAILING_MODEL, KeyCheck.input(null).assoc(Strings.create("model"), Strings.create(model)));
	}
}
