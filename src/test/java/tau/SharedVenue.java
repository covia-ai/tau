package tau;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.venue.Config;

/**
 * The embedded venue a test class shares.
 *
 * <p>A venue is built to serve many principals at once, so a class that only
 * needs <em>a</em> venue takes this one and keeps to user principals of its own
 * — a {@code <venueDID>:u:<name>} nobody else uses, minted with
 * {@link Identity#of} exactly as the app mints the owner's — and what one test
 * can see of another is what one user can see of another: nothing. Test
 * classes run concurrently (see {@code junit-platform.properties}), so several
 * venues may be up at once, each on a temp store and an ephemeral port.</p>
 *
 * <p>How far the venue is shared is one switch: by default the whole run
 * shares one, and every class's principals work the same venue at the same
 * time — it is how a venue is used for real, and it costs one boot instead of
 * one per class. So what a class sets venue-wide (a god's configuration, an
 * adapter's) must be what every other class sets, or be set on a venue of its
 * own. A class also ends the work it starts: a job still running when the
 * venue closes holds the close for the venue's whole shutdown grace. With
 * {@code -Dtau.tests.sharedVenue=false} each test class launches its
 * own, closed with the class, to tell a fault of one class from a clash
 * between two.</p>
 *
 * <p>Use it as {@code @RegisterExtension static final SharedVenue shared = new
 * SharedVenue();} and read {@link #venue()} in {@code @BeforeAll}. A test of
 * the launch itself, of an encrypted store, or of taking a venue down launches
 * its own from {@link #config(Path)}.</p>
 */
public final class SharedVenue implements BeforeAllCallback, AfterAllCallback {

	/** One venue for the whole run, rather than one per class. */
	static final boolean ACROSS_RUN = !"false".equals(System.getProperty("tau.tests.sharedVenue"));

	private static final Namespace NAMESPACE = Namespace.create(SharedVenue.class);

	private volatile Launched launched;

	@Override
	public void beforeAll(ExtensionContext context) {
		// The store's scope decides the venue's life: JUnit closes what the store
		// holds when its context ends — the run's, or this class's.
		ExtensionContext scope = ACROSS_RUN ? context.getRoot() : context;
		Launched l = scope.getStore(NAMESPACE).getOrComputeIfAbsent("venue", key -> new Launched(), Launched.class);
		l.venue();
		launched = l;
	}

	@Override
	public void afterAll(ExtensionContext context) {
		launched = null;
	}

	/** The venue this class shares; valid from {@code @BeforeAll} to {@code @AfterAll}. */
	public EmbeddedVenue venue() {
		Launched l = launched;
		if (l == null) throw new IllegalStateException("No venue: register SharedVenue as a static @RegisterExtension");
		return l.venue();
	}

	/**
	 * A private test venue's config: Tau's defaults on {@code home}, a
	 * temporary store, and port 0 so the server picks a free port itself (read
	 * it back from {@link EmbeddedVenue#port()}) — no race with other venues
	 * booting alongside.
	 */
	public static AMap<AString, ACell> config(Path home) {
		return AppConfig.defaultVenue(home)
			.assoc(Config.STORE, Strings.create("temp"))
			.assoc(Fields.PORT, CVMLong.create(0));
	}

	/**
	 * A venue on a temp home; JUnit closes it with the store it was put in.
	 *
	 * <p>It launches on a thread of its own, and the classes that need it wait
	 * on its future. That wait is one JUnit's fork-join pool knows is blocked,
	 * so the pool starts other classes meanwhile instead of leaving every
	 * worker parked behind the boot.</p>
	 */
	private static final class Launched implements AutoCloseable {

		private final Path home;
		private final CompletableFuture<EmbeddedVenue> venue;

		Launched() {
			try {
				home = Files.createTempDirectory("tau-test-venue");
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			venue = CompletableFuture.supplyAsync(() -> EmbeddedVenue.launch(config(home)),
				launch -> Thread.ofPlatform().name("shared-venue-launch").start(launch));
		}

		/** The venue, once launched; waits for a launch under way. */
		EmbeddedVenue venue() {
			try {
				return venue.join();
			} catch (CompletionException e) {
				throw new IllegalStateException("The shared test venue failed to launch", e.getCause());
			}
		}

		@Override
		public void close() throws IOException {
			try {
				EmbeddedVenue v = venue.exceptionally(failure -> null).join();
				if (v != null) v.close();
			} finally {
				try (var paths = Files.walk(home)) {
					for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
				}
			}
		}
	}
}
