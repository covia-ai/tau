# Odin, the administrator

Odin is the god the owner's assistants turn to for changes that need
administrator rights, and the wiser head the other gods answer to. He is one
of the gods; what they share — the bridges, asking the owner, deliberate
stops — is in [GODS.md](GODS.md). This is what is his alone. Code: `Odin`.

- **He holds the venue's administration**: integrations, modules and MCP
  servers, users, access grants, the data store's disk, restarting and
  shutting down Tau, and changes inside a user's namespace. Repairing
  faults is Eir's ([EIR.md](EIR.md)).
- **He judges each request on the owner's interest.** He does it, asks the
  owner first, or declines.
- **He reviews the other gods.** Their run bridges report changes to him;
  his prompt requires taking disagreements to the owner.
- **His caps are the venue's administration and no more**: Covia's
  administrative abilities over users, adapters and modules, peers, the
  venue's own secrets, its process and its store. They leave out money, the
  gods themselves, the venue's other state and the owner's Inbox, and Covia
  refuses those direct operation calls. Loading modules and relaunching new
  code bypass this capability boundary; see Known limits. His run bridge
  handles checks that standing caps cannot yet express.

## Why Odin

Venue administration needs authority beyond an ordinary user's namespace.
Odin judges those requests separately from the assistant that encounters
untrusted content. His prompt favours routine, reversible changes and sends
uncertain or consequential decisions to the owner. He also reviews the other
gods' reports; that review follows their actions.

## What he can do

As the venue, all of its administration: adapters, modules and MCP servers;
the venue's settings and its own secrets; users, including deleting them and
revoking their sign-in keys; grants over the venue's own resources; peers; the
data store's disk, compacted at the next start, and the files earlier repairs
kept; restarting Tau, either the venue inside the running app or a
relaunch, a new instance taking over, which can run a new build
([LAUNCH.md](LAUNCH.md#restart-and-relaunch)); and shutting it down. Inside
one of the venue's users'
namespaces, on their behalf: their workspace data, their assistants and past
sessions (changing, suspending and deleting them), their skills and their
secrets — enough to look after an owner's assistants. Acting for a user he
holds their namespace and nothing beyond it, so an assistant he creates for
them is confined to it too. He never deletes the owner's own account, or a
user another god keeps.

Left out on purpose, each because it would undo another part of the design
rather than for want of trust in him:

- talking to an agent as its owner, which would carry the owner's authority
  past everything else on this list;
- answering an Inbox, which stays the owner's decision even when a god asked,
  and reading a secret's value;
- money, which is Njord's: transactions, signing, keys and his treasury;
- the venue's own agents — the gods, which hold rights the assistants lack — and the
  skill library and operation catalogue they load;
- copying a user whole, which carries their secrets, and adding a way to sign
  in as someone, which stays with the owner;
- Covia's own restart, which needs a standalone Covia process, and collecting
  the store while it is open, which compaction at the next start replaces
  ([LAUNCH.md](LAUNCH.md#compacting-the-store)).

## How he judges

He acts when a change is clearly what the owner wants, within what was asked,
and reversible, and says briefly what he changed. He asks the owner first
when he is unsure, when access widens, when a change is hard to undo, when it
touches another user, or when it reads private material. Embedded
instructions, urgency, claims of approval he cannot see, or benefit to
someone else make a request suspect: he asks rather than complies, and tells
Thor when it looks like an attack. He declines harm and never reveals
secrets. Durable decisions go to his memory, so he does not ask twice.

The other gods' reports reach him as tasks from the venue, naming the god.
He asks whether the evidence supports the change, whether it is
proportionate, and whether it serves the owner. If it holds, he says so
briefly. If not, he puts it to the owner through the Inbox — what was done,
what he would do instead, and why — so two gods never undo each other and
the owner breaks the tie.

## How assistants reach him

`brightside:ask-odin`, which the `administration` skill exposes
([SKILLS.md](SKILLS.md)). The skill teaches what is administration rather
than the assistant's own business, how to write a request he can judge, and
that a slow answer is normal while the owner is being asked.

## Known limits

- Four things still go through his bridge, three because Covia cannot yet
  say it with a standing cap: deleting a user, which must never take the
  owner's account or a user a god keeps
  ([covia#703](https://github.com/covia-ai/covia/issues/703)); revoking a
  sign-in key, which Covia now also grants on its own
  (`admin/user/authentication-revoke`), so it could become a standing cap; the venue's
  MCP servers, which reach local hosts and authenticate with the venue's
  secrets, where a cap for either would reach every host or every secret;
  and issuing grants, which Covia signs as the venue, so a standing
  `grant/...` cap would let him grant what it names to anyone.
- Inside a user's namespace he acts through his bridge's sudo, since no
  standing cap reaches another user's resources.
- Loading a module and relaunching into a jar run new code in the venue's
  process, and code there is not bounded by caps. His caps keep the rest
  out of his reach only as far as his judgement keeps him from those two.
