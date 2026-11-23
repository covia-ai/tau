package tau;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.exception.ConfigurationException;
import covia.exception.CoviaException;
import covia.exception.UnavailableException;
import covia.grid.Status;
import covia.grid.Venue;

/**
 * Settings → Model's <b>Test</b>: one small call to a model operation as the
 * client's principal, answered in Covia's own terms. A key typed but not yet
 * saved goes as the operation's {@code apiKey} input; with none, the
 * operation reads the caller's stored key as a chat does. No key or setting
 * is saved. The call is a job in the caller's history like any other run,
 * and a model operation declares {@code apiKey} secret, so its record does
 * not keep the key.
 *
 * <p>The outcome is read the way Covia's agent loop reads a model call
 * (Covia's {@code AGENT_LOOP.md} §3.4 and {@code ERRORS.md}): a
 * {@link ConfigurationException}, or a failure value, is a setup problem (the
 * key, the model or the provider's settings) that fails every call until it is
 * fixed; an {@link UnavailableException} is a provider that could not be
 * reached or kept failing, which may clear by itself; any other failure
 * refused this call. The message is Covia's, written for the caller, with the
 * provider's own error passed on as it was sent.</p>
 */
public final class KeyCheck {

	/** How long a test waits for the model before giving up on it. */
	static final long TIMEOUT_SECONDS = 30;

	/** Room for a one-word reply, with headroom for a model that thinks first. */
	static final long MAX_TOKENS = 256;

	static final String PROMPT = "Reply with the single word OK.";

	static final String NO_ANSWER = "No answer from the model within %d seconds; the provider may be busy or out of reach";
	static final String INTERRUPTED = "The test was interrupted";
	static final String NO_REASON = "The test call failed in the venue; its log has the details";

	private static final AString API_KEY = Strings.intern("apiKey");

	/** What the test found. */
	public enum Outcome {
		/** The model answered. */
		WORKS,
		/** The key, the model or the provider's settings are wrong: every call fails until they are fixed. */
		SETUP,
		/** The provider could not be reached, or kept failing: it may work later. */
		UNAVAILABLE,
		/** This call failed some other way. */
		FAILED
	}

	/** The outcome, Covia's reason for a failure (null when it worked), and how long the call took. */
	public record Result(Outcome outcome, String message, long millis) {
		public boolean ok() {
			return outcome == Outcome.WORKS;
		}
	}

	private final Venue client;

	/** Tests as {@code client}'s principal, reading its stored keys. */
	public KeyCheck(Venue client) {
		this.client = client;
	}

	/**
	 * Tests {@code modelOp} with {@code apiKey}, or the stored key when it is
	 * null or blank. Blocks until the model answers or the test gives up.
	 */
	public Result check(String modelOp, String apiKey) {
		return run(modelOp, input(apiKey));
	}

	/** The test call's input: one short message, room for the reply, and any typed key. */
	static AMap<AString, ACell> input(String apiKey) {
		AMap<AString, ACell> input = Maps.of(
			"messages", Vectors.of(Maps.of("role", "user", "content", PROMPT)),
			"maxTokens", CVMLong.create(MAX_TOKENS));
		return (apiKey == null || apiKey.isBlank()) ? input : input.assoc(API_KEY, Strings.create(apiKey.trim()));
	}

	/** Calls {@code modelOp} with {@code input} and reads the outcome. */
	Result run(String modelOp, AMap<AString, ACell> input) {
		long start = System.nanoTime();
		CompletableFuture<ACell> call = client.run(modelOp, input);
		try {
			ACell out = call.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			return answered(out, elapsed(start));
		} catch (ExecutionException e) {
			return failed(e.getCause(), elapsed(start));
		} catch (TimeoutException e) {
			// Stop the call rather than leave it running: cancelling closes the provider connection.
			call.cancel(true);
			return new Result(Outcome.UNAVAILABLE, NO_ANSWER.formatted(TIMEOUT_SECONDS), elapsed(start));
		} catch (InterruptedException e) {
			call.cancel(true);
			Thread.currentThread().interrupt();
			return new Result(Outcome.FAILED, INTERRUPTED, elapsed(start));
		}
	}

	/** A reply, unless it is a failure value: a setup problem the operation reports as its result. */
	private static Result answered(ACell out, long millis) {
		if (out instanceof AMap && Status.FAILED.equals(RT.getIn(out, Fields.STATUS))) {
			AString message = RT.ensureString(RT.getIn(out, Fields.MESSAGE));
			return new Result(Outcome.SETUP, (message != null) ? message.toString() : NO_REASON, millis);
		}
		return new Result(Outcome.WORKS, null, millis);
	}

	private static Result failed(Throwable failure, long millis) {
		String reason = CoviaException.reasonOf(failure);
		return new Result(outcomeOf(failure), (reason != null) ? reason : NO_REASON, millis);
	}

	/** What a failed model call means, by the type Covia gave it. */
	static Outcome outcomeOf(Throwable failure) {
		Throwable cause = failure;
		while ((cause instanceof CompletionException || cause instanceof ExecutionException)
				&& cause.getCause() != null) {
			cause = cause.getCause();
		}
		if (cause instanceof ConfigurationException) return Outcome.SETUP;
		if (cause instanceof UnavailableException) return Outcome.UNAVAILABLE;
		return Outcome.FAILED;
	}

	private static long elapsed(long startNanos) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
	}
}
