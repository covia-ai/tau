package tau.ui.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import convex.lattice.ALatticeComponent;
import tau.ConnectTool;
import tau.Tokens;

/**
 * Settings → Access offers scoped tokens only until the owner asks for the
 * advanced options: a login as them, a login as the venue operator, and the
 * Unrestricted preset stay reachable behind that step, and show at once when
 * one of the local logins is already kept. A connected tool's token never
 * takes a name already in use. Headless.
 */
class AccessPanelTest {

	private boolean keeping;

	private final AccessPanel.Host host = new AccessPanel.Host() {
		public List<AccessPanel.Row> tokens() { return List.of(); }
		public ALatticeComponent<?> tokenStore() { return null; }
		public String chatAgentId() { return "Tau"; }
		public String createToken(String name, Tokens.Scope scope, Long lifetimeSeconds) { return "key"; }
		public String newTokenKey(String name) { return "key"; }
		public void revokeToken(String name) { }
		public String revealTokenKey(String name, char[] passphrase) { return "key"; }
		public char[] rememberedPassphrase() { return null; }
		public String mintLogin(long seconds, boolean asOperator) { return "jwt"; }
		public boolean keepingToolToken() { return keeping; }
		public void keepToolToken(boolean keep) { }
		public boolean keepingOperatorToken() { return false; }
		public void keepOperatorToken(boolean keep) { }
	};

	@Test
	void advancedAccessIsBehindItsOwnStep() {
		AccessPanel panel = new AccessPanel(host);
		assertFalse(panel.advancedShown());
		List<String> offered = panel.offered();
		assertEquals("Named token for a tool (MCP)", offered.get(0));
		assertFalse(offered.contains("Login as you"));
		assertFalse(offered.contains("Venue operator login (advanced)"));
		assertFalse(offered.contains("Unrestricted"));
		panel.showAdvanced();
		offered = panel.offered();
		assertTrue(offered.containsAll(List.of("Login as you", "Venue operator login (advanced)", "Unrestricted")), "still reachable");
	}

	@Test
	void aLocalLoginAlreadyKeptStaysInView() {
		keeping = true;
		assertTrue(new AccessPanel(host).advancedShown());
	}

	@Test
	void aConnectedToolGetsItsSettingsAndAFreeName() {
		AccessPanel panel = new AccessPanel(host);
		assertEquals("claude-code", panel.connectName(ConnectTool.Client.CLAUDE_CODE));
		panel.showConnected(ConnectTool.Client.CURSOR, "cursor", "http://127.0.0.1:8085/mcp", "the-key");
		assertTrue(panel.connectSettings().contains("\"Authorization\": \"Bearer the-key\""));
	}
}
