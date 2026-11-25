package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.token.AccessToken;

/**
 * Connecting a tool: each client's settings carry the endpoint and the named
 * token's key in the shape that client reads (stdio clients through the bridge's
 * scoped key, never a login); the connection test proves a key works and
 * tells a refused key from a venue that isn't there; the presets offered
 * without Advanced are scoped, and none of them reaches Claude Code except
 * through the assistant, which the page says; a token lasts 30 days unless
 * the owner chooses otherwise.
 */
class ConnectToolTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static final String URL = "http://127.0.0.1:8085/mcp";
	private static final String KEY = "tk_example\"key";

	@Test
	void eachClientGetsTheEndpointAndKeyInItsOwnShape() {
		String code = ConnectTool.config(ConnectTool.Client.CLAUDE_CODE, URL, "abc", "java", "tau.jar");
		assertTrue(code.startsWith("claude mcp add --transport http"), code);
		assertTrue(code.contains(URL) && code.contains("Authorization: Bearer abc"), code);

		String codex = ConnectTool.config(ConnectTool.Client.CODEX, URL, "abc", "java", "tau.jar");
		assertTrue(codex.startsWith("[mcp_servers.tau]"), codex);
		assertTrue(codex.contains("url = \"" + URL + "\"") && codex.contains("\"Bearer abc\""), codex);

		for (ConnectTool.Client c : List.of(ConnectTool.Client.CURSOR, ConnectTool.Client.OTHER_HTTP)) {
			ACell server = server(ConnectTool.config(c, URL, KEY, "java", "tau.jar"));
			assertEquals(URL, String.valueOf(RT.getIn(server, "url")), c.label);
			assertEquals("Bearer " + KEY, String.valueOf(RT.getIn(server, "headers", "Authorization")), "escaped, and whole");
		}
		for (ConnectTool.Client c : List.of(ConnectTool.Client.CLAUDE_DESKTOP, ConnectTool.Client.OTHER_STDIO)) {
			assertTrue(c.stdio());
			ACell server = server(ConnectTool.config(c, URL, KEY, "/opt/java/bin/java", "C:\\Apps\\Tau\\tau.jar"));
			assertEquals("/opt/java/bin/java", String.valueOf(RT.getIn(server, "command")));
			assertEquals("C:\\Apps\\Tau\\tau.jar", String.valueOf(RT.nth(RT.getIn(server, "args"), 1)), "a Windows path survives");
			assertEquals("--mcp-stdio", String.valueOf(RT.nth(RT.getIn(server, "args"), 2)));
			assertEquals(KEY, String.valueOf(RT.getIn(server, "env", McpStdio.TOKEN_ENV)), "the bridge sends the scoped key");
		}
	}

	@Test
	void theBridgeTakesAScopedKeyFromItsEnvironment() {
		assertEquals("abc", McpStdio.scopedKey("abc"));
		assertEquals("abc", McpStdio.scopedKey("  Bearer abc \n"));
		assertNull(McpStdio.scopedKey(""));
		assertNull(McpStdio.scopedKey("abc\r\nX-Injected: 1"), "no header injection");
		assertNull(McpStdio.scopedKey(null));
	}

	@Test
	void theConnectionTestProvesTheKeyAndTellsTheFailuresApart() throws Exception {
		AString owner = Strings.create(Identity.of("connect-owner").userDID(shared.venue().did()));
		shared.venue().engine().getVenueState().users().ensure(owner.toString());
		Tokens tokens = new Tokens(shared.venue().engine(), owner);
		String key = tokens.create("cursor", TokenPreset.READ_ONLY.scope("Tau"), Tokens.DEFAULT_LIFETIME_SECONDS).bearer();
		String mcp = shared.venue().url() + "mcp";

		ConnectTool.Test works = ConnectTool.test(mcp, key);
		assertEquals(ConnectTool.Outcome.WORKS, works.outcome(), works.words());
		assertEquals(2, works.tools(), "read and list: the token's own tools, no more");

		assertEquals(ConnectTool.Outcome.KEY_REFUSED, ConnectTool.test(mcp, "not-a-key").outcome());
		tokens.revoke("cursor");
		assertEquals(ConnectTool.Outcome.KEY_REFUSED, ConnectTool.test(mcp, key).outcome(), "a revoked key");

		int closed;
		try (ServerSocket s = new ServerSocket(0)) {
			closed = s.getLocalPort();
		}
		assertEquals(ConnectTool.Outcome.NOT_RUNNING, ConnectTool.test("http://127.0.0.1:" + closed + "/mcp", key).outcome());
	}

	@Test
	void aDroppedConnectionIsTriedAgainBeforeAnythingIsDecided() throws Exception {
		// Accepted, then reset before an answer (as a venue may drop a refused request), then a 401: the key is refused.
		try (Dropping venue = new Dropping(1)) {
			assertEquals(ConnectTool.Outcome.KEY_REFUSED, ConnectTool.test(venue.url(), "revoked").outcome());
			assertEquals(2, venue.accepted.get(), "tried once more, on a fresh connection");
		}
		// Reset every time: something is there but not answering as it should; it is not "not running".
		try (Dropping venue = new Dropping(Integer.MAX_VALUE)) {
			assertEquals(ConnectTool.Outcome.FAILED, ConnectTool.test(venue.url(), "any").outcome());
			assertEquals(2, venue.accepted.get(), "and only once more");
		}
	}

	/** A local server that resets the first {@code drops} connections, then answers 401. */
	private static final class Dropping implements AutoCloseable {
		final java.net.ServerSocket server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
		final java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
		private final Thread thread;

		Dropping(int drops) throws java.io.IOException {
			thread = Thread.ofVirtual().start(() -> {
				while (!server.isClosed()) {
					try (java.net.Socket s = server.accept()) {
						int n = accepted.incrementAndGet();
						// Read the request line and headers before answering or dropping, as a server would.
						java.io.InputStream in = s.getInputStream();
						byte[] buf = new byte[8192];
						StringBuilder head = new StringBuilder();
						while (!head.toString().contains("\r\n\r\n")) {
							int r = in.read(buf);
							if (r < 0) break;
							head.append(new String(buf, 0, r, java.nio.charset.StandardCharsets.ISO_8859_1));
						}
						if (n <= drops) {
							s.setSoLinger(true, 0); // closes with a reset
						} else {
							s.getOutputStream().write(("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
								.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
							s.getOutputStream().flush();
						}
					} catch (java.io.IOException e) {
						// closed: done
					}
				}
			});
		}

		String url() {
			return "http://127.0.0.1:" + server.getLocalPort() + "/mcp";
		}

		@Override
		public void close() throws Exception {
			server.close();
			thread.join(5000);
		}
	}

	@Test
	void aConnectedToolsTokenLastsThirtyDaysByDefault() {
		AString owner = Strings.create(Identity.of("connect-lifetime").userDID(shared.venue().did()));
		shared.venue().engine().getVenueState().users().ensure(owner.toString());
		Tokens tokens = new Tokens(shared.venue().engine(), owner);
		long before = System.currentTimeMillis();
		tokens.create("codex", TokenPreset.ASK_ASSISTANT.scope("Tau"), Tokens.DEFAULT_LIFETIME_SECONDS);
		AccessToken made = tokens.list().stream().filter(t -> "codex".equals(String.valueOf(t.name()))).findFirst().orElseThrow();
		Long expires = made.expires();
		assertNotNull(expires, "named tokens made here expire");
		long thirtyDays = 30L * 24 * 3600 * 1000;
		assertTrue(expires >= before + thirtyDays && expires <= System.currentTimeMillis() + thirtyDays, "30 days from now");
	}

	@Test
	void presetsOfferedWithoutAdvancedAreScopedAndKeepClaudeCodeOut() {
		assertFalse(TokenPreset.scoped().contains(TokenPreset.UNRESTRICTED));
		assertEquals(TokenPreset.ASK_ASSISTANT, TokenPreset.scoped().get(0), "the default");
		for (TokenPreset p : TokenPreset.values()) {
			Tokens.Scope scope = p.scope("Tau");
			if (p == TokenPreset.UNRESTRICTED) continue; // the explicit one, behind Advanced
			assertFalse(String.valueOf(scope.tools()).contains("claudecode"), p + " names no Claude Code tool");
			assertFalse(String.valueOf(scope.caps()).contains("claudecode"), p + " grants nothing on Claude Code");
			assertFalse(String.valueOf(scope.caps()).contains("unrestricted"), p + " is scoped");
		}
		assertTrue(TokenPreset.ASK_ASSISTANT.asksTheAssistant(), "so the page says it can run commands");
		assertTrue(TokenPreset.UNRESTRICTED.asksTheAssistant());
		assertFalse(TokenPreset.READ_ONLY.asksTheAssistant());
		assertFalse(TokenPreset.COLLABORATE.asksTheAssistant());
		assertEquals(30L * 24 * 3600, Tokens.DEFAULT_LIFETIME_SECONDS);
	}

	/** The {@code tau} server in a client's JSON settings. */
	private static ACell server(String config) {
		ACell parsed = JSON.parse(config);
		ACell server = RT.getIn(parsed, "mcpServers", ConnectTool.SERVER);
		assertNotNull(server, config);
		return server;
	}
}
