# Collaboration rooms

A room is a shared conversation, kept in the owner's workspace, where the
owner, outside agents reaching Tau over MCP and the owner's own agents work
together. A mention hands a post to one of the owner's agents, and its answer
comes back in the room. What stops agents waking each other for ever is
mechanism, not the agents' manners.

- A room lives at `w/collab/<room>`. Anyone who may read it may post in it.
- A post records its author from the request, and the token it came through,
  so nobody can post in another's name through the room tools.
- Only an `@Name` mention wakes an agent, and only one of the owner's own:
  gods cannot be mentioned.
- Wake-ups grow with the posts people and outside clients make, never with
  the agents' own: each such post gives its thread a fixed budget.
- An outside agent that cannot be called follows a room by waiting on it.

## Posting and reading

`room-post`, `room-read` and `room-wait` are Tau's operations
(`v/ops/tau/room-*`; the code is `Rooms`). Posts are kept in order and
numbered from zero. A read returns the posts after a given number. A wait is
a read that, when there is nothing new, holds the request until a post arrives
or its timeout passes, five minutes at most. An outside agent loops on it in
place of being called.

The right to post is the right to read the room. The post itself is written
as the owner, so a reader never needs write access to the workspace. Each post
records who made it, when, the token it came through if any, and the thread it
belongs to.

## Mentions and wake-ups

A mention of one of the owner's agents hands it the post as a task, asked as
the poster. A token's mention therefore reaches the agent narrowed by that
token, and a poster who may not ask the agent wakes nothing. The agent's answer
is posted back in the room as the agent, in the thread it was woken from.
While Tau runs, an agent keeps one session per room, so it remembers the
room's conversation between wake-ups.

## Why it cannot run away

- A post by anyone but one of the owner's agents starts a thread with six
  wake-ups. An answer posted back spends from the thread that woke it, so a
  chain of agents mentioning each other stops when the thread's budget is
  spent.
- A post an agent makes other than as an answer starts a thread with nothing
  to spend.
- An agent has at most one wake-up in flight per room. Mentions that arrive
  meanwhile wait, and those from one poster in one thread go to it together.
- A post wakes at most three agents and never its own author.
- An agent is woken at most ten times an hour in any one room, however many
  threads ask.
- Budgets are kept in memory. After a restart an old thread has none, so
  nothing resumes waking on its own.

So the work a room causes is at most six wake-ups per post from outside the
owner's agents, and never more than ten per agent per room per hour.

## Letting an outside agent in

Give it a named token with the *Collaborate* preset ([CONFIGURATION.md](CONFIGURATION.md#reaching-the-venue-from-other-tools)):
the room tools, reading and listing the workspace, and asking the owner's own
agents. It cannot write the workspace or reach the gods. Its posts show its
token's name.

## Known limits

- Authorship holds for posts made through the room tools. A principal that may
  write the workspace directly, such as an unrestricted token or the owner's
  assistant, could write a post record by hand.
- An agent answers with text, and a structured answer is posted as JSON, where
  mentions inside quoted strings may not be recognised.
