package tau;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.venue.Engine;

/**
 * Every tool the standard agent's skills can bring, Tau's and the venue's
 * shipped libraries alike, is accounted for under the assistant's set: run
 * in AssistantScopeTest, behind a grant the page lists as needing more, or of
 * an adapter whose grants a run there exercises. A skill that adds a tool
 * nobody has placed fails here, so the set is revisited rather than guessed.
 */
class AssistantInventoryTest {

	/** Run in AssistantScopeTest under the set, and succeeding (http: refused only by the address policy). */
	static final Set<String> RUN = Set.of(
		"v/ops/covia/write", "v/ops/covia/read", "v/ops/covia/list", "v/ops/covia/inspect",
		"v/ops/memory", "v/ops/file/write", "v/ops/file/read", "v/ops/file/list", "v/ops/file/create",
		"v/ops/dlfs/list-drives", "v/ops/dlfs/create-drive", "v/ops/dlfs/write", "v/ops/dlfs/read",
		"v/ops/vault/mkdir", "v/ops/vault/list", "v/ops/http/get", "v/ops/http/post",
		"v/ops/secret/set", "v/ops/secret/list", "v/ops/oauth/status", "v/ops/connections/list", "v/ops/mcp/list-tokens",
		"v/ops/agent/create", "v/ops/agent/request", "v/ops/agent/message",
		"v/ops/agent/sessions", "v/ops/tau/ask-odin", "v/ops/scheduler/schedule",
		"v/ops/scheduler/list", "v/ops/scheduler/trigger", "v/ops/hitl/list", "v/ops/skills/list", "v/ops/skills/read",
		"v/ops/tau/info", "v/ops/speech/engines", "v/ops/claudecode/projects", "v/ops/claudecode/sessions");

	/** Run in AssistantScopeTest under the set: each raises one Inbox card, and goes ahead once approved. */
	static final Set<String> ASKS = Set.of("v/ops/covia/delete", "v/ops/agent/update", "v/ops/mcp/delete-token",
		"v/ops/secret/set", "v/ops/archive/extract");

	/** The grant its adapter checks, checked in AssistantScopeTest as the adapter checks it (the call itself needs more). */
	static final Map<String, String> CHECKED = Map.of(
		"v/ops/claudecode/run", "the Claude Code CLI; checked as <owner>/claudecode/tau x claudecode/run",
		"v/ops/oauth/connect", "a provider set up on the venue, and the owner in a browser; connected accounts are used under oauth/use");

	/** Refused under the set in AssistantScopeTest: the page's "Needs an extra grant". */
	static final Set<String> NEEDS_MORE = Set.of(
		"v/ops/agent/fork", "v/ops/secret/delete", "v/ops/secret/extract", "v/ops/mcp/add-server", "v/ops/mcp/add-tool",
		"v/ops/mcp/remove-server", "v/ops/oauth/disconnect", "v/ops/ucan/issue", "v/ops/venue/show-config",
		"v/ops/venue/adapters", "v/ops/venue/adapter/configure", "v/ops/venue/adapter/enable",
		"v/ops/venue/adapter/disable", "v/ops/venue/adapter/restart", "v/ops/venue/module/load", "v/ops/venue/module/unload", "v/ops/venue/restart",
		"v/ops/venue/gc", "v/ops/user/list", "v/ops/user/info", "v/ops/user/create", "v/ops/user/delete",
		"v/ops/user/sudo", "v/ops/user/import", "v/ops/user/clone", "v/ops/user/authentication-add",
		"v/ops/user/authentication-list", "v/ops/user/authentication-revoke");

	/**
	 * Adapters whose every operation checks a grant a run above exercises (the
	 * same namespace and ability, or running operations alone), with the run that shows it.
	 */
	static final Map<String, String> FAMILY = Map.ofEntries(
		Map.entry("v/ops/covia/", "covia read, write and delete on the workspace"),
		Map.entry("v/ops/file/", "file read and write in a file root; deletes through the same gated grant"),
		Map.entry("v/ops/dlfs/", "drive reads and writes; deletes through the same gated grant"),
		Map.entry("v/ops/vault/", "the vault is a drive"),
		Map.entry("v/ops/agent/", "agent reads, create, request, message and update on the owner's agents"),
		Map.entry("v/ops/job/", "its jobs, read under j/"),
		Map.entry("v/ops/scheduler/", "schedule, list and trigger under x/schedule/"),
		Map.entry("v/ops/hitl/", "the Inbox: hitl/request and reads of h/"),
		Map.entry("v/ops/skills/", "skill list and read; import writes a new skill under the gated workspace grant"),
		Map.entry("v/ops/tau/", "Tau's own: the god bridges carry their own grant, the rest check the workspace"),
		Map.entry("v/ops/moltbook/", "the owner's Moltbook key, used without seeing it (secret/use)"),
		Map.entry("v/ops/claudecode/", "claudecode/run in the owner's projects"),
		Map.entry("v/ops/speech/", "running operations"),
		Map.entry("v/ops/convex/", "running operations; generated keys are stored as new secrets"),
		Map.entry("v/ops/crypto/", "running operations; generated keys are stored as new secrets"),
		Map.entry("v/ops/asset/", "asset/read and asset/store"),
		Map.entry("v/ops/json/", "running operations"),
		Map.entry("v/ops/jvm/", "running operations"),
		Map.entry("v/ops/schema/", "running operations"),
		Map.entry("v/ops/langchain/", "running operations"),
		Map.entry("v/ops/grid/", "running operations; another venue applies its own grants"),
		Map.entry("v/ops/a2a/", "running operations; the other agent applies its own"),
		Map.entry("v/ops/auth/", "its own identity"),
		Map.entry("v/ops/connections/", "reads of the owner's connections"),
		Map.entry("v/ops/oauth/", "connected-account reads; complete follows a connect the owner started"),
		Map.entry("v/ops/mcp/", "token list and revoke; tool listing runs operations"),
		Map.entry("v/ops/ucan/", "verifying runs operations"),
		Map.entry("v/ops/room/", "room create, post and read"));

	@Test
	void everyToolTheSkillsBringIsPlaced() throws Exception {
		Set<String> tools = new TreeSet<>();
		for (String path : TauSkillsAdapter.SHIPPED) {
			String resource = "/tau/skills/" + path.substring(path.lastIndexOf('/') + 1) + ".json";
			try (InputStream in = TauSkillsAdapter.class.getResourceAsStream(resource)) {
				collect(JSON.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)), tools);
			}
		}
		for (String json : venueSkills()) collect(JSON.parse(json), tools);
		assertTrue(tools.size() > 100, "the skills were read: " + tools.size());
		List<String> unplaced = new ArrayList<>();
		for (String op : tools) {
			if (RUN.contains(op) || ASKS.contains(op) || CHECKED.containsKey(op) || NEEDS_MORE.contains(op)) continue;
			if (FAMILY.keySet().stream().anyMatch(op::startsWith) && !NEEDS_MORE.contains(op)) continue;
			unplaced.add(op);
		}
		assertTrue(unplaced.isEmpty(), "tools no test places under the assistant's set: " + unplaced);
	}

	/** The venue's shipped skills, read from the Covia jar Tau runs on. */
	private static List<String> venueSkills() throws Exception {
		URI where = Engine.class.getProtectionDomain().getCodeSource().getLocation().toURI();
		List<String> out = new ArrayList<>();
		if (where.getPath().endsWith(".jar")) {
			try (FileSystem jar = FileSystems.newFileSystem(Path.of(where))) {
				read(jar.getPath("/skills"), out);
			}
		} else {
			read(Path.of(where).resolve("skills"), out);
		}
		return out;
	}

	private static void read(Path dir, List<String> out) throws Exception {
		try (Stream<Path> files = Files.list(dir)) {
			for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) out.add(Files.readString(f));
		}
	}

	/** Every operation named under a {@code tools} key, at any depth. */
	private static void collect(ACell node, Set<String> into) {
		if (node instanceof AMap<?, ?> m) {
			for (var e : ((AMap<AString, ACell>) m).entrySet()) {
				if ("tools".equals(e.getKey().toString()) || "tool".equals(e.getKey().toString())) {
					ACell v = e.getValue();
					AVector<ACell> list = RT.ensureVector(v);
					if (list == null && v != null) list = convex.core.data.Vectors.of(v);
					if (list != null) {
						for (ACell t : list) {
							if (t instanceof AString s) into.add(s.toString());
							else if (RT.getIn(t, "op") instanceof AString s) into.add(s.toString());
						}
					}
				}
				collect(e.getValue(), into);
			}
		} else if (node instanceof AVector<?> v) {
			for (ACell c : (AVector<ACell>) v) collect(c, into);
		}
	}
}
