# Self-improvement

The self-improvement workflow uses Claude Code and Tau's Improver agent to
choose, implement, test and check changes. It runs when explicitly invoked or
scheduled on the owner's machine; installing Tau does not establish a daily
schedule. The procedure is the
[improvement-loop skill](../.claude/skills/improvement-loop/SKILL.md).

- **Bound the work.** Choose useful changes within the procedure's time and
  token limits; treat dependency upgrades as separate checked chunks.
- **Consult before design changes.** Routine fixes proceed; authority,
  startup and other unresolved design questions belong to the owner.
- **Verify behaviour.** Tests and smoke checks decide whether a build works;
  retry a reported failing action before closing its backlog entry.
- **Keep a last good build.** Rollback also requires a compatible store.
  A jar alone cannot undo a data migration.
- **Do not publish code autonomously.** Pushes and releases remain the owner's
  decision. The invoked procedure separately authorises issue reporting.

## Who does what

| Who | Part |
|---|---|
| Claude Code, from a scheduled task on the owner's computer | runs the loop: upgrades, implements, tests, relaunches, merges, reports; as the one driving development, it judges for itself how observable and manageable the venue is and how the development process could improve |
| Improver, the improvement agent | keeps the backlog honest, says what hurts most, weighs Claude Code's ideas, tries things |
| Tau's assistant and gods | report misses and ideas to the backlog as they meet them |
| The owner | files issues, answers design questions, pushes, releases |

Improver is an agent in the owner's namespace, forked from Tau's main agent
so it has the same tools and skills, with caps unrestricted and the
`self-improvement` skill pinned ([SKILLS.md](SKILLS.md)). It has its own
sessions, and may create child agents to test behaviour. Its definition is
the skill's `agent.json`; each run applies it.

## A run

The skill owns the commands and thresholds. A run checks the installation,
upgrades Covia when justified, consults Improver on a bounded change, tests and
commits it, then relaunches Tau into the new build and runs the smoke checks. Successful
work is integrated into local `develop`; failures are rolled back where the
store remains compatible and reported to the owner.

The run chooses which Covia to build on: develop, a release or an earlier
commit. It may move the store to a new format; past that point the last good
build cannot read the store, so a failure is fixed forward rather than rolled
back. It relaunches Tau into its build even when the owner launched Tau from
source.

## What needs the owner

- An open design question: how something should work where the evidence
  does not settle it, or anything touching authority, startup or the loop
  itself. Asked as an issue labelled `question` that tags the owner, with the
  options and a recommendation; the loop works around it until he answers,
  then acts and closes it. A question about Covia's design is asked the same
  way. Bug fixes and minor tweaks need no issue; a major enhancement may have
  one as its record.
- The loop never edits Covia. A Covia bug it reports straight away as an
  issue on `covia-ai/covia`, with the smallest reproduction and no exploit
  detail.
- Pushing and releasing.

The loop and the agents also leave alone, without the owner's word: any
agent's caps and the startup pass that repairs them, the security skill and
secret handling, what memory holds, which provider receives the owner's data,
and history ([GODS.md](GODS.md), [LAUNCH.md](LAUNCH.md#the-startup-pass)).

## Where things are

| What | Where |
|---|---|
| The loop's checkout | `~/.tau/code/tau`, Tau's own Claude Code project: on the owner's machine a worktree on branch `improve` |
| The backlog | `w/skill-feedback`, written by the skill-feedback operation: misses under their category, ideas under `improvement`; the loop marks entries `resolved` |
| Work and design questions | GitHub issues on `covia-ai/tau` |
| The last good build | `~/.tau/code/last-good.jar` (`file://code/last-good.jar` to Tau's relaunch) |
| The Covia commit last installed | `~/.tau/code/.covia-installed`, built from `../covia`'s committed `develop`, never its working tree |
| The schedule | configured separately on the owner’s computer; no schedule is installed by this repository |

The loop relaunches by calling Tau's relaunch operation with the venue
operator login Tau keeps for local tools while the owner has it ticked
([CONFIGURATION.md](CONFIGURATION.md#from-claude-code)). A relaunch is a
deterministic step of the loop, so no agent judges it; it is a recorded job of
the venue's, and the report says what changed and how it was tested, or what
failed for a rollback. Tau starts its successor itself, so the running app
does not depend on the Claude session that asked. Without the operator login
the loop cannot relaunch, and reports the build for the owner to relaunch.

Reports and issues quote the backlog and logs as they are. An owner whose
data is not test data says so in the repository's `CLAUDE.local.md`, and the
loop redacts.

## Known limits

- Run availability and missed-run handling depend on the scheduler the owner
  configures, not on Tau's venue scheduler.
- There is no store backup: once a newer Covia has migrated the store, the
  last good build cannot read it, so there is nothing to roll back to.
- Covia snapshot jars in `~/.m2` are shared with other work on the machine,
  so the loop reinstalls its commit when a build breaks in untouched code.
