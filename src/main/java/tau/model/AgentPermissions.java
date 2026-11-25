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
				cap("outbound", "outbound/http/get"), cap("outbound", "outbound/http/post"),
				cap("g/", "agent/request")));
			case RESEARCH -> BASE.concat(Vectors.of(
				cap("outbound", "outbound/http/get"), cap("outbound", "outbound/http/post"),
				gated("w/", "crud/write")));
			case SOFTWARE -> BASE.concat(Vectors.of(
				gated("file://", "crud/write"), gated("file://", "crud/delete"),
				gated("w/", "crud/write"),
				cap("outbound", "outbound/http/get")));
			case WRITING -> BASE.concat(Vectors.of(
				gated("w/", "crud/write"), gated("w/", "crud/delete")));
			case BLANK -> BASE;
		};
	}

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
			out.add(describe(can, with) + (asks ? " (asks you first before overwriting or deleting)" : ""));
		}
		return out;
	}

	/** Whether {@code caps} let an agent do everything its owner can. */
	public static boolean unrestricted(ACell caps) {
		return words(caps).equals(List.of(UNRESTRICTED_WORDS));
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
			case "outbound" -> "the internet";
			default -> with;
		};
		return switch (can) {
			case "invoke" -> "Call " + where;
			case "crud/read" -> "Read " + where;
			case "crud/write" -> "Write to " + where;
			case "crud/delete" -> "Delete from " + where;
			case "job/read" -> "Follow " + where;
			case "hitl/request" -> "Ask you through " + where;
			case "skill/load" -> "Load skills";
			case "tool/load" -> "Load tools";
			case "asset/read" -> "Read assets";
			case "agent/request" -> "Hand work to " + where;
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
