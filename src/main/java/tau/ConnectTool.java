package tau;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * Connecting another tool to this venue (docs/CONFIGURATION.md, "Connect a
 * tool"): which clients there are, the exact configuration each needs for a
 * named token's key, and a test that the key works at the MCP endpoint.
 * No Swing here; Settings → Access shows it.
 *
 * <p>HTTP clients carry the key as a fixed {@code Authorization} header.
 * Clients that launch a command (Claude Desktop, any stdio client) run Tau's
 * own {@code --mcp-stdio} bridge with the key in {@value McpStdio#TOKEN_ENV},
 * so the bridge sends that scoped key rather than a login as the owner.</p>
 */
public final class ConnectTool {

	/** A tool to connect: its name, the name its token gets, and where its configuration goes. */
	public enum Client {
		CLAUDE_CODE("Claude Code", "claude-code", "Run this in a terminal, in the project you want it in"),
		CODEX("Codex", "codex", "Add this to ~/.codex/config.toml, then restart Codex; codex mcp list shows it"),
		CURSOR("Cursor", "cursor", "Add this to ~/.cursor/mcp.json (or .cursor/mcp.json in one project)"),
		CLAUDE_DESKTOP("Claude Desktop", "claude-desktop",
			"Add this to claude_desktop_config.json (Settings → Developer → Edit config), then restart Claude Desktop"),
		OTHER_HTTP("Another MCP client (HTTP)", "mcp-client", "Add this to the client's MCP settings"),
		OTHER_STDIO("Another MCP client (stdio)", "mcp-stdio", "Add this to the client's MCP settings");

		public final String label;
		public final String tokenName;
		public final String where;

		Client(String label, String tokenName, String where) {
			this.label = label;
			this.tokenName = tokenName;
			this.where = where;
		}

		/** Whether the client launches Tau's stdio bridge rather than speaking HTTP. */
		public boolean stdio() {
			return this == CLAUDE_DESKTOP || this == OTHER_STDIO;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	/** The server's name in each client's configuration. */
	public static final String SERVER = "tau";

	private ConnectTool() {
	}

	/**
	 * The configuration {@code client} needs to reach the MCP endpoint at
	 * {@code mcpUrl} with {@code key}. A stdio client runs {@code java} on
	 * {@code jar}.
	 */
	public static String config(Client client, String mcpUrl, String key, String java, String jar) {
		String auth = "Bearer " + key;
		return switch (client) {
			case CLAUDE_CODE -> "claude mcp add --transport http --scope local " + SERVER + " " + mcpUrl
				+ " --header \"Authorization: " + auth + "\"";
			case CODEX -> "[mcp_servers." + SERVER + "]\n"
				+ "url = " + json(mcpUrl) + "\n"
				+ "http_headers = { \"Authorization\" = " + json(auth) + " }\n";
			case CURSOR, OTHER_HTTP -> "{\n"
				+ "  \"mcpServers\": {\n"
				+ "    " + json(SERVER) + ": {\n"
				+ "      \"url\": " + json(mcpUrl) + ",\n"
				+ "      \"headers\": { \"Authorization\": " + json(auth) + " }\n"
				+ "    }\n"
				+ "  }\n"
				+ "}\n";
			case CLAUDE_DESKTOP, OTHER_STDIO -> "{\n"
				+ "  \"mcpServers\": {\n"
				+ "    " + json(SERVER) + ": {\n"
				+ "      \"command\": " + json(java) + ",\n"
				+ "      \"args\": [\"-jar\", " + json(jar) + ", \"--mcp-stdio\"],\n"
				+ "      \"env\": { " + json(McpStdio.TOKEN_ENV) + ": " + json(key) + " }\n"
				+ "    }\n"
				+ "  }\n"
				+ "}\n";
		};
	}

	/** This Java's executable, for a stdio client to run. */
	public static String javaPath() {
		return Path.of(System.getProperty("java.home"), "bin", "java").toString();
	}

	/** The jar this app runs from, or a placeholder to replace when it runs from classes. */
	public static String jarPath() {
		try {
			Path p = Path.of(ConnectTool.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			if (p.toString().endsWith(".jar")) return p.toString();
		} catch (Exception e) {
			// not from a jar
		}
		return "/path/to/tau.jar";
	}

	/** A string as JSON (and TOML) writes it: quoted, with its escapes. */
	static String json(String s) {
		StringBuilder sb = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
					else sb.append(c);
				}
			}
		}
		return sb.append('"').toString();
	}

	/** What a connection test found. */
	public enum Outcome {
		WORKS, KEY_REFUSED, NOT_RUNNING, FAILED
	}

	/** A test's outcome and, when it works, how many tools the key offers. */
	public record Test(Outcome outcome, int tools) {
		/** In plain words, for beside the Test connection button. */
		public String words() {
			return switch (outcome) {
				case WORKS -> "Works: the key reaches " + tools + (tools == 1 ? " tool." : " tools.");
				case KEY_REFUSED -> "The venue didn't accept this key. It may have expired or been revoked: make a new key.";
				case NOT_RUNNING -> "Couldn't reach the venue. Check " + Tau.APP_NAME + " is open and not locked.";
				case FAILED -> "The venue answered, but not as an MCP server should. The log has the details.";
			};
		}
	}

	/**
	 * Lists the tools {@code key} offers at {@code mcpUrl}, as a client would:
	 * the proof that the configuration will work. Never shows the key or the
	 * server's reply. {@link Outcome#NOT_RUNNING} means nothing accepted a
	 * connection; a connection that was accepted and then reset or closed
	 * before an answer (as the venue may do to a request it refuses) is tried
	 * once more on a fresh connection, and what that answers decides.
	 */
	public static Test test(String mcpUrl, String key) {
		try {
			try {
				return once(mcpUrl, key);
			} catch (java.io.IOException dropped) {
				if (refused(dropped)) return new Test(Outcome.NOT_RUNNING, 0);
				// Accepted, then dropped: once more, on a connection of its own.
				return once(mcpUrl, key);
			}
		} catch (java.io.IOException e) {
			return new Test(refused(e) ? Outcome.NOT_RUNNING : Outcome.FAILED, 0);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Test(Outcome.FAILED, 0);
		} catch (RuntimeException e) {
			return new Test(Outcome.FAILED, 0);
		}
	}

	/** Whether {@code e} says nothing accepted the connection at all: refused, or no answer to connect. */
	static boolean refused(Throwable e) {
		for (Throwable c = e; c != null; c = (c.getCause() == c) ? null : c.getCause()) {
			if (c instanceof ConnectException || c instanceof java.net.http.HttpConnectTimeoutException
				|| c instanceof java.net.UnknownHostException || c instanceof java.net.NoRouteToHostException) {
				return true;
			}
		}
		return false;
	}

	/** One request on a client of its own, so no connection is reused from an earlier one. */
	private static Test once(String mcpUrl, String key) throws java.io.IOException, InterruptedException {
		try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
			HttpRequest request = HttpRequest.newBuilder(URI.create(mcpUrl)).timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
				.header("Authorization", "Bearer " + key)
				.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
				.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			int status = response.statusCode();
			if (status == 401 || status == 403) return new Test(Outcome.KEY_REFUSED, 0);
			if (status != 200) return new Test(Outcome.FAILED, 0);
			String body = response.body();
			int start = body.indexOf('{');
			if (start < 0) return new Test(Outcome.FAILED, 0);
			// A streamed reply carries the JSON on its data line.
			String jsonText = body.substring(start);
			int end = jsonText.indexOf("\n");
			if (body.startsWith("event:") || body.startsWith("data:")) jsonText = (end < 0) ? jsonText : jsonText.substring(0, end);
			ACell reply = JSON.parse(jsonText.strip());
			ACell tools = RT.getIn(reply, "result", "tools");
			if (tools == null) {
				return RT.getIn(reply, "error") != null ? new Test(Outcome.KEY_REFUSED, 0) : new Test(Outcome.FAILED, 0);
			}
			return new Test(Outcome.WORKS, (int) RT.count(tools).longValue());
		}
	}
}
