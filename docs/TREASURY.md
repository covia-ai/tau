# The treasury

The treasury records accounts, owner-approved spend limits, budgets and
payments. The current implementation supports CVM on Convex. Code:
`tau.treasury`; Njord's role is in [NJORD.md](NJORD.md).

- **Limits constrain the treasury payment route.** They do not yet constrain
  Njord's raw signing, which can use the same account keys.
- **An account has a lifetime spend limit.** Budgets allocate that allowance;
  they neither move funds nor guarantee a sufficient network balance.
- **Authority and accounting are separate checks.** A caller needs a capability
  on the resource, and a payment must fit its budget and account limit.
- **Record before sending.** The budget draw and pending ledger entry are
  durable before the rail is called.
- **Uncertainty retains the draw.** Only a confirmed failure returns the amount
  to the budget. Pending and unknown payments need reconciliation.

## Why a treasury

A typed payment names an amount and recipient, so code can enforce a spending
limit. An arbitrary contract call cannot reliably be reduced to those fields.
The treasury is intended to separate bounded spending from raw signing;
credential isolation is still missing, so this is not yet a hard cap on all
money Njord can move.

## Rails, currencies and accounts

A rail moves money on a network or payment system; a currency identifies what
moves. Each account holds one currency on one rail and has its own limit.
The rail reads balances, submits payments and reports their outcomes.

The wider design allows bank rails, tokens and other currencies, using
standard asset and account identifiers where available. Only Convex CVM is
implemented. Account credentials currently live in Njord's treasury user,
where his raw signing operations can also use them.

## Spend limits

An account starts with a zero limit. The limit is the total approved spending
over the account's life, not a daily or monthly allowance. Lowering it stops
further payments beyond the new limit without automatically cancelling budgets.

Njord can register an account through his bridge, initially with no spending
allowance. Setting its limit requires `treasury/limit` on the venue's account
resource; his bridge cannot grant that ability. There is no owner-facing
treasury control or Inbox approval that applies a limit increase.

## Budgets

Each budget belongs to one account. Creating it reserves part of that account's
limit; creating another cannot overcommit the limit. A budget can be lowered
only to at least what it has drawn. Cancelling it releases its unused allowance.

Budgets may exceed the funds actually held on the network, so an allowed
payment may still fail for insufficient funds. Moving budgets between accounts
is proposed, not implemented.

## Paying

`pay` takes a budget, recipient, amount and reason. It checks the caller's
authority, records the draw and a pending ledger entry durably, then calls the
rail. The outcome is:

- **Paid:** the draw remains and the rail's result is recorded.
- **Not paid:** the rail confirms nothing moved; the draw is returned.
- **Unknown:** the rail cannot establish the outcome; the draw remains.

A process stop can also leave an entry pending. There is no automatic
reconciliation or duplicate-payment protection: repeating `pay` is a new
payment. The proposed payable model addresses retries in [PAYMENTS.md](PAYMENTS.md).

## Grants

Accounts and budgets are venue resources under `<venueDID>/treasury/`.
Covia capabilities combine a resource path and an ability. Njord's bridge
obtains a short-lived grant for the particular account or budget a call needs;
it cannot obtain a grant to set limits.

| Ability | Scope | Purpose |
|---|---|---|
| `treasury/read` | treasury resources | read accounts, budgets and ledger |
| `treasury/spend` | a budget | pay from it |
| `treasury/budget` | accounts collection or an account | register accounts or manage their budgets |
| `treasury/limit` | an account | set its limit; excluded from Njord’s bridge |

The operation definitions and `TreasuryAdapter` own exact call shapes and
checks. The venue principal retains authority over these resources.

## Operations

The current operations are `account-add`, `account-list`, `limit-set`,
`budget-create`, `budget-lower`, `budget-cancel`, `budget-list`, `pay` and
`ledger`. All check venue-resource authority. Njord's bridge exposes every
one except `limit-set`, with a grant for the resource each call needs.

## Njord's place

Njord judges requests and uses budgets where possible. Mutating calls through
his bridge require a reason and attempt to report their result to Odin. That
review follows the action; it is not a transaction approval or rollback.

## Not built yet

- Owner controls for registering accounts and changing limits, outside venue
  operations, including an Inbox request to raise a limit.
- Credentials held exclusively by the treasury and unavailable to raw signing.
- Payables and paying by a durable payable ID ([PAYMENTS.md](PAYMENTS.md)).
- Reconciliation of pending or unknown payments.
- Moving budgets between accounts, additional rails and currencies.
- Delegation to assistants that can present their own grants
  ([covia#447](https://github.com/covia-ai/covia/issues/447)).

## Not yet decided

- How credentials enter treasury custody, and how existing keys migrate.
- How contract calls with financial effects fit typed limits.
- Whether budgets expire or limits can renew periodically.
- How owner-only controls remain separate from native code able to run inside
  the same JVM ([SECURITY.md](SECURITY.md#assumptions-and-limitations)).
