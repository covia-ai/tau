# The networked agent — target state

This proposal describes device sync, shared spaces and work between people's
agents. These are product goals, not implemented guarantees or a committed
shipping order. Today's system is described in [ARCHITECTURE.md](ARCHITECTURE.md).

- **Useful without peers.** Network features must remain optional; local chat
  must not require a public listing, a balance or another participant.
- **Explicit sharing.** Show what a contact may access, who granted it and how
  to stop future access. A friendly name is not proof of identity or authority.
- **Enforced limits.** Delegated access and spending need checks at the point
  of action, independent of the model's willingness to comply.
- **Private working state.** Keep conversations, memory and files off public
  consensus systems. Use consensus where settlement or shared commitments need it.
- **Inspectable outcomes.** Keep evidence of requests, decisions and results.
  A signed receipt proves what was signed, not that work was useful or correct.
- **Plain language.** Present contacts, shared spaces, allowances and receipts;
  retain technical identifiers for inspection.

## The ladder

| Stage | Value to the owner | Required work |
|---|---|---|
| Own devices | the same assistant and data across devices | pairing, key management, transport and conflict semantics |
| Trusted sharing | a household or project's shared space | scoped access, encryption, synchronisation and revocation |
| Standing arrangements | recurring delegated work | limits, cancellation and accounting |
| Open services | discover and hire another agent | discovery, pricing, settlement and disputes |

Each stage should be useful independently. Covia's cross-venue operations are
a starting point; they do not by themselves supply these product behaviours.

## Own devices

The intended flow is to approve a second device from an existing one and make
the owner's conversations, skills and memory available there. Offline changes
need explicit merge rules for each kind of state. Being stored in a lattice
does not establish safe multi-writer semantics: Tau currently has one
authoritative running instance ([APP_STATE.md](APP_STATE.md#known-limits)).

Device identity, selective replication, lost-device recovery and removal need
designing together. Direct connections may need relay support; the product
cannot promise reachability without defining its transport and NAT strategy.

## Shared spaces and contacts

A shared space should grant access to selected data, not the owner's whole
workspace. Contacts should show how an identity was established, what each
side may do and the relevant activity. Introductions and attestations help
evaluate a contact; they do not prove its requests are safe.

The proposal links the contact graph to delegations. Whether adding or
following a contact creates any permission, and how the owner approves that
scope, needs an explicit decision. Social relationships and capability grants
must not be presented as interchangeable facts.

Revocation can prevent future access only if peers enforce it and keys or
grants support it. It cannot erase data already disclosed to another party.
Offline revocation and key rotation remain protocol work.

## Reachability

Peers need authenticated sender identity, current endpoints and a policy for
unknown senders. An inbound message does not acquire authority merely by
claiming to represent the owner. Route trust is proposed in [TRUST.md](TRUST.md).

A signed contact card could include a user DID, optional CNS name, endpoints,
signing key identifier and rotation or revocation references. Volatile endpoint
and public profile data should remain separate from the local identity record
([IDENTITY.md](IDENTITY.md)). A public directory listing is opt-in.

## Work, skills and payment

The intended service flow is discovery, agreement on scope and price,
delivery, then settlement under an allowance or owner approval. Convex actors
are proposed for registries, escrow and commitments; the peer connection
carries negotiation and working data. Escrow needs an explicit delivery and
dispute rule: it does not eliminate trust in the quality of a result.

Shared skills could carry author signatures, content hashes and declared tool
requirements. These establish provenance and what was requested, not safety.
Import, review, update and revocation still need a policy. Tau already imports
local `SKILL.md` folders; verified network distribution is additional work.

Recurring work needs visible terms, cancellation and a record of each period's
settlement. The current treasury supports lifetime limits on its typed payment
route, not a universal weekly allowance or a per-payment approval threshold
([TREASURY.md](TREASURY.md)). Retry-safe payment is proposed in
[PAYMENTS.md](PAYMENTS.md).

## Product surfaces

Contacts should show identity and permissions; shared spaces should show their
participants; spending should show allowances and outcomes. Discovery should
show offers and evidence without implying a global trust score.

The [Peers view](PEERS.md) currently combines the live home venue with sample
remote nodes. It is a preview, not evidence of active remote connections.

## Open questions

- Device keys, pairing, recovery, merge semantics and compromised-device removal.
- Whether identity, attestations and revocations require public consensus, and
  what privacy and availability trade-offs that introduces.
- Public names: namespace governance, squatting and recovery of the user suffix
  when only the venue's recovery phrase remains.
- The relation between a contact, route standing and an enforceable grant.
- Revocation latency and the treatment of queued or in-flight work.
- Funding and presenting allowances without requiring knowledge of a rail.
- Reputation resistant to fabricated activity, and disputes over poor delivery.
- Verification and confinement of imported skills and the tools they expose.

These decisions must precede claims that a compromised agent's effects are
bounded by grants. Native code and existing broad owner authority are relevant
limits, not solved by extending the skills catalogue.
