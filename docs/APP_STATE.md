# Tau's own state

Tau keeps what is its own, and owned by no Covia region, in its
adapters' state inside the venue. That state is a lattice value at
`:adapters/<name>` in the venue store, and each adapter owns its shape. Covia
keeps conversations, agents, skills, secrets and scheduled work. DLFS keeps
files. Tau's adapters keep the rest. How adapter state works is
Covia's (`venue/docs/ADAPTERS.md`, "Private state and user-managed storage",
in the Covia repository).

- **One store.** Adapter state is encrypted with the venue store, backed up
  with it and recovered by the recovery phrase. It commits in the same
  transactions as users, agents and secrets.
- **Shaped by Tau.** Any CAD3 value, behind the adapter's own methods.
  Nothing outside the adapter reads or writes the raw value.
- **The installation's part and each person's part.** Installation-wide
  values sit at the top. A person's sit under `users/<did>`, written through
  the person's own view, so a write racing their deletion is refused, and
  they are removed when the person is deleted.
- **Readable by their agents where that helps.** `x/brightside` shows a person
  their own part, read-only, through the ordinary read tools.
- **Separate application state from external files.** Pre-launch settings,
  vault envelopes, logs and the owner's ordinary files remain outside the
  store ([CONFIGURATION.md](CONFIGURATION.md#where-things-live)).

## What it holds

| State | Owning adapter |
|---|---|
| Identity, model, startup checks and app preferences | `TauAdapter`, retaining the `brightside` state id |
| Per-principal Peers map settings | `TauAdapter` |
| Moltbook account metadata | `MoltbookAdapter` |
| Treasury accounts, budgets and payment ledger | `TreasuryAdapter` |

The adapters own their schemas. Call their methods rather than duplicating
storage keys in views or operations.

The skill-feedback backlog stays in the person's Covia workspace, because the
assistant reads and writes it there with its ordinary tools. Secrets stay in
the person's secret store; adapter state holds no credentials.

File roles and protection are documented once in
[CONFIGURATION.md](CONFIGURATION.md#where-things-live) and
[SECURITY.md](SECURITY.md#files-and-their-recovery-role).

## Rules

- **Methods, not paths.** Each value has a reader and a writer on its adapter
  (`TauAdapter`, `MoltbookAdapter`, `TreasuryAdapter`). Screens and
  operations call those; nothing else names a key.
- **A person's part through their view.** Reads and writes for a person use
  the adapter state bound to that person. Each adapter's user-deletion hook
  removes `users/<did>`.
- **Several changes, one transaction.** Values that must change together are
  written in one venue transaction, together with any secret or user change
  they go with. Nothing outside the venue is called inside one.
- **Saved before it is reported.** When the person is told something is
  saved, the venue's durability barrier comes first (`EmbeddedVenue.durable`).
- **Shapes are versioned.** A change of shape raises the adapter's
  `stateVersion` and migrates with `migrateState`, so an older store is
  brought forward at start-up and a newer one is refused.

## Known limits

- One venue is one authoritative running instance. Two of a person's devices
  editing apart do not merge part by part. Device sync waits on P2P in the
  venue ([covia#587](https://github.com/covia-ai/covia/issues/587)) and the
  venue's own replication rules.
- Until lattice subscriptions exist upstream, screens notice changes by
  comparing immutable values ([WATCHER.md](WATCHER.md)).
