package tau.model;

import java.util.ArrayList;
import java.util.List;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.venue.Config;

/**
 * What an agent Tau makes may do, by template (docs/IDENTITY.md, "What an
 * agent may do"): Covia caps over the owner's namespace, never Unrestricted
 * unless the owner chooses it. Every agent may call operations (each still
 * checks what it touches), read the workspace, the file roots and its jobs,
 * keep its own notes and the shared memory, and ask the owner through the
 * Inbox. Templates add what their work needs. A write or delete through a
 * template's caps is {@linkplain #GATE gated}: a new file goes ahead, while an
 * overwrite or a delete waits for the owner's approval.
 *
 * <p>Covia has no separate grant for the internet: fetching and sending over
 * http is an operation like any other, so every agent that may run
 * operations may reach the internet (the venue's address policy still keeps
 * it off this computer and the local network). The words say so.</p>
 */
public final class AgentPermissions {

	/** The gate that decides each write and delete: an approval for an overwrite or a delete. */
	public static final String GATE = "v/ops/tau/confirm-change";

	/** How the caps view says an agent may do everything its owner can. */
	public static final String UNRESTRICTED_WORDS = "Everything you can do (Unrestricted)";

	private AgentPermissions() {
	}

	/** What every agent Tau makes may do. */
	static final AVector<ACell> BASE = Vectors.of(
		cap("v/ops/", "invoke"),
		cap("w/", "crud/read"),
		cap("file://", "crud/read"),
		cap("j/", "job/read"),
		cap("n/", "crud/read"),
		cap("n/", "crud/write"),
		cap("w/memory", "crud/write"),
		cap("h/", "hitl/request"),
		cap("", "skill/load"),
		cap("", "tool/load"),
		cap("", "asset/read"));

	/** The caps an agent made from {@code template} starts with. */
	public static AVector<ACell> caps(AgentTemplate template) {
		return switch (template) {
			case GENERAL -> BASE.concat(Vectors.of(
				gated("w/", "crud/write"), gated("w/", "crud/delete"),
				gated("file://", "crud/write"), gated("file://", "crud/delete"),
				cap("g/", "agent/request")));
			case RESEARCH -> BASE.concat(Vectors.of(
				gated("w/", "crud/write")));
			case SOFTWARE -> BASE.concat(Vectors.of(
				gated("file://", "crud/write"), gated("file://", "crud/delete"),
				gated("w/", "crud/write")));
			case WRITING -> BASE.concat(Vectors.of(
				gated("w/", "crud/write"), gated("w/", "crud/delete")));
			case BLANK -> BASE;
		};
	}

	/**
	 * The main assistant's set, for a new install (docs/IDENTITY.md, "What an
	 * agent may do"): broad, so every shipped skill and tool still works, but
	 * scoped to the owner's namespace, and its writes and deletes are gated
	 * the same way a template's are. Derived from what the standard agent's
	 * skills, tools and loads call (AssistantScopeTest runs each):
	 * <ul>
	 *   <li>the base: operations, reads, its jobs, its notes, the shared
	 *       memory, the Inbox, and skill and tool loads;</li>
	 *   <li>workspace, file roots and drives (the vault among them): gated
	 *       writes and deletes;</li>
	 *   <li>the internet over http, for research and connections, using the
	 *       owner's stored keys and connected accounts without seeing them;</li>
	 *   <li>the owner's secrets: storing a key the owner gives it;</li>
	 *   <li>the owner's other agents: creating, asking and messaging them,
	 *       and keeping its own sessions;</li>
	 *   <li>rooms: reading, posting and creating;</li>
	 *   <li>Claude Code, in the owner's projects.</li>
	 * </ul>
	 * The gods are asked through Tau's bridges, which carry their own grant,
	 * and a relaunch is Odin's to run, so neither needs more here.
	 */
	public static final AVector<ACell> ASSISTANT = BASE.concat(Vectors.of(
		gated("w/", "crud/write"), gated("w/", "crud/delete"),
		gated("file://", "crud/write"), gated("file://", "crud/delete"),
		cap("dlfs/", "crud/read"), gated("dlfs/", "crud/write"), gated("dlfs/", "crud/delete"),
		cap("s/", "secret/use"), cap("oauth/", "oauth/use"),
		cap("s/", "crud/read"), gated("s/", "secret/write"),
		cap("x/oauth/", "crud/read"),
		cap("x/token", "crud/read"), gated("x/token", "mcp/token"),
		cap("a/", "asset/store"),
		cap("g/", "crud/read"), gated("g/", "agent/create"), cap("g/", "agent/request"), cap("g/", "agent/message"),
		gated("g/", "agent/write"),
		cap("h/", "crud/read"),
		cap("x/schedule/", "schedule/manage"),
		cap("x/room/", "crud/read"), cap("x/room/", "room/post"), cap("x/room/", "room/create"),
		cap("claudecode/", "claudecode/run")));

	/**
	 * What the assistant's set does not cover, in plain words for its page:
	 * each needs a grant the owner adds, or is the gods' to do. Found by
	 * running every tool of the shipped skills under the set
	 * (AssistantScopeTest).
	 */
	public static final List<String> NEEDS_MORE = List.of(
		"Copy an agent with everything it has",
		"Delete a stored key",
		"See a stored key's value (no agent ever can)",
		"Add or remove tool servers",
		"Disconnect a connected account",
		"Hand out access to others",
		"Read the venue's settings",
		"Run the venue: people, adapters, restarts and relaunches (Odin does these when asked)");

	/** What the assistant's set lets happen without asking, in plain words for its page. */
	public static final List<String> DOES_NOT_ASK = List.of(
		"Making something new: a file, a note, a key, a drive",
		"Its own notes and the shared memory",
		"Cancelling or running one of your schedules",
		"Claude Code's own edits inside the project it works in");

	/** The caps for {@code template}, or Unrestricted when the owner chose it. */
	public static ACell caps(AgentTemplate template, boolean unrestricted) {
		return unrestricted ? Config.UNRESTRICTED : caps(template);
	}

	/**
	 * {@code caps} in plain words for the caps view: each grant, saying when it
	 * asks the owner first; Unrestricted when there are no caps to list.
	 */
	public static List<String> words(ACell caps) {
		if (caps == null || Config.UNRESTRICTED.equals(caps) || "unrestricted".equals(String.valueOf(caps))) {
			return List.of(UNRESTRICTED_WORDS);
		}
		AVector<ACell> list = RT.ensureVector(caps);
		if (list == null) return List.of(String.valueOf(caps));
		List<String> out = new ArrayList<>();
		for (long i = 0; i < list.count(); i++) {
			ACell c = list.get(i);
			String can = String.valueOf(RT.getIn(c, "can"));
			String with = String.valueOf(RT.getIn(c, "with"));
			boolean asks = RT.getIn(c, "nb", "gate") != null;
			out.add(describe(can, with) + (asks ? " (" + asksWhen(can) + ")" : ""));
		}
		return out;
	}

	/** Whether {@code caps} let an agent do everything its owner can. */
	public static boolean unrestricted(ACell caps) {
		return words(caps).equals(List.of(UNRESTRICTED_WORDS));
	}

	private static String asksWhen(String can) {
		return switch (can) {
			case "agent/create" -> "asks you first when it gives one permissions";
			case "agent/write" -> "asks you first before changing what one may do, or deleting";
			case "secret/write" -> "asks you first before replacing one";
			case "mcp/token" -> "asks you first";
			default -> "asks you first before overwriting or deleting";
		};
	}

	private static String describe(String can, String with) {
		String where = switch (with) {
			case "" -> "anything";
			case "w/" -> "your workspace";
			case "w/memory" -> "the shared memory";
			case "n/" -> "its own notes";
			case "j/" -> "its jobs";
			case "h/" -> "your Inbox";
			case "g/" -> "your other agents";
			case "v/ops/" -> "operations";
			case "file://" -> "your file folders";
			case "dlfs/" -> "your drives";
			case "s/" -> "your stored keys";
			case "oauth/", "x/oauth/" -> "your connected accounts";
			case "x/token" -> "your access tokens";
			case "a/" -> "assets";
			case "x/schedule/" -> "your schedules";
			case "x/room/" -> "rooms";
			case "claudecode/" -> "your Claude Code projects";
			case "outbound" -> "the internet";
			default -> with;
		};
		return switch (can) {
			case "invoke" -> "v/ops/".equals(with) ? "Run operations, the internet included" : "Run " + where;
			case "crud/read" -> "Read " + where;
			case "crud/write" -> "Write to " + where;
			case "crud/delete" -> "Delete from " + where;
			case "job/read" -> "Follow " + where;
			case "hitl/request" -> "Ask you through " + where;
			case "skill/load" -> "Load skills";
			case "tool/load" -> "Load tools";
			case "asset/read" -> "Read assets";
			case "agent/request" -> "Hand work to " + where;
			case "agent/message" -> "Message " + where;
			case "agent/create" -> "Make new agents";
			case "agent/write" -> "Change " + where + " and their conversations";
			case "secret/use" -> "Use " + where + " without seeing them";
			case "oauth/use" -> "Use " + where;
			case "secret/write" -> "Store " + where;
			case "asset/store" -> "Store assets";
			case "mcp/token" -> "Revoke " + where;
			case "schedule/manage" -> "Run and cancel " + where;
			case "room/post" -> "Post in " + where;
			case "room/create" -> "Create " + where;
			case "claudecode/run" -> "Run Claude Code in " + where;
			case "outbound/http/get" -> "Fetch from " + where;
			case "outbound/http/post" -> "Send to " + where;
			default -> can + " on " + where;
		};
	}

	static AMap<AString, ACell> cap(String with, String can) {
		return Maps.of("with", with, "can", can);
	}

	static AMap<AString, ACell> gated(String with, String can) {
		return Maps.of(Strings.create("with"), Strings.create(with), Strings.create("can"), Strings.create(can),
			Strings.create("nb"), Maps.of("gate", GATE));
	}
}
