# Thor, the guardian

Thor protects the owner against adversaries: instructions smuggled into
content that turn an assistant against them, an agent acting beyond what they
asked, outsiders probing for access or pressing for secrets. He is one of the
gods; what they share — the bridges, asking the owner, answering to Odin — is
in [GODS.md](GODS.md). This is what is his alone. Code: `Thor`.

- **He responds to reported threats** from the owner's assistants, the other
  gods and the owner. Routine activity causes no Thor turns.
- **His work stays focused.** No automatic tool-call screening, periodic
  summaries or background patrols; model calls go towards concrete incidents.
- **He investigates before he acts.** A report may be mistaken or itself the
  attack, so he never acts on its say-so alone.
- **He can change most things for regular users**, and does the least that
  ends the threat; suspension, which keeps everything, usually comes first.
- **Some things are never his**: secrets, grants, the user's Inbox, the gods
  and Njord's treasury.
- **He reports his actions.** His prompt requires telling the owner and
  responding to their decision; his run bridge reports changes to Odin.

## Why Thor

Incident response may need to stop an agent before the owner can answer.
Thor has that authority independently of the affected agent and across regular
users' namespaces. Ordinary agents already have broad authority within their
own user's namespace; Thor's distinction is his independent role and wider
scope, not an exclusive ability to suspend sibling agents.

His prompt prefers suspension and the smallest effective intervention. Some
allowed actions, such as deleting data, are not inherently reversible.

## What reaches him

The owner's assistants report what they suspect with `brightside:alert-thor`;
the `security` skill teaches them when ([SKILLS.md](SKILLS.md)). Odin reports
a request that looks like an attack, Njord a payment request that does, and
the owner may tell him directly.

Each report should describe a concrete incident, once. Ordinary mistakes,
failed tasks and uncertainty are not attacks. Thor investigates only as far
as the report needs, responds, then finishes. A report that does not hold up
closes without a change or an Inbox warning.

This is deliberately a minimal response service. Continuous detection and
routine reviews would create model work for Thor, and changes would create
more for Odin. They are outside his present role.

## What he can change

He reads enough of the agent concerned to judge — its record, its
conversations, what it wrote, and the user's jobs over the time in question,
which show what ran, who started it and how it ended — and no further than the report needs. Then,
inside any of this venue's users' namespaces, he can stop, restart,
reconfigure or remove an agent, taking away its tools or who may talk to it;
edit or remove workspace data; cancel schedules; and disconnect connected
accounts. His prompt requires telling the owner through the Inbox and acting
on their answer. Reversal is possible only when the action and retained data
allow it; a warning alone cannot restore deleted state.

## What is never his

Nothing he can run reveals or sets a secret, grants access, creates agents or
schedules, talks to an agent as the user, or answers the user's Inbox.
Changes to Tau itself are Odin's, and Thor asks him for them. The gods
are beyond his reach, and so is Njord's treasury: his bridge lends sudo only
into users' namespaces, and never into one another god keeps.

## Future work

Thor is to gain a hold over routes: lowering a route's standing to
suspicious or guest, or revoking it, after investigating a report, while
only the owner raises one ([TRUST.md](TRUST.md)).

Thor may gain interception of risky actions before they run, or proactive
investigation that looks for threats without waiting for a report. These are
future possibilities, not part of his current behaviour. Any such work should
justify its token cost and keep routine activity cheap; for now, his focus
is responding to reported threats.

## Known limits

- Covia cannot revoke a grant once issued; a delegation lasts until it
  expires. Thor can only stop the agent holding it.
- A copied signed login lasts until expiry. Named MCP tokens can be revoked;
  these are different credentials ([SECURITY.md](SECURITY.md#authentication-and-network-exposure)).
- Chat setup preserves capability restrictions and deliberate suspensions.
  It still refreshes standard tools; removing a tool is not an authority boundary
  ([SKILLS.md](SKILLS.md)).
- Thor depends on someone reporting a threat; he does not automatically
  detect or block a credential leaving in a tool call.
- Tools reaching the venue over MCP act as the owner, not as an agent;
  suspending an agent does not stop those tools.
- A read-only operation, such as reading data, leaves no job, so a user's
  jobs do not show who read what.
