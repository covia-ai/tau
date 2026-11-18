# Design guidelines

Tau is a personal agent running on its owner's computer. These principles
guide product decisions; they are not claims that every intended safeguard
has been implemented.

- **The owner stays in control.** Make the scope and consequence of an action
  clear. Ask for decisions that belong to the owner, and respect deliberate stops.
- **Local state, explicit connections.** Keep the owner's state locally and
  explain when a model provider or integration receives it.
- **Plain language in everyday flows.** Use familiar words for tasks; reserve
  venue concepts for the technical views that need them.
- **Accessible machinery.** Keep settings, logs, identity and model context
  inspectable without crowding the main flow.
- **Useful defaults.** An empty configuration is valid. Default to authenticated
  loopback access and offer the echo bot when no model key is available.
- **Learn without obscuring authority.** Skills and memory help an assistant
  work; permissions must be enforced independently of its instructions.

The product is **Tau** (τ, two times π). The repository, Java packages,
build artefact and default data directory use `tau`. Configured venue and
assistant names are independent of the product name. Existing operation
addresses remain usable so stored calls can migrate individually
([ARCHITECTURE.md](ARCHITECTURE.md#operation-addresses)).

## Language and presentation

Prefer “your assistant”, “your name”, “memory” and “starting up”. An error
should explain the failed action and a useful next step; put diagnostic detail
in the logs or an expandable view.

Settings pages show controls, not introductory essays. Place an explanation
beside the control it explains. Keep a visible warning when someone needs to
understand a consequence before acting; fold one-time setup instructions away.
Use grouping, labels and icons to make choices legible rather than adding
more tiles or banners.

## Identity and first use

The owner chooses a display name. Renaming it must not change the principal
that owns their data ([IDENTITY.md](IDENTITY.md)). First run establishes the
vault and recovery phrase before chat. Returning users unlock and reach a
clean Home chat; saved conversations remain available
([ONBOARDING.md](ONBOARDING.md)).

## The assistant

Keep identity, runtime facts and working methods separate: the system prompt
defines the role, per-turn context supplies current facts, and skills supply
task-specific methods. Memory holds useful preferences and decisions rather
than duplicating the conversation archive ([SKILLS.md](SKILLS.md)).

The assistant can author skills and work on Tau's source through Claude Code.
Changes should be bounded, reviewable and tested. A new build takes effect
only when the owner, or Odin on their behalf, relaunches into it
([SELF_IMPROVEMENT.md](SELF_IMPROVEMENT.md)). Requests for decisions belong in
the [Inbox](INBOX.md); exposing a tool through a skill does not itself require
or establish approval.
