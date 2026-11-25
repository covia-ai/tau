# Identity

Tau's venue, users and agents are named from one root identity: a 32-byte
Ed25519 seed. Provider credentials and treasury signing keys are separate
secrets; the root seed does not replace them.

- **The venue** is the seed's `did:key`: it signs tokens, owns the store's
  encryption key, and is what other venues will know Tau by.
- **The owner** is a user of that venue, `<venueDID>:u:<slug>`, with no key of
  its own: the venue signs for it, and the chat acts as it so every turn is
  attributed to the owner rather than to "the operator".
- **An agent** is a sub-principal of its owner, `<userDID>:g:<agentId>`,
  bounded by its capabilities, never by its name.
- **The slug is fixed once chosen.** Renaming yourself changes the display
  name only; it never switches you to a different, empty agent.

| Principal | Form | Key | State |
|---|---|---|---|
| Venue | `did:key:z6Mk…` | the identity seed | `venue.etch`; the `v/` catalogue |
| User (the owner) | `<venueDID>:u:<slug>` | none — the venue signs for it | `w/`, `g/`, `s/`, `h/` under the user |
| Treasury | `<venueDID>:u:njord--treasury` | none — reached only through Njord's bridge | the signing keys in its `s/` |
| Agent | `<userDID>:g:<agentId>` | none — acts under the owner, capability-scoped | `<userDID>/g/<agentId>`; `n/` while it runs |
| Anonymous | `<venueDID>:public` | none | none; public access is off by default |

## Venue identity

The seed comes from the BIP39 recovery phrase chosen or imported at onboarding
(key hierarchy in [SECURITY.md](SECURITY.md)). The venue's DID is the
`did:key` of its public key, fixed the moment the seed exists and reproduced by
the phrase on any machine; Tau defaults to loopback and does not assign a
`did:web` alias. The venue key signs the bearer tokens the venue trusts,
identifies the venue to other venues ([NETWORK.md](NETWORK.md)), and derives
the key that encrypts the store. It is Convex-capable, but Tau does not
yet create or link a Convex account to it.

## User identity

The owner picks a **name**. `Identity` keeps two forms apart: the display
name exactly as typed, which the UI shows and the assistant says; and the
slug, a lower-case DID-safe label used only to form the principal.
The identity record holds name, slug and the full DID, and lives in the venue
store in Tau's adapter state ([APP_STATE.md](APP_STATE.md)), so the
recovery phrase brings it back with the store. The DID is pinned when the
home venue first launches and must equal `<runningVenueDID>:u:<slug>` on every
later launch, so a copied or edited profile cannot quietly move the UI to
another principal.

The user has no key: the owner-controlled venue key is its signing authority.
Off-process tools act as the user with a venue-signed token whose subject is
the user DID ([CONFIGURATION.md](CONFIGURATION.md)). Model API keys are kept
per user in the venue's encrypted secret stores, so each user's agents resolve
that user's key. *Settings → Identity* shows the name, the user DID, the home
venue DID, the venue's public key and, behind the passphrase, the seed; the
identicons beside them derive from the venue's public key and are a comparison
aid, not a substitute for checking the value.

**Switching user.** *Settings → Identity → Switch user* switches the app to
the venue principal itself for the session: the agents pane lists the venue's
own agents — [the gods](GODS.md), Odin first — the chat talks to them and the Inbox is
the venue's. The operator has a display name of its own, kept in
the same record as `operator`; it is a label only, since the operator is the
venue itself and has no slug or user DID. Every launch starts as the user.

**The treasury** is a user too, kept by Njord, the treasurer
([NJORD.md](NJORD.md)). A user and its agents use only their own secrets, so the
signing keys in the treasury's store can be used by nothing but a context
acting as the treasury, and Tau's bridges reserve that context for Njord.
This is a venue permission boundary, not isolation from native code running
in the same process ([SECURITY.md](SECURITY.md#assumptions-and-limitations)).
Its slug, `njord--treasury`, is one no name can produce, since `Identity`
collapses runs of separators.

## Agent identity

An agent is `<userDID>:g:<agentId>`, the same `g` namespace its record lives
in. A bare path in its configuration, `w/skills` or `n/memory`, still means
the owner's namespace: the DID says who acted, not whose data a path names.

The default agent is `Tau` (`chat.agentId`), including when this key is omitted.
Set the key explicitly to use an existing assistant's id and retain its memory
and conversations. Without an explicit system prompt, the default persona uses
the configured agent id as its name.
A new agent's id is the
name the owner typed, with anything a path cannot carry turned into `-`, and it
never changes. The display name is the one the record sets; without one, the
standard agent shows as the app's name, `Tau`, whatever its id, and any other
agent as its id (`AgentRef.displayName`). What
makes an agent itself is its record: its system prompt, its model, its private
memory at `n/memory` (across sessions, not shared with the owner's other
agents), its sessions, and the skills it can see. The owner writes a new
agent's system prompt in the New agent dialog, starting from a template, and
can change it later on the agent's Agent tab; the standard agent's follows
Settings and `config.json`, and a god's is fixed. What it knows of the owner
in `w/memory` it shares with their other agents. Each turn, the read-only
`brightside:context` load tells it who its owner is.

An agent has no independent identity key. It acts within its configured
capabilities and its owner's authority, which may include using that owner's
credentials. Tau's default owner agent holds the scoped assistant set on a new
install (below); loading skills neither narrows nor widens it.

### What an agent may do

An agent made in the New agent dialog starts with its template's caps
(`AgentPermissions`), not Unrestricted. Every one may run operations (each
still checks what it touches), read the workspace, the file roots and its jobs,
keep its own notes and the shared memory, load skills, and ask the owner
through the Inbox. Personal assistant adds writes and deletes in the workspace
and the file roots, and handing work to the owner's other agents; Research
adds workspace writes; Software development adds file-root writes and deletes
and workspace writes; Writing & editing adds workspace writes and deletes;
Blank slate adds nothing. Every write and delete grant is gated by
`v/ops/tau/confirm-change` ([SKILLS.md](SKILLS.md#deletes-and-overwrites-wait-for-you)).

Covia has no grant for the internet apart from running operations: `http:get`
and `http:post` check only that the agent may run them. Every agent that may
run operations may therefore fetch from and send to the internet, whatever its
template; the venue's address policy keeps it off this computer and the local
network. The agent's page says "Run operations, the internet included", so no
template claims a limit Covia does not enforce. Keeping an agent off the
internet would need `invoke` scoped to a list of adapters, since `mcp`, `a2a`,
`grid` and model operations dial out under the same grant; that is not done.

**The main assistant** of a new install gets the scoped assistant set
(`AgentPermissions.ASSISTANT`), derived from what its skills, tools and loads
call, each run under the set by `AssistantScopeTest` and every shipped tool
placed by `AssistantInventoryTest`: the base above; gated writes and deletes
in the workspace, the file roots and the owner's drives (the vault among
them); the owner's stored keys and connected accounts, used without being seen
(connections and Moltbook), listing them, and storing a new key (replacing one
asks); making agents (asks when it gives one permissions), asking and
messaging them, and changing them and their conversations (asks before
changing what one may do, or deleting); reading the Inbox; running and
cancelling the owner's schedules; reading, posting in and creating rooms;
storing assets; listing and revoking the owner's access tokens (revoking
asks); and Claude Code in the owner's projects. The gods are asked through
Tau's bridges, which carry their own grant, and a relaunch is Odin's to run,
so neither needs more. It cannot widen its own caps: Covia refuses caps their
author does not hold, and an agent it makes without caps gets none.

What the set does not cover, listed on the agent's page as *Needs an extra
grant*: copying an agent (`agent:fork`), deleting a stored key, seeing a
stored key's value (no agent ever can), adding or removing tool servers,
disconnecting a connected account, handing out access (`ucan:issue`), reading
the venue's settings, and running the venue (people, adapters, restarts and
relaunches), which Odin does when asked. What it does without asking: making
something new, its own notes and the shared memory, running or cancelling one
of the owner's schedules, and Claude Code's own edits inside its project.

*Unrestricted* is an explicit, labelled choice: in the New agent dialog, and
for the main assistant on its page (*Make it Unrestricted…*), which says that
its deletes and overwrites will not ask, scheduled runs included. An
assistant from an older install keeps the caps it has; its page offers *Use
the scoped set…* in one click, listing what changes. The agent's page (*Agent
info*) lists what it may do in plain words. Caps are an owner's decision:
setting up an existing agent never changes them.

Covia can issue a UCAN to an agent DID after an Inbox approval, but the agent
cannot yet present that grant on later calls (see below). Tau's `hitl` skill
therefore offers no grants. An Inbox answer is not a general interception or
approval mechanism for tool calls. Per-agent keys and accounts are a proposal
in [NETWORK.md](NETWORK.md).

## Known limits

- One venue serves one owner
  ([tau#3](https://github.com/covia-ai/tau/issues/3)).
- An agent cannot yet present a grant the owner approved: the token reaches it
  as text in its conversation, not as authority it holds
  ([covia#447](https://github.com/covia-ai/covia/issues/447)), so the `hitl`
  skill offers no grants. An approval that runs one exact call is
  [covia#592](https://github.com/covia-ai/covia/issues/592).
