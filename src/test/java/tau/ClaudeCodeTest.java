package tau;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.grid.Job;
import covia.grid.Venue;
import covia.venue.RequestContext;

/**
 * Claude Code is part of the embedded venue: the adapter and its skill are
 * on it, and a project declared in config.json becomes the owner's once the
 * identity is bound, and no one else's. No test runs a turn: that starts a
 * process, and the improvement loop runs real turns every day. The command
 * is a stand-in all the same, so a refusal that failed would show as a run.
 */
class ClaudeCodeTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	@TempDir
	static Path home;

	private static Path project;
	private static EmbeddedVenue venue;
	private static String userDID;
	private static Venue user;

	@BeforeAll
	static void boot() throws Exception {
		project = Files.createDirectories(home.resolve("project"));
		venue = shared.venue();
		// What config.json's adapters.claudecode block would have declared at
		// launch — the stand-in command, this test's project — applied live.
		venue.engine().configureAdapter(ClaudeCode.ADAPTER, fakeConfig(Maps.of("scratch", project.toString())));
		userDID = Identity.of("code-owner").userDID(venue.did());
		user = venue.clientAs(userDID);
	}

	/** The adapter block with the stand-in as the command and the given projects. */
	private static AMap<AString, ACell> fakeConfig(AMap<AString, ACell> projects) throws Exception {
		return Maps.of(
			"command", RT.cvm(FakeClaudeCli.command()),
			"projects", projects);
	}

	@Test
	void theAdapterAndItsSkillAreOnTheVenue() {
		RequestContext ctx = RequestContext.of(Strings.create(venue.did()));
		for (String op : new String[] { "v/ops/claudecode/run", "v/ops/claudecode/session", "v/ops/claudecode/sessions",
			"v/ops/claudecode/stop", "v/ops/claudecode/projects" }) {
			assertNotNull(venue.engine().resolvePath(Strings.create(op), ctx), "op present: " + op);
		}
		assertNotNull(venue.engine().resolvePath(Strings.create("v/skills/adapters/claudecode"), ctx),
			"the module's skill is installed for the assistant to discover");
	}

	@Test
	void ownedDefaultsTheUserOfEveryProjectThatNamesNone() {
		AMap<AString, ACell> projects = Maps.of(
			"plain", "/some/where",
			"open", Maps.of("path", "/else/where", "description", "no user"),
			"theirs", Maps.of("path", "/their/place", "user", "did:test:someone"));
		AMap<AString, ACell> cfg = Maps.of("command", "claude", "projects", projects);
		AMap<AString, ACell> owned = ClaudeCode.owned(cfg, "did:test:owner");
		assertEquals(Strings.create("did:test:owner"), RT.getIn(owned, "projects", "plain", "user"));
		assertEquals(Strings.create("/some/where"), RT.getIn(owned, "projects", "plain", "path"));
		assertEquals(Strings.create("did:test:owner"), RT.getIn(owned, "projects", "open", "user"));
		assertEquals(Strings.create("no user"), RT.getIn(owned, "projects", "open", "description"));
		assertEquals(Strings.create("did:test:someone"), RT.getIn(owned, "projects", "theirs", "user"));
		assertEquals(Strings.create("claude"), RT.getIn(owned, "command"), "other settings pass through untouched");
		assertEquals(Maps.empty(), ClaudeCode.owned(Maps.empty(), "did:test:owner"), "nothing declared, nothing added");
		assertEquals(owned, ClaudeCode.owned(owned, "did:test:owner"), "idempotent");
	}

	@Test
	void tausOwnSourceIsAProjectByDefault() throws Exception {
		Path dir = ClaudeCode.checkoutDir(home);
		AMap<AString, ACell> cfg = ClaudeCode.withDefaultProject(Maps.of("command", "claude"), home);
		assertTrue(Files.isDirectory(dir), "created empty, for the assistant to clone into");
		assertEquals(Strings.create(dir.toString()), RT.getIn(cfg, "projects", ClaudeCode.PROJECT, "path"));
		assertEquals(Strings.create("auto"), RT.getIn(cfg, "projects", ClaudeCode.PROJECT, "options", "permissionMode"),
			"works without asking in its own checkout");
		String allowed = String.valueOf(RT.getIn(cfg, "projects", ClaudeCode.PROJECT, "options", "allowedTools"));
		for (String rule : new String[] { "Edit", "Write", "Bash(git:*)", "Bash(mvn:*)" }) {
			assertTrue(allowed.contains(rule), "the developer tools are allowed: " + rule);
		}
		for (String rule : new String[] { "Bash(curl:*)", "Bash(rm:*)", "Bash(ssh:*)" }) {
			assertFalse(allowed.contains(rule), "left to Claude Code's own checks: " + rule);
		}
		assertEquals(Strings.create("0"), RT.getIn(cfg, "projects", ClaudeCode.PROJECT, "options", "env", "GIT_TERMINAL_PROMPT"),
			"headless git fails fast instead of waiting for a credential prompt");
		assertTrue(String.valueOf(RT.getIn(cfg, "projects", ClaudeCode.PROJECT, "description")).contains(ClaudeCode.REPOSITORY),
			"the description says where to clone from");
		assertEquals(Strings.create("claude"), RT.getIn(cfg, "command"), "other settings pass through");
		assertEquals(cfg, ClaudeCode.withDefaultProject(cfg, home), "declared once");

		// A project of that name declared by the owner wins.
		AMap<AString, ACell> mine = Maps.of("projects", Maps.of(ClaudeCode.PROJECT, "/my/checkout"));
		assertEquals(mine, ClaudeCode.withDefaultProject(mine, home));
		assertEquals(mine, ClaudeCode.withDefaultProject(mine, null), "no home, no default");
	}

	@Test
	void theOwnersProjectIsTheirsAlone() throws Exception {
		// As declared, the project is the venue's: the owner cannot use it.
		assertNull(project(user, "scratch"), "not the owner's until the identity is bound");

		ClaudeCode.own(venue, userDID, home);
		ACell scratch = project(user, "scratch");
		assertNotNull(scratch, "the owner's after binding");
		assertEquals(Strings.create(userDID), RT.getIn(scratch, "user"));
		assertEquals(Strings.create(project.toAbsolutePath().normalize().toString()), RT.getIn(scratch, "path"));
		ACell own = project(user, ClaudeCode.PROJECT);
		assertNotNull(own, "Tau's own source is the owner's project too");
		assertEquals(Strings.create(ClaudeCode.checkoutDir(home).toString()), RT.getIn(own, "path"));

		// Another user on the venue sees nothing and cannot run there.
		Venue other = venue.clientAs(Identity.of("someone-else").userDID(venue.did()));
		assertNull(project(other, "scratch"));
		assertThrows(Exception.class, () -> run(other, Maps.of("project", "scratch", "prompt", "cwd")));

		// Binding again changes nothing (already the owner's).
		ClaudeCode.own(venue, userDID, home);
		assertNotNull(project(user, "scratch"));
	}

	private static AMap<AString, ACell> run(Venue client, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke("v/ops/claudecode/run", input).get(30, TimeUnit.SECONDS);
		return RT.castMap(job.future().get(60, TimeUnit.SECONDS));
	}

	/** The named project as the adapter lists it for this client, or null when it is not theirs. */
	private static ACell project(Venue client, String name) throws Exception {
		ACell out = client.run("v/ops/claudecode/projects", Maps.empty()).get(30, TimeUnit.SECONDS);
		if (!(RT.getIn(out, "projects") instanceof AVector<?> list)) return null;
		for (long i = 0; i < list.count(); i++) {
			ACell p = (ACell) list.get(i);
			if (Strings.create(name).equals(RT.getIn(p, "name"))) return p;
		}
		return null;
	}

}
