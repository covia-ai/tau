# Skills, tools and memory

Tau exposes tools through a small initial set and discoverable skills.
Memory supplies preferences across conversations. `TauSkillsAdapter` and
`src/main/resources/tau/skills/` own the shipped library; Covia owns loading
and execution.

- **Tool exposure is not authority.** The default assistant has unrestricted
  caps within its owner's authority. Its initial tools cover reads, memory,
  feedback and compaction; skills expose further tools without granting new
  permissions. Previously loaded skills can remain available in a session.
- **Every top-level skill is a useful first load.** The assistant is
  general-purpose, and loading a skill is its first step towards specialising
  for a task. A skill therefore solves the general form of its problem itself
  and reveals children for the specific sub-issues. No shipped skill is a bare
  router.
- **Descriptions are the trigger.** An index line says which tasks the skill
  serves and when to load it, in the words the owner would use, and never
  which tools it carries. It is all the model sees before loading.
- **Skills teach judgement, not mechanics.** How a tool behaves is its
  schema's job. A skill body says when to act, what good looks like and what
  never to do.
- **Namespaces separate who writes what.** The venue writes `v/`, the owner's
  agent writes `w/`, scratch and memory live in `n/`.
- **Memory holds preferences, not records.** The pinned memory is the owner's
  preferences and standing decisions, kept short. What happened is already in
  job records and past conversations, and knowledge of a subject belongs in a
  skill or the workspace.

## Namespaces

| Namespace | Purpose | Written by |
|---|---|---|
| `v/skills/tau/…` | Tau's shipped skills | `TauSkillsAdapter`, at venue launch |
| `v/skills/root` | the venue's own library — its entry points, each opening a family | the venue |
| `w/skills` | the owner's agent's own skills, including imported `SKILL.md` folders | the agent, `FilesystemSkills` |
| `w/skill-feedback/<id>` | append-only reports of concrete skill-system misses | the scoped feedback operation |
| `w/memory` | what the owner is like and prefers, shared by all their assistants | the assistants |
| `n/…` | the agent's private scratch, including `n/memory`: the owner's standing decisions that only this agent's work needs | the agent |

Only the venue writes `v/`. A skill of the owner's shadows a shipped skill of
the same name, and Tau's shadow the venue's. Tau ships its own
variant of a venue skill where talking to the owner or using the app calls for
different judgement, and gives it the module's tools: `hitl`, and `voice` and
`speaking` in place of the speech module's `speech`. Protocol knowledge stays
close to the venue's. A skill is optional depth: an
agent may never load it, or may not be Tau's. Guidance needed every turn
belongs in context or the message; a rule that must be enforced belongs in
the operation's authority and validation checks.

## What the assistant starts with

Every agent Tau makes for the owner, the main assistant and those made from a
template, gets the same setup from `AgentSetup`. `ChatSession` applies it to the
main assistant on the owner's first message from the app after each launch;
until then, scheduled runs and other channels use the one last applied:

- **Skillsets** `w/skills`, `v/skills/tau`, `v/skills/root`, in that
  order. No skill is pinned, except in an agent made from a template for one
  kind of work: Research, Software development and Writing & editing pin
  `research`, `coding` and `writing`, and the pin stays as the owner's.
- **Tools:** read-only lattice inspection, memory editing, the scoped feedback
  reporter and compaction. Optional tools arrive through skills.
- **Context:** shared `w/memory`, private `n/memory`, and the `tau-context`
  operation load with the owner's identity, model route and current time.
  Pinned memory is budgeted and can be truncated; keep it short.

Mutable memory, including the gods' memory, enters the model as structured
tool-result data, not system instructions. Recording a claim in memory does
not make it an instruction from the owner. Tau's application context remains
trusted configuration.

The configured system prompt owns the assistant's name and role. Runtime facts
come from that context load. Working methods and everything optional stay in
skills. Those three jobs are kept apart on purpose.

Tau's own parts of that setup are the operation, tools, memory pins, skill
sources and the context load. The prompt, model, name, caps and the owner's
own pins are preserved by the upgrade operation. This differs from the main
assistant's first-chat setup, which re-applies the configured prompt, model
and standard tools, while preserving existing caps. Changing authority needs
an explicit agent edit by the owner or an authorised administrator.
`brightside:upgrade-agent` brings an
existing agent that Tau made (identified by the `tau-context` load) onto
Tau's current parts and leaves
the rest; it creates nothing. When a start finds Tau's parts or a shipped
skill changed since the last one, Tau asks Eir to upgrade the owner's
agents ([EIR.md](EIR.md#upgrades)).

## How a load works

The skills index provides descriptions for discovery. Loading adds the skill's
instructions and tool definitions to the session and exposes its children in
the index; children are not loaded automatically. Unloading removes those tools
and children, but does not remove the instructions already in history.

A pinned skill is included in every session and rebuilt after compaction.
Memory context is read before inference and appended when it changes. Covia
owns the cached-prefix and tool-dispatch mechanisms; its
[skills documentation](https://github.com/covia-ai/covia/blob/develop/venue/docs/SKILLS.md)
is the reference for those details.

## The shipped library

The source of truth is `TauSkillsAdapter` and the resources under
`src/main/resources/tau/skills/`. The Skills screen shows what the assistant
can discover; a session's Skills tab shows what it has loaded.

The library covers conversations, speech, files and structured data;
research, writing, planning and coding; automation and the Inbox; diagnostics,
administration and security; Convex and connected services. Tau supplies
product-specific judgement where the venue's generic skills are insufficient.
For example, its Convex skills route signing requests to Njord, and its token
skill sends token creation to Settings. These are instructions and tool choices,
not restrictions on all operations an owner-authorised agent could discover.

A top-level skill should solve the general task and reveal optional depth.
Avoid duplicating schemas in prose or creating a router with no useful guidance
of its own. Descriptions should say when to load a skill in task language.

## Growing, importing and feedback

- The assistant can work on Tau itself. Its source is a Claude Code
  project by default (`code/tau`, cloned on first use), and `coding` →
  `self-improvement` is the path: agree the change, hand it to Claude Code one
  bounded task at a time, verify by the tests, and report; the change takes
  effect once Odin relaunches Tau into the new build, when the owner
  wants it running. An explicitly configured loop can run this workflow
  ([SELF_IMPROVEMENT.md](SELF_IMPROVEMENT.md)).

- The assistant writes skills under `w/skills/<name>`. The authoring skill
  exposes writes and a deletion operation confined to that subtree. Other
  skills also expose workspace mutation, so authoring is not the sole path to
  changing skills. Deletion recovery depends on retained data or a backup;
  enforced confirmation remains open
  ([tau#16](https://github.com/covia-ai/tau/issues/16)).

- Folders of `SKILL.md` files under `~/.tau/skills/` are imported into
  `w/skills` at launch (`FilesystemSkills`), unchanged when nothing changed.
- A narrow always-on operation appends concrete misses — a failed load, a
  missing skill, an instruction that contradicts observed tools — under
  `w/skill-feedback/<job-id>`. It chooses the path itself and cannot write
  elsewhere. Ordinary task failures are not backlog; an improvement worth
  making to Tau is, under its own category. The self-improvement loop works
  this backlog ([SELF_IMPROVEMENT.md](SELF_IMPROVEMENT.md)).

## Tests

`TauSkillsTest` is driven by the shipped list: every skill resolves,
every tool it declares exists, every child it reveals loads with its tools
including the venue skills it points at, and no top-level skill reveals
children without tools of its own. Nothing asserts prose.
