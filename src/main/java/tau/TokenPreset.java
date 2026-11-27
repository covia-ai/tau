package tau;

import convex.auth.ucan.Capability;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Abilities;
import covia.lattice.Caps;

/**
 * The usual scopes for a named access token ({@link Tokens}), each a set of
 * tools and the caps their calls act within. A custom token starts from one of
 * these and changes either.
 */
public enum TokenPreset {

	/** Hand the owner's chat agent work and follow it, and read the owner's workspace. */
	ASK_ASSISTANT("Ask the assistant", "Give your assistant tasks and follow them; read your workspace") {
		@Override
		public Tokens.Scope scope(String agentId) {
			return new Tokens.Scope(
				Vectors.of(OP_AGENT_REQUEST, OP_JOB_STATUS, OP_JOB_RESULT, OP_READ, OP_LIST),
				Vectors.of(
					Capability.create(Strings.create("g/" + agentId), Abilities.AGENT_REQUEST),
					Capability.create(JOBS, Abilities.JOB_READ),
					Capability.create(WORKSPACE, Capability.CRUD_READ)));
		}
	},

	/** Read and list the owner's workspace, and nothing else. */
	READ_ONLY("Read only", "Read and list your workspace") {
		@Override
		public Tokens.Scope scope(String agentId) {
			return new Tokens.Scope(Vectors.of(OP_READ, OP_LIST),
				Vectors.of(Capability.create(WORKSPACE, Capability.CRUD_READ)));
		}
	},

	/**
	 * Read the owner's workspace, and read and post in the venue's
	 * collaboration rooms ({@link RoomAdapter}): for an outside agent working
	 * alongside the owner's, which reach it through the rooms they follow.
	 */
	COLLABORATE("Collaborate", "Read your workspace, and read, follow and post in collaboration rooms") {
		@Override
		public Tokens.Scope scope(String agentId) {
			return new Tokens.Scope(
				Vectors.of(OP_ROOM_READ, OP_ROOM_WAIT, OP_ROOM_POST, OP_READ, OP_LIST),
				Vectors.of(
					Capability.create(ROOMS, Capability.CRUD_READ),
					Capability.create(ROOMS, RoomAdapter.ROOM_POST),
					Capability.create(WORKSPACE, Capability.CRUD_READ)));
		}
	},

	/**
	 * All of the owner's authority. Its tools include {@code grid_run}, which
	 * runs any operation, so it reaches everything the owner can without the
	 * token naming every tool.
	 */
	UNRESTRICTED("Unrestricted", "Everything you can do, through any operation. Anyone with its key acts as you "
		+ "in all but name: keep it like your passphrase") {
		@Override
		public Tokens.Scope scope(String agentId) {
			return new Tokens.Scope(
				Vectors.of(OP_RUN, OP_READ, OP_LIST, OP_WRITE, OP_AGENT_REQUEST, OP_AGENT_CHAT, OP_JOB_STATUS, OP_JOB_RESULT),
				Caps.UNRESTRICTED);
		}

		@Override
		public boolean dangerous() {
			return true;
		}
	};

	private static final AString OP_RUN = Strings.intern("v/ops/grid/run");
	private static final AString OP_WRITE = Strings.intern("v/ops/covia/write");
	private static final AString OP_AGENT_CHAT = Strings.intern("v/ops/agent/chat");
	private static final AString OP_AGENT_REQUEST = Strings.intern("v/ops/agent/request");
	private static final AString OP_JOB_STATUS = Strings.intern("v/ops/job/status");
	private static final AString OP_JOB_RESULT = Strings.intern("v/ops/job/result");
	private static final AString OP_READ = Strings.intern("v/ops/covia/read");
	private static final AString OP_LIST = Strings.intern("v/ops/covia/list");
	private static final AString OP_ROOM_POST = Strings.intern("v/ops/room/post");
	private static final AString OP_ROOM_READ = Strings.intern("v/ops/room/read");
	private static final AString OP_ROOM_WAIT = Strings.intern("v/ops/room/wait");
	private static final AString JOBS = Strings.intern("j/");
	private static final AString ROOMS = Strings.intern("x/room");
	private static final AString WORKSPACE = Strings.intern("w/");

	private final String label;
	private final String description;

	TokenPreset(String label, String description) {
		this.label = label;
		this.description = description;
	}

	/** The preset's name, as Settings shows it. */
	public String label() {
		return label;
	}

	/** One line on what a token with this scope can do. */
	public String description() {
		return description;
	}

	/** The tools and caps a token with this preset carries, for the owner's chat agent {@code agentId}. */
	public abstract Tokens.Scope scope(String agentId);

	/** Whether a token with this preset can do everything the owner can, which Settings marks with a warning. */
	public boolean dangerous() {
		return false;
	}

	/**
	 * Whether a token with this preset can hand the owner's assistant work. The
	 * assistant can run commands on this computer through Claude Code, so such
	 * a token can ask it to; Settings says so beside it.
	 */
	public boolean asksTheAssistant() {
		return scope("x").tools().contains(OP_AGENT_REQUEST) || dangerous();
	}

	/** What Settings says beside a preset that can hand the assistant work. */
	public static final String RUNS_COMMANDS = "The assistant can run commands on this computer through Claude Code, "
		+ "so whoever holds this key can ask it to.";

	/** The presets offered without Advanced: none of them can do everything the owner can. */
	public static java.util.List<TokenPreset> scoped() {
		return java.util.Arrays.stream(values()).filter(p -> !p.dangerous()).toList();
	}

	/** The preset {@code scope} is, for chat agent {@code agentId}, or null for a custom scope. */
	public static TokenPreset matching(Tokens.Scope scope, String agentId) {
		for (TokenPreset preset : values()) {
			if (preset.scope(agentId).equals(scope)) return preset;
		}
		return null;
	}

	@Override
	public String toString() {
		return label;
	}
}
