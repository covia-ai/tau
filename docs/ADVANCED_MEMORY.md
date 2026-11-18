# Advanced memory — proposals for consideration

This proposal explores searchable episodic memory alongside a small curated
core. None of these extensions is implemented. Current memory behaviour is
documented in [SKILLS.md](SKILLS.md).

- **Keep the core small.** Preferences and standing decisions belong in context;
  detailed records belong in searchable history.
- **Retain provenance.** A memory should identify its source and when it was
  recorded. Repetition does not turn an untrusted claim into an owner instruction.
- **Make promotion explicit.** Moving an observation into persistent guidance
  requires a policy beyond the model deciding it is useful.
- **Prefer cheap recall.** Keyword search is the baseline; embeddings and model
  calls should justify their cost and data exposure.
- **Make changes inspectable.** The owner should be able to review edits and
  forget material by source, including derived items where traceable.
- **Schedule actions.** A reminder needs a scheduled request, not a memory entry
  that may happen to enter a future prompt.

## Today

Owner-shared `w/memory` and agent-private `n/memory` are edited with Covia's
memory tool and rendered into turns. Past sessions remain readable separately.
Memory entries have no source tracking, consolidation or expiry. Covia's pinned
context budget can truncate a long memory, so keeping it short matters today.

## Tiers

| Tier | Proposed role |
|---|---|
| Instructions | system prompt, runtime context and skills; outside memory consolidation |
| Curated core | small, budgeted preferences and decisions included in context |
| Episodic | dated observations and session evidence, retrieved explicitly |
| Prospective | scheduled requests or bounded event-triggered intents |
| Review | changes to the core and their sources |

## Provenance at the write path

Operations would stamp provenance from authenticated context rather than from
text claims. Owner statements, agent deductions, external material and system
requests need distinguishable origins; unknown origin must not become owner
authority. Recalled material must retain its original source rather than being
counted as fresh evidence.

Conservative turn-level taint after an external read is one option. It cannot
prove the semantic origin of a later statement, especially across history,
summaries and delegated work. Treating a promoted memory as trusted instruction
therefore needs a separate decision about validation and owner approval.

## Consolidation

A proposed pass first filters and ranks eligible candidates deterministically,
then uses a bounded model call to merge duplicates and propose changes to the
core. It would preserve sources, validate the size and extent of a rewrite,
detect concurrent edits, and keep a pre-image and review record.

Stale entries should be proposed for removal rather than silently forgotten.
The relationship between a consolidation writer, direct assistant edits and
explicit owner edits remains open. So do cadence, cost limits and approval:
“nightly” is an option, not an agreed default.

## Recall

Explicit search would retrieve episodic material as evidence, not instructions.
Keyword ranking can include recency and importance. Embeddings are optional;
the proposal favours local processing so indexing does not itself disclose
memory to a provider. A more expensive recall task would run only when the
request needs it and simple retrieval is insufficient.

Automatic injection would be limited to the curated tier. Trigger matching,
if added, needs limits on volume and frequency.

## The owner model

Preferences may need separate handling from factual observations: a superseded
instruction should not compete with its replacement. Runtime identity and
model-route facts remain in `tau-context`; memory does not redefine them.

## Remembering to act

Time-bound requests belong in the scheduler. Event-bound intents would need a
scope, expiry, cooldown and firing budget. Unclear aspirations remain material
for review rather than automatic actions.

## Compaction

A pre-compaction pass could preserve useful observations in episodic memory.
It cannot guarantee the model notices every fact. Summaries must retain their
source provenance and must not bypass the promotion policy.

## Owner surfaces

A memory view could show the core, sources, review history and episodic search.
Forgetting by source should account for derived entries and indexes; deleting
one visible row is not necessarily complete erasure. Sensitive or uncertain
promotions could go to the Inbox.

## Costs to weigh

- Promotion authority and the role of owner approval.
- Provenance across tools, summaries, agents and mixed-trust turns.
- Consolidation cadence, token budget and concurrent editing.
- Retention, expiry and deletion of source material and derived indexes.
- The extra complexity of multiple memory tiers in the UI.

## Known limits

- A searchable tier would be Tau's responsibility; Covia's memory design keeps
  memory small and in context
  ([covia#429](https://github.com/covia-ai/covia/issues/429)).
- Confirmation of destructive memory operations remains open
  ([tau#16](https://github.com/covia-ai/tau/issues/16)).
