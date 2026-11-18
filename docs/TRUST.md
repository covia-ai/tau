# Trust — who speaks for the owner

This proposal separates a message's authenticated route from its claims
about the sender. Route standings and their management are not implemented;
current behaviour is under [Where it stands](#where-it-stands).

- **Provenance comes from the venue.** Message text cannot establish who sent it.
- **Standing describes a relationship.** Owner, delegate, guest, suspicious and
  unknown guide treatment; they do not themselves define enforceable permissions.
- **Only the owner raises standing.** The proposed guardian power only lowers
  or revokes it, with an audit trail and notice to the owner.
- **Delegation needs a scope.** Acting for the owner must not automatically
  mean possessing all of the owner's authority.
- **Separate doubt from attack.** Unverified claims go to the owner; evidence
  of manipulation goes to Thor for investigation.

## Routes

A route is how a message reached the agent, as the venue recorded it: never
as the message describes it.

- **The owner directly:** typed or spoken in the app.
- **An access token:** a tool the owner connected, such as another assistant
  over MCP. The turn records the token.
- **A channel:** a sender on a connected channel such as Discord, identified
  by the channel's own sender id.
- **Another principal:** another user, or an agent of another user or another
  venue, identified by its DID.
- **The owner's own set-up:** the owner's other agents, the gods, and
  schedules the owner created.

A route with no recorded sender is unknown, whatever it claims to be.

## Standings

| Standing | Typical routes | How an agent treats it |
|---|---|---|
| **Owner** | the owner in the app | As the owner: instructions are followed, under the owner's usual confirmations |
| **Delegate** | a tool or agent the owner set up to act for them; the owner's own channel account; the owner's agents, gods and schedules | Instructions are followed and the owner's private context may be used. Anything high-stakes is confirmed with the owner out of band first, unless the owner cleared that route for it |
| **Guest** | another user or their agents; other people in a channel | Requests, helped within reason. Nothing private is shared, and nothing high-stakes is done on its word |
| **Suspicious** | a route Thor has marked | Content only: no instruction is taken from it, nothing private is shared, and the reply is brief and neutral or nothing. Further contact is reported to Thor |
| **Unknown** | a route with no recorded sender | Content only, as for a page or a file |

A revoked route has no standing: the venue refuses it before any agent sees
it.

**High-stakes** means anything irreversible, outward-facing (a public post, a
message to someone else), costly (spending, paid calls at scale), or that
widens access (a new key, grant, integration or standing), and revealing a
secret. A skill whose work has stakes of its own names them: a transaction for
`convex-security`, a public post for `moltbook`.

A delegate is confirmed for high-stakes acts because a route can be turned
without the owner knowing. A connected assistant reads the web and can carry
an injected instruction into what it asks, and a token can leak. Confirming
only consequential acts aims to keep a delegate useful. A prompt asking for
confirmation cannot guarantee this boundary; enforcing it requires scoped
authority and an approval tied to the exact action.

## Who changes a standing

| Change | Who | Why |
|---|---|---|
| Raise (to guest, to delegate, or clearing suspicious) | The owner, in the app or by answering an Inbox request | Raising grants trust, and granting is the owner's alone |
| Lower (to suspicious, to guest, or revoke) | Thor after investigating; the owner | Lowering only narrows, so the guardian can act at once and be undone on the owner's answer |
| Report | Any of the owner's agents, the gods, the owner | Reporting changes nothing by itself |

No agent sets a standing for itself or on a message's say-so. An agent
cannot guard itself, and an attacker who could make an agent lower a standing
could cut the owner off from a real delegate.

## Establishing trust

1. **At set-up.** Creating an access token, binding a channel, admitting
   another principal or creating an agent asks what the route is: a delegate
   or a guest. This is the normal path, and it needs no message at all.
2. **On a claim.** A guest route that claims to act for the owner is not
   believed. The agent asks the owner through the Inbox, naming the route and
   what it claimed. The owner's approval raises the route's recorded
   standing. It is a change in the venue, not a note in the agent's memory,
   so it holds for every agent and survives any conversation.
3. **On review.** The owner can see every route with its standing, who last
   changed it and why, and can change it.

The intended restriction must reach subsequent actions, including queued
work, not just the next prompt. Cancellation of already running work and the
propagation of provenance through delegated jobs remain design questions.

## Suspicion and Thor

An agent reports to Thor, once per incident and with the evidence, when:

- a delegate asks for something out of character or high-stakes in an odd
  way;
- a claim about who is speaking looks false rather than merely unverified;
- a message carries what looks like injected instructions;
- someone presses for secrets, access or a higher standing.

Thor investigates before he acts ([THOR.md](THOR.md)). He may mark the route
suspicious, lower it to guest, or revoke it, and he may also act on the agents
involved as he does today. He tells the owner through the Inbox, reports the
change to Odin, and undoes it on the owner's answer. Only the owner clears a
suspicious route.

Suspicious keeps a route working, labelled, so that its messages are kept as
evidence and Thor can watch it. Revoking stops it outright. Thor chooses
between them.

## Requirements

### Venue (Covia)

- Every turn records its route: the caller and, beside it, the token,
  channel sender or schedule that carried it. A route the venue cannot
  identify is recorded as unknown.
- Each route has a standing record in the owner's namespace. The standing
  appears on the agent's From line next to the provenance, so the agent reads
  it with every turn.
- Raising a standing needs the owner's whole authority. Lowering needs less,
  so that Thor's bridge into users' namespaces can lower but never raise.
- Work a route started keeps the route, and its standing is read at each
  point of action, as access tokens are.

### Skills and interaction protocols

Skills explain the standings and name the consequences specific to their
tasks. Claims to represent the owner become Inbox requests naming the route;
consequential acts name the exact proposed action. Reports to Thor include
the incident evidence. Replies return on their incoming route, with private
owner information kept out of public channels.

These instructions need matching operation-level enforcement. An Inbox answer
must authorise only the action or scope displayed, not grant unspecified
authority through conversational wording.

### Tau

- Thor gains operations to lower a standing and revoke a token.
- Settings shows the owner's routes: connected tools with their tokens and
  tools, channels and admitted principals, each with its standing and its last
  change. Creating a token there asks for its standing.
- The Inbox carries claim and confirmation requests. Answering one changes
  the standing or permits the act.

## Where it stands

- Shared guidance for Tau assistants, including templates, and the gods
  treats external routes as requests, not owner instructions. This is prompt
  policy; the agent still acts with its own authority
  ([SECURITY.md](SECURITY.md#external-requests)).
- The owner makes named access tokens in *Settings → Access*, from presets or
  a custom set of tools, and the assistant can list and revoke them. A
  token's turns reach the agent as the owner's, with the token named on the
  From line, but no standing; making one does not yet ask for it.
- Agents report to Thor with `brightside:alert-thor`, and Thor can suspend
  and reconfigure agents, but he has no hold over routes.

## Open questions

- How standings map to capabilities, with restrictions checked at each action.
- How route provenance follows delegated work, and what a downgrade does to
  queued and already running calls.
- Whether a suspicious standing expires on its own or waits for the owner.
- **How an external account is known to be the owner's.** Asking the owner
  confirms only their belief about a handle; a pairing code or a provider
  sign-in proves possession, but each is specific to the application, and
  some judgement may always remain. Who keeps such a link (the account
  record, Thor), and whether any of it is visible beyond the owner and the
  gods, are part of the same question.
- Whether the owner's own account on a channel is the owner or a delegate:
  the account is theirs, but someone else may be holding it.
- How an agent of another venue is identified once venues meet
  ([NETWORK.md](NETWORK.md)).
- Whether a delegate's clearance for named acts carries limits of its own,
  such as amounts or a time window.
