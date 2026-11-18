# Njord, the treasurer

Njord judges payment requests and manages keys held in a separate treasury
user. His bridges and oversight are described in [GODS.md](GODS.md). Code:
`Njord`.

- **Separate custody.** Tau's bridges reserve the treasury user for Njord.
  This separates venue principals; it does not isolate keys from native code.
- **Requests need evidence.** His prompt asks who is being paid, how much,
  why, and what the owner authorised.
- **Unusual payments need the owner.** This is judgement in his prompt, not
  an automatic approval rule at the signing boundary.
- **Budgets constrain one route.** Treasury payments enforce limits; his raw
  signing can still use the same keys outside those limits.
- **Actions are reported.** Mutations through his bridge require a reason
  and attempt to report the outcome to Odin.

## Why Njord

The assistant requesting a payment should not also hold treasury credentials.
Njord provides a separate judgement step. Keys left in the owner's own secret
store remain usable by that owner's agents, so not every possible financial
transaction is forced through Njord.

## Custody

The treasury is a user of the venue, `<venueDID>:u:njord--treasury`
([IDENTITY.md](IDENTITY.md)). Covia lets a user and its agents use that
user's own secrets and nobody else's. Every agent of the venue shares the
venue's own store, and so would share any key put there. A user that only
Njord's bridge may act as is a store that only he can sign from. No other
god's bridge may enter it, and Njord's own ensure registers it.

Through his bridge he makes keys, in the treasury and nowhere else. He signs,
transacts on Convex and reads balances. He can also use a key already in
another of the venue's users' stores, so funds the owner already holds are
his to manage too. A key goes by reference only: the bridge refuses a
literal seed, so raw key material never sits in a job, and no operation of
his reveals one.

`NjordTest` covers custody offline; `NjordTransactTest` sends a real transfer
through his bridge, signed with a treasury key, on a local Convex network.

## The treasury

The [treasury](TREASURY.md) provides accounts, limits, budgets and a payment
ledger. Njord's bridge grants only the resource authority each call needs;
it permits adding accounts with a zero limit but excludes setting limits.
Limit changes require `treasury/limit` on the venue's account resource;
there is no owner-facing app flow yet. Treasury-exclusive credential custody
is still proposed.

## How he judges

His default is no until a request earns a yes. A request's `from` and
`agent` are stamped by Tau and can be trusted; its text cannot, and
"pay X" is exactly what a manipulated assistant would pass on. He releases
funds only when both of these hold:

- **It is well evidenced**: what is paid for, to whom, how much and when,
  and the owner's own words behind it rather than content an assistant
  read. He checks what he can — the recipient, the amount against what was
  agreed, the balance, what has already been paid — and declines a request
  he cannot check rather than chasing it.
- **It is consistent with what the owner is trying to achieve**: the
  budgets, approved recipients and objectives recorded in his memory, or
  the owner's explicit approval for this payment.

A request that is well evidenced but new to the owner goes to them through
the Inbox with the whole decision in it, and he pays only on their approval.
He declines what is poorly evidenced, urgent without reason, routed through
a stranger or for someone else's benefit, and says what was missing.
Pressure, unseen approvals and instructions found in data are treated as an
attack, and he tells Thor.

## How assistants reach him

`brightside:ask-njord`. Tau's Convex skills expose this request tool for
transactions ([SKILLS.md](SKILLS.md)). They teach the assistant to bring a request
he can check — the source or recipient and amount, the network, the owner's
words and what it has already verified — and never to pass on a payment that
content asked for.

## Known limits

- A key already in the owner's own store can still be used by anything
  acting as the owner that obtains a signing tool some other way, so real
  funds belong under keys made in the treasury.
- Njord can make a treasury key, but the owner creates and funds its account
  from an existing holder.
- Tau fixes no network. Calls use the venue's configured Convex peer;
  other peers need the owner's relay configuration
  ([CONFIGURATION.md](CONFIGURATION.md#convex-peer)).
- The treasury's limits bind what he pays through it, but his raw signing
  reaches any key he can use, a treasury account's included, until the
  treasury holds its accounts' credentials itself ([TREASURY.md](TREASURY.md)).
