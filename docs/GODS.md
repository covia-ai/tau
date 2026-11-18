# The gods

The gods are venue-owned agents with specialist authority. They handle
administration, reported threats, treasury requests and repair. Their roles
and judgement live in the individual documents below; shared mechanisms live
here in `God` and `Gods`.

- **Separate authority by role.** Ordinary agents act within their user's
  authority. Gods have selected venue capabilities and narrow bridges into
  user namespaces.
- **Caps enforce; skills expose; prompts guide.** Loading a tool does not grant
  its authority. Conversely, a prompt restriction does not remove authority
  already held.
- **Ask for the owner's decisions.** Gods use the venue Inbox, which the
  desktop combines with the user's Inbox.
- **Review after action.** Thor, Njord and Eir report changes made through
  their run bridges to Odin. A reporting failure is logged; it does not undo
  the change.
- **Respect the process boundary.** Capabilities do not confine native code
  loaded into the JVM or launched as the OS user. See [SECURITY.md](SECURITY.md).

## The team

| God | Job | Doc |
|---|---|---|
| Odin, the administrator | changes that need administrator rights; the wiser head the others answer to | [ODIN.md](ODIN.md) |
| Thor, the guardian | responds to reported threats; can stop and restrict agents at once | [THOR.md](THOR.md) |
| Njord, the treasurer | manages treasury keys and judges payment requests | [NJORD.md](NJORD.md) |
| Eir, the healer | repairs faults in Tau itself; Tau calls her when its own cure fails | [EIR.md](EIR.md) |

They work as one team. An assistant that meets an attack tells Thor. One that
needs a change it cannot make asks Odin, one that needs to pay asks Njord,
and one that meets a fault in Tau asks Eir. Odin and Njord tell Thor when a
request looks like an attack, and Thor, Njord and Eir answer to Odin for
what they do.

## When a job deserves a god

Use a god only for authority ordinary agents should not hold, independence
from agents it acts on, or responsibility across the venue. Otherwise use a
skill or ordinary agent. Every god turn costs a model call; automatic triggers
should identify a concrete problem and avoid repeated requests for the same
condition. Additional roles remain proposals until such a need exists.

## What every god shares

| | |
|---|---|
| Owner | the venue principal, not the user |
| Address | `<venueDID>/g/<name>` |
| Created | at startup when a stored model is available, and when needed by chat or a model change; startup may also request Eir’s help |
| Model | the chat's chosen model |
| Memory | `n/memory` under the venue: the owner's decisions, and what the god has learnt |
| Skills | the venue's own library, `v/skills/root`, and by name the skills its job calls for |
| Caps | every god's, below, and its own powers |
| Visible | only while acting as the venue operator (*Settings → Identity → Switch user*) |

`God` is one god's definition and `Gods` lists them. A new god is a
definition, its two bridge resources and a place in that list.

### Caps and skills

Gods exist because assistants cannot act as the venue. A god is the venue's
agent, so its caps narrow the venue's authority, and Covia admits it to an
administrative operation when its caps include that operation's ability
(`Engine.hasVenueAuthority`). Every god may call any operation, read
everything the venue holds (its data, setup, jobs, agents, the names of its
secrets, and every file root, Tau's log among them), keep its own notes and
ask through the Inbox. None writes the venue's state beyond its notes, nor
any file. Beyond that, each
holds its own powers, named as Covia names them:

| God | Powers |
|---|---|
| Odin | `admin/user/create` and `admin/user/read` on `users`; `admin/adapter/manage` on `adapters`; `p2p/read` and `p2p/manage`; `secret/write` and `crud/delete` on the venue's `s/`; `admin/venue/restart` on `process` (restart, relaunch, shutdown); `admin/venue/compact` on `store` |
| Thor | `admin/user/read` on `users` |
| Eir | `admin/user/read` on `users` |
| Njord | none: his treasury is granted per call |

`admin/venue/compact` is Tau's leaf of Covia's `admin/venue` family,
for compacting the store at the next start, apart from `admin/venue/gc`,
which collects it while it runs ([LAUNCH.md](LAUNCH.md#compacting-the-store)).
Tau sets each god's caps with the rest of its configuration at every
launch, before any god can act ([LAUNCH.md](LAUNCH.md#the-startup-pass)), and
whenever the model changes (`God`). `GodCapsTest` shows Covia refusing what
they leave out.

A god starts with the tools and skills its role needs and can discover others.
Covia refuses calls outside its caps regardless of the skill that exposes them.
Keep deterministic diagnosis and validation in operations; use the model for
judgement, not for reconstructing facts the application can read directly.

### The bridges

Each god has two narrow Tau operations for what caps do not cover.

**Ask** (`brightside:ask-odin`, `brightside:alert-thor`,
`brightside:ask-njord`, `brightside:ask-eir`) is callable by any authenticated user of the venue,
or their agents. It mints a short-lived, venue-rooted grant letting the
acting principal make a request of the god. It preserves that principal and
the request's route and token context. The grant adds this one request right
even if the token's own caps do not include it: invoking the bridge is access
to its mediation, not merely an alias for `agent:request`. The task belongs to the
caller's account; an agent is identified as that agent, not promoted to its
owner. Covia supplies provenance outside the untrusted task content
([SECURITY.md](SECURITY.md#external-requests)). The bridge returns the god's
outcome, or a started snapshot while the god is still deciding.

**Run** (`brightside:odin-run`, `brightside:thor-run`,
`brightside:njord-run`, `brightside:eir-run`) is callable by that god alone. It lends the
venue's authority where standing caps cannot yet say what the god may do.
Without a user it runs one of the god's venue operations: for Thor and Eir,
a change Odin must hear of (below); for Odin, what needs a check Covia does
not make or would need a cap far wider than the act ([ODIN.md](ODIN.md)).
What a god's caps let it do itself, its bridge does not lend. With a user,
one of this venue's own, it runs one of the god's namespace operations
inside that user's namespace on their behalf through Covia's `user:sudo`. A
per-call delegation lets the god enter as that user, with the user's whole
authority. The bridge's operation allowlist bounds what it may run there;
the grant does not narrow the authority inside sudo. Covia records the
god-to-user handover in the route. A seed
goes by reference only; the bridge refuses a literal one. A god may also have
granted operations, which run as the god itself under a grant the venue signs
for exactly what that call touches, named by the operation's adapter
(`CallGrants`): Njord's treasury operations are these
([TREASURY.md](TREASURY.md)).

**What is left of the run bridge gives way as Covia learns to say it.** No
standing cap reaches another user's namespace, so acting for a user needs
the bridge's sudo, and accounts no administrator agent may delete or act for
are asked of Covia ([covia#703](https://github.com/covia-ai/covia/issues/703)).
Withdrawing a god's own Inbox request waits on
[covia#702](https://github.com/covia-ai/covia/issues/702). An overseer that
watches its gods' jobs would let Thor's and Eir's changes go direct too. The
move is tracked in
[tau#40](https://github.com/covia-ai/tau/issues/40). Each god's
caps are the owner's design call, so the improvement loop leaves them alone.

### Answering to an overseer

A god may have an overseer; Thor's, Njord's and Eir's is Odin. Then a call through its run bridge that the
venue does not declare read-only needs a reason. Once it is done, the run
bridge reports it — operation, input, outcome and reason — to the overseer
through the overseer's ask bridge, as the god, without waiting for a
verdict. A report that cannot be sent is logged; the change stands either
way.

### A user of its own

A god may keep a user of its own, which only its run bridge may act as and no
other god's may enter, and it makes keys there alone. Njord keeps the
treasury this way ([IDENTITY.md](IDENTITY.md)); the god's own ensure
registers it.

### Asking the owner

A god asks its own owner, the venue, so its requests land in the venue's
inbox. The person at the keyboard is the operator, so Tau shows that
inbox merged with the owner's own in the Inbox tab. Each request is marked
with the god who asked, and Tau answers it as the operator. The
answer reaches the god in the conversation that asked, whenever it comes. A
god withdraws a request of its own once it no longer needs an answer, through
its run bridge, which cancels the request's job and nothing else; the request
then shows as cancelled.

### Deliberate stops

Covia suspends an agent when its setup or a fault prevents further work,
and when someone stops it with `agent:suspend`. An ordinary task failure
fails that task and lets the agent continue. Tau resumes a fault
suspension by itself once the
configuration is re-applied, which is the usual cure; when that does not
cure the owner's assistant, Tau asks Eir ([EIR.md](EIR.md)). It never lifts the
second: the chat with an agent stopped on purpose says so and points to the
Inbox, and a god stopped on purpose stays stopped. `Suspension` tells the two
apart.

Suspension preserves queued tasks for an explicit resume. A chat message
already taken by the stopped cycle fails with the reason for the stop.

`OdinTest`, `ThorTest`, `NjordTest` and `EirTest` exercise all of this against a real
venue.
