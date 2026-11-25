package tau;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Venue;
import tau.chat.ChatSession;
import tau.chat.StopRun;
import tau.vault.Vault;

/**
 * {@code --self-test <folder>}: proves an installed copy works end to end,
 * headless, in a throwaway data folder, through the same code the app runs.
 * Never on by default, and never on a real data directory.
 *
 * <p>The first run in an empty folder sets up as onboarding does with the
 * offline echo bot, then checks a chat turn, a scoped token used by an MCP
 * client, a room post and read, Stop on a running job, and a clean close. A
 * second run in the same folder unlocks the store it left and finds that chat
 * turn again: the store reopens, and it was closed cleanly. Each step is
 * reported as PASS, FAIL or SKIP (could not be checked here, with the reason)
 * on standard output and in {@value #REPORT}, since a Windows launcher has no
 * console. The exit code is 0 when no step failed.
 *
 * <p>The folder must be empty or one an earlier self-test made (it holds
 * {@value #MARKER}), and must not be the default data directory. The venue
 * takes a free port, so a Tau already running here is never touched.
 */
public final class SelfTest {

	/** The file that marks a folder as the self-test's own. */
	public static final String MARKER = ".tau-self-test";
	/** The report, appended to on every run. */
	public static final String REPORT = "self-test-report.txt";
	/** Exit codes: every step passed; a step failed; the folder was refused; the arguments were wrong. */
	public static final int PASSED = 0, FAILED = 1, REFUSED = 2, USAGE = 64;

	/** Throwaway data only: the passphrase is fixed, and the seed made fresh. */
	private static final char[] PASSPHRASE = "self-test-only-passphrase".toCharArray();
	private static final String NAME = "Self Test";
	private static final String GREETING = "Self-test hello";
	private static final long WAIT_SECONDS = 60;

	private final Path home;
	private final PrintStream out;
	private final List<String> lines = new ArrayList<>();
	private int failures;

	private SelfTest(Path home, PrintStream out) {
		this.home = home;
		this.out = out;
	}

	/** Entry point from {@link Main}: {@code --self-test <folder>}. Returns the exit code. */
	public static int run(String[] args, PrintStream out, PrintStream err) {
		Path folder = null;
		for (int i = 0; i < args.length; i++) {
			// The folder follows the flag; another option there is a mistake, not a folder's name.
			if (args[i].equals("--self-test") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
				folder = Path.of(args[i + 1]);
			}
		}
		if (folder == null) {
			err.println("Usage: tau --self-test <empty or self-test folder>");
			return USAGE;
		}
		String refusal = refusal(folder);
		if (refusal != null) {
			err.println("Self-test refused: " + refusal);
			return REFUSED;
		}
		Path home = folder.toAbsolutePath().normalize();
		try {
			prepare(home);
		} catch (IOException e) {
			err.println("Self-test could not prepare " + home + ": " + e.getMessage());
			return REFUSED;
		}
		Tau.configureLogging(home);
		return new SelfTest(home, out).run();
	}

	/**
	 * Runs the self-test in {@code folder}, already checked by {@link #refusal},
	 * leaving the process's logging as it is: for a test in a shared JVM.
	 */
	static int runIn(Path folder, PrintStream out) throws IOException {
		Path home = folder.toAbsolutePath().normalize();
		prepare(home);
		return new SelfTest(home, out).run();
	}

	private static void prepare(Path home) throws IOException {
		Files.createDirectories(home);
		if (!Files.exists(home.resolve(MARKER))) Files.writeString(home.resolve(MARKER), "Made by tau --self-test.\n");
	}

	/**
	 * Why {@code folder} may not be used, or null when it may: it is the default
	 * data directory, or it holds something an earlier self-test did not make.
	 */
	static String refusal(Path folder) {
		Path abs = folder.toAbsolutePath().normalize();
		if (abs.equals(AppConfig.HOME.toAbsolutePath().normalize()) || sameFile(abs, AppConfig.HOME)) {
			return abs + " is the default data directory; use an empty folder";
		}
		if (!Files.exists(abs)) return null;
		if (!Files.isDirectory(abs)) return abs + " is not a folder";
		if (Files.exists(abs.resolve(MARKER))) return null;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(abs)) {
			if (entries.iterator().hasNext()) return abs + " is not empty and was not made by a self-test";
		} catch (IOException e) {
			return "cannot read " + abs + ": " + e.getMessage();
		}
		return null;
	}

	private static boolean sameFile(Path a, Path b) {
		try {
			return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
		} catch (IOException e) {
			return false;
		}
	}

	private int run() {
		boolean reopen = Vault.exists(home);
		log("Tau self-test " + BuildInfo.version() + " at " + Instant.now() + (reopen ? ", reopening" : ", first run"));
		log("Data folder: " + home);
		pass("runtime", "Java " + Runtime.version() + " from " + System.getProperty("java.home"));

		EmbeddedVenue venue = null;
		try {
			int port = freePort();
			Path config = home.resolve("config.json");
			if (!reopen) {
				Files.writeString(config, """
					// Written by tau --self-test: throwaway data, the offline echo bot, a free port.
					{
						"venue": { "port": %d },
						"chat": { "llmOperation": "%s" }
					}
					""".formatted(port, AppConfig.ECHO_LLM_OPERATION));
			} else {
				// A new free port each run: the last one may be taken by now.
				String text = Files.readString(config).replaceAll("\"port\"\\s*:\\s*\\d+", "\"port\": " + port);
				Files.writeString(config, text);
			}
			AppConfig app = AppConfig.load(config);

			// Onboarding (first run) or unlock (reopen), as Tau.onOnboardingComplete and Tau.onUnlock do.
			Vault vault = Vault.open(home, PASSPHRASE.clone());
			String seed;
			if (reopen) {
				seed = vault.seedHex();
			} else {
				seed = Blob.createRandom(new java.security.SecureRandom(), 32).toHexString();
				vault.storeSeed(seed);
			}
			AMap<AString, ACell> venueConfig = Vault.secure(app.venueConfig(), seed);
			venue = EmbeddedVenue.launch(venueConfig);
			if (!Tau.allowEchoModel(venue.engine(), app.chat().llmOperation())) {
				fail("venue", "the offline echo bot could not be switched on");
			}
			pass("venue", "up at " + venue.url() + " as " + venue.did());

			loopbackOnly(venue.port());
			webOriginsLocalOnly(venue.port());

			Identity identity;
			TauAdapter tau = venue.tau();
			if (reopen) {
				identity = Identity.fromCell(tau.owner());
				check(identity != null && identity.did() != null, "identity", "the identity made on the first run is in the store",
					"no identity in the reopened store");
			} else {
				identity = Identity.of(NAME).withVenueDID(venue.did());
				tau.saveOwner(identity.toCell());
				venue.durable();
				Identity saved = Identity.fromCell(tau.owner());
				check(saved != null && identity.did().equals(saved.did()), "onboarding", "identity " + identity.did(),
					"the identity was not saved");
			}
			if (identity == null || identity.did() == null) return finish(venue);

			String owner = identity.did();
			Venue me = venue.clientAs(owner);
			AppConfig.Chat chatConfig = app.chat();
			ChatSession chat = new ChatSession(me, chatConfig, identity.name());

			if (reopen) {
				reopened(me, chatConfig.agentId());
			} else {
				chatTurn(chat);
				tokenAndMcp(venue, owner, chatConfig.agentId());
				room(me);
				stop(venue, me, owner, chatConfig.agentId());
			}
		} catch (Throwable t) {
			fail("run", t.toString());
		}
		return finish(venue);
	}

	/** Closes the venue (the clean close a reopen depends on) and writes the summary. */
	private int finish(EmbeddedVenue venue) {
		if (venue != null) {
			try {
				venue.close();
				pass("close", "the venue and its store closed");
			} catch (Throwable t) {
				fail("close", t.toString());
			}
		}
		log(failures == 0 ? "RESULT PASS" : "RESULT FAIL (" + failures + " failed)");
		try {
			Files.writeString(home.resolve(REPORT), String.join(System.lineSeparator(), lines) + System.lineSeparator()
				+ System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			out.println("Could not write the report: " + e.getMessage());
		}
		return failures == 0 ? PASSED : FAILED;
	}

	/** The venue answers on loopback, and on no other address this computer has. */
	private void loopbackOnly(int port) throws IOException {
		if (!connects(InetAddress.getLoopbackAddress(), port)) {
			fail("loopback", "nothing answers on " + InetAddress.getLoopbackAddress().getHostAddress() + ":" + port);
			return;
		}
		List<InetAddress> others = new ArrayList<>();
		for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
			if (!nic.isUp() || nic.isLoopback()) continue;
			for (InetAddress a : Collections.list(nic.getInetAddresses())) {
				if (!a.isLoopbackAddress() && !a.isLinkLocalAddress()) others.add(a);
			}
		}
		if (others.isEmpty()) {
			skip("loopback", "answers on 127.0.0.1; this computer has no other address to try");
			return;
		}
		for (InetAddress a : others) {
			if (connects(a, port)) {
				fail("loopback", "the venue also answers on " + a.getHostAddress());
				return;
			}
		}
		pass("loopback", "answers on 127.0.0.1 only; refused on " + others.size() + " other address(es)");
	}

	/**
	 * A web page elsewhere can't read the venue from a browser: a request from
	 * another origin gets no Access-Control-Allow-Origin, while a page served
	 * from this computer still does. Checked on the open discovery route, which
	 * answers without a key.
	 */
	private void webOriginsLocalOnly(int port) {
		String url = "http://127.0.0.1:" + port + "/api/v1/status";
		try {
			String website = allowedOrigin(url, "https://evil.example");
			String local = allowedOrigin(url, "http://localhost:" + port);
			if (website != null) {
				fail("web-origins", "a website's request was allowed (" + website + ")");
			} else if (!("http://localhost:" + port).equals(local)) {
				fail("web-origins", "a page on this computer was refused (" + local + ")");
			} else {
				pass("web-origins", "a website is refused; a page on this computer is allowed");
			}
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			fail("web-origins", "could not ask the venue: " + e.getMessage());
		}
	}

	/** The Access-Control-Allow-Origin the venue answers a request from {@code origin} with, or null. */
	private static String allowedOrigin(String url, String origin) throws IOException, InterruptedException {
		java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
			.connectTimeout(java.time.Duration.ofSeconds(5)).build();
		java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
			.timeout(java.time.Duration.ofSeconds(10)).header("Origin", origin).GET().build();
		java.net.http.HttpResponse<Void> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.discarding());
		return response.headers().firstValue("Access-Control-Allow-Origin").orElse(null);
	}

	private static boolean connects(InetAddress address, int port) {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(address, port), 2000);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/** One chat turn with the offline echo bot. */
	private void chatTurn(ChatSession chat) throws Exception {
		ChatSession.Reply reply = chat.send(GREETING);
		check(reply != null && reply.text() != null && reply.text().contains(GREETING), "chat",
			"the reply came back in session " + (reply == null ? null : reply.sessionId()),
			"unexpected reply: " + (reply == null ? null : reply.text()));
	}

	/** A scoped named token, as Connect a tool makes, and an MCP client listing tools with it. */
	private void tokenAndMcp(EmbeddedVenue venue, String owner, String agentId) {
		Tokens tokens = new Tokens(venue.engine(), Strings.create(owner));
		String key = tokens.create("self-test", TokenPreset.READ_ONLY.scope(agentId), 3600L).bearer().toString();
		ConnectTool.Test test = ConnectTool.test(venue.url() + "mcp", key);
		check(test.outcome() == ConnectTool.Outcome.WORKS && test.tools() > 0, "token",
			"a read-only token reaches " + test.tools() + " tools over MCP", "MCP with the token: " + test.outcome());
		tokens.revoke("self-test");
		ConnectTool.Test revoked = ConnectTool.test(venue.url() + "mcp", key);
		check(revoked.outcome() == ConnectTool.Outcome.KEY_REFUSED, "token-revoked", "the revoked key is refused",
			"a revoked key gave " + revoked.outcome());
	}

	/** Makes a room, posts to it, and reads the post back. */
	private void room(Venue me) throws Exception {
		me.run("v/ops/room/create", Maps.of("room", "self-test")).get(WAIT_SECONDS, TimeUnit.SECONDS);
		me.run("v/ops/room/post", Maps.of("room", "self-test", "text", GREETING)).get(WAIT_SECONDS, TimeUnit.SECONDS);
		ACell page = me.run("v/ops/room/read", Maps.of("room", "self-test")).get(WAIT_SECONDS, TimeUnit.SECONDS);
		String read = String.valueOf(RT.getIn(page, "posts"));
		check(read.contains(GREETING), "room", "posted and read back", "the post was not read back: " + read);
	}

	/** Stop, as the chat's Stop button does it (StopRun), on a job that would otherwise never end. */
	private void stop(EmbeddedVenue venue, Venue me, String owner, String agentId) throws Exception {
		Job running = me.invoke("v/test/ops/never", Maps.empty()).get(WAIT_SECONDS, TimeUnit.SECONDS);
		StopRun.stop(venue.engine(), Strings.create(owner), running.getID(), agentId);
		try {
			running.future().get(WAIT_SECONDS, TimeUnit.SECONDS);
			fail("stop", "the job finished instead of being cancelled");
		} catch (java.util.concurrent.TimeoutException e) {
			fail("stop", "the job was still running " + WAIT_SECONDS + " s after Stop");
		} catch (Exception e) {
			String status = String.valueOf(running.getStatus());
			check(status.contains("CANCELLED"), "stop", "the running job was cancelled", "the job ended as " + status);
		}
	}

	/** The second run: the earlier chat turn is still there, and the store was closed cleanly last time. */
	private void reopened(Venue me, String agentId) throws IOException {
		boolean unclean;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(home, "*.unclean-*")) {
			unclean = files.iterator().hasNext();
		}
		// The launch above already proved it: Etch refuses to open a store left
		// open, and the self-test never repairs one. Nor may a repair copy exist.
		check(!unclean, "clean-close", "the store opened without a repair, so the last run closed it cleanly",
			"a repaired store's copy is here: an earlier run did not close it cleanly");
		SessionHistory.Snapshot latest = SessionHistory.loadLatest(me, agentId);
		boolean found = latest != null && latest.items().stream()
			.anyMatch(i -> i instanceof SessionHistory.Message m && m.text() != null && m.text().contains(GREETING));
		check(found, "history", "the first run's chat turn is in the reopened store", "the first run's chat turn is missing");
	}

	private static int freePort() throws IOException {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}

	private void check(boolean ok, String step, String passed, String failed) {
		if (ok) pass(step, passed);
		else fail(step, failed);
	}

	private void pass(String step, String detail) {
		log("PASS " + step + ": " + detail);
	}

	private void fail(String step, String detail) {
		failures++;
		log("FAIL " + step + ": " + detail);
	}

	private void skip(String step, String detail) {
		log("SKIP " + step + ": " + detail);
	}

	private void log(String line) {
		lines.add(line);
		out.println(line);
	}
}
