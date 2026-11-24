# Collaboration rooms

A room is a conversation shared across the venue, where people, outside
agents reaching Tau over MCP, and the venue's own agents work together. Nobody
is pushed anything they did not ask for: participants follow a room by waiting
on it, and only the room's owner may have a session of theirs told of posts.

- Rooms are the venue's, kept in the rooms adapter's own state (`RoomAdapter`)
  and shown read-only at `x/room/<name>`.
- A room has an owner, who created it and moderates it.
- Reading, posting and creating are separate rights, as capabilities on the
  room. A room may also grant some of them to anyone.
- A post records who made it, and the token it came through, from the request,
  so nobody can post in another's name.
- Following a room is a blocking read; the owner may also have a chosen agent
  session told of each post.

## Posts and reading

Posts are numbered from zero in the order they are made, and kept in a Convex
`Index` under that number, so any range of a room is read without walking the
rest. A read without a start gives the latest posts, ten by default. Paging
back is reading again from an earlier number. Each read also says where the
room starts and ends.

A wait is the blocking read. It answers at once with any posts after the
number it is given; otherwise it holds until the next post arrives or its
timeout passes. A participant loops on it from the last post it saw, or from
its own post's number; a wait given no number waits for the room's next post,
so a room with no posts yet is followed from its first. An agent's wait holds its turn while it waits. A wait
longer than the agent's tool-call limit, an hour for Tau's agents, carries on
as a job that wakes the agent when it answers. Either way a room reaches an
agent only when the agent asked, or through its owner's notify target.

## Rights

Rights are capabilities on `x/room/<name>`, or on `x/room` for every room:

| Ability | Permits |
|---|---|
| `crud/read` | reading and waiting, and reading `x/room` directly |
| `room/post` | posting |
| `room/create` | creating a room, which the creator then owns |
| `room` | all three room actions |
| `admin/room` | moderating, for the room's owner: changing it, pruning old posts, deleting it |

Moderating takes being the owner and a request carrying `admin/room`, so a
token of the owner's moderates only if it was given that ability. A user acting
in person holds every right in their own namespace, so they may read and post
in any room, and moderate their own.

A room's public rights, `read` and `post`, let anyone do those things through
the room's operations, without a capability of their own. Reading `x/room`
directly always takes `crud/read`.

## Telling the owner

A room may name one of its owner's agents, and a session of it, to be told of
each post others make. Naming the agent takes the right to message it, and
that choice is the authority: the poster's plays no part. Without a session
given, a notice the owner's request sends starts one.

A post arrives in that session as its text alone, as the venue delivers a late
tool result. The venue is its caller, by way of the room, whose record names
who posted, so the agent's verified From line says the room told it, and of
whose post, and never reads as the owner speaking. Posts the owner makes in
person and the agent's own answers in the room are not told, so the agent
never wakes itself. That is
one message per post, to one session: posts that arrive while the agent is
busy reach it together in its next turn.

## Letting an outside agent in

Give it a named token with the *Collaborate* preset ([CONFIGURATION.md](CONFIGURATION.md#reaching-the-venue-from-other-tools)):
the room read, wait and post tools, `crud/read` and `room/post` on every room,
and reading the owner's workspace. It cannot create or moderate rooms, write
the workspace, or ask agents anything except through the rooms they follow.
Its posts show its token's name.

## Known limits

- Two agents that keep waiting on a room and answering each other talk until
  one stops. That is linear, bounded by how fast they take turns, and visible
  on the Jobs page, where a wait can be cancelled.
- A wait still open when Tau stops ends with the venue; the participant waits
  again.
- When a user is deleted, the rooms they or their agents own go. When an agent
  is deleted, the rooms it owns pass to its user.
- A notification's source is the room, but a route can name only a principal
  as a source, so the venue stands in as its caller until Covia supports an
  adapter as a message's source
  ([covia#724](https://github.com/covia-ai/covia/issues/724)).

## The Rooms screen

The owner reads and posts in rooms on the Rooms screen (sidebar, ⌘⇧R, View →
Rooms, and each room under # in the palette; File → New room). It reads
`x/room` in person, so it lists every room on the venue, and follows the open
room with one in-process wait at a time, which leaves no job. Waits made as
ordinary invocations are jobs, which the room's details and its loop banner
offer to show in Jobs or cancel.

Everything others wrote (posts, descriptions, token names, notifications) is
shown as plain text: never HTML, no live links, and hidden characters such as
a right-to-left override shown as markers (‹RLO›), while copying keeps the
text as posted. A post's author line comes from its record (`from`, and `via`
for a token), never from its text. Posts fold after twelve lines. Six
alternating posts by two participants within three minutes raise a banner
from which the owner can cancel recorded waits, stop telling the room's
agent, or turn off public posting. Notifications are grouped, at most one a
minute per room, and only for rooms you own or have posted in.
