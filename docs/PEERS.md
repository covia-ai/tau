# Peers — from a preview to live nodes

How the Peers tab gets from sample data to the real network: what Tau
holds about other nodes, where that comes from, and how the map's own settings
are kept apart from it. The model is `tau.peers`; the map and its
screen are `ui/peers`. The home venue and its agents are live; remote nodes
are samples. Local service and P2P sources remain proposed. The networked product it serves is in
[NETWORK.md](NETWORK.md).

- **Two structures, never mixed.** *Peer data* is what is known about other
  nodes, as a source reports it. *Map settings* are how this installation
  chooses to draw them: orbit sizes, where a body was dragged, the view.
  Settings refer to nodes by DID and hold no facts about them.
- **Assume little about a node.** A node is a DID, a display name, what it is,
  how it relates to you, its status, what it belongs to, and an open set of
  facts. Everything else is expected to change as the network's records do.
- **One source, many kinds.** The map reads peer data from a source: the
  live home venue plus samples today, then local services and the P2P network. Which orbit a node
  sits on is a setting over its relation, not code.
- **Settings are local and disposable.** They live with this installation,
  never travel to peers, and can be reset at any time without touching
  anything real.
- **Live first where it is honest.** The services Tau already talks to
  can be shown live before any P2P exists.

## Peer data

A node is whatever the network says another participant is: a venue, a user
on a venue, or an agent of a user. Tau assumes only this much:

| Part | What it is |
|---|---|
| Identity | The node's DID. The one stable key; everything else hangs off it. |
| Name | A real, human name for the node, as it gives it or as you know it. |
| Kind | Venue, person (a user) or agent; open-ended, an unknown kind is shown plainly. |
| Relation | How it relates to you. Your own venue is yourself; on it, you are yourself, its operator is the admin, and anyone else is a member. Elsewhere, users are people you trust, services you use, or known; venues are linked to you, listed in a directory, or unknown. |
| Status | Connected, idle, offline, or not connected. |
| Belongs to | The DID of the node this one is part of: a user's venue, an agent's user. |
| Facts | An ordered set of labelled values for the card: address, reach, how you met, what they may do, what is shared. |

Facts are an open list, not fields, so a new kind of record needs no change
to the structure: the card shows a plain fact as a row and a list fact, such
as what a node may do, as a section. Peer data is read-only to the map.
Dragging, zooming or resizing an orbit never writes to it.

The map draws one tree, by ownership, with the same rules at every depth
(`MapLayout`): a node whose owner the graph does not have is a root, free on
the plane, and every other node orbits its owner. So:

- **Stars are venues.** Your own is Home, at the centre; every other venue is
  a star system far out, linked to you when its relation is "linked".
- **Planets are users**, orbiting their venue. Your venue has at least two:
  its admin, the operator, and you.
- **Moons are agents**, orbiting the user who owns them.
- **Anything attached further down** orbits whatever it belongs to, the same
  way.

Rendering geometry belongs to `MapLayout`, not the peer-data model. Missing
children may be drawn as unnamed placeholders; they must not imply a known
identity or an active connection.

The operator acts as the venue itself, so the network knows both by the
venue's DID. On the map the operator is keyed by a DID URL under it,
`<venueDID>#operator`, a local key only, and its card gives the real DID.

## Where peer data comes from

The map reads one source, which can be swapped or combined, and refreshes
when the source says something changed:

1. **Samples**, currently used for remote nodes. Keep the preview indication
   while any source is synthetic.
2. **Your venue**, live now: the embedded venue, its operator, you and their
   agents, read in-process.
3. **Local services.** Tau already talks to real services: the model
   provider, Discord, Moltbook, the Convex peer, Claude Code, and MCP clients
   connected to the venue. Each can be a system of its own with its live
   status, linked to yours.
4. **The P2P network.** Venues and agents discovered and connected through a
   node run inside the venue, with real DIDs and names.

Relations come from the source, not the map. A follow, an introduction and a
capability grant express different facts; a visual “trusted” relation must not
imply permissions that were never granted. The network relationship model
remains a decision in [NETWORK.md](NETWORK.md#shared-spaces-and-contacts).

## Map settings

Map settings contain ring sizes, placements keyed by DID, zoom, tracking and
pause preferences. They hold no node names, statuses or permissions. Defaults
give unplaced nodes stable positions; invalid stored settings fall back to
defaults. Resetting the map changes presentation only.

Settings are versioned separately from peer data. Placements for absent nodes
are pruned when the tab opens. Geometry and serialisation details belong in
`MapSettings` and its tests.

**Where they live.** In Tau's own adapter state on the venue, under the
acting person's DID: encrypted with the store, outside the person's own
namespace, and deleted with the person. The person's agents can read them at
`x/brightside/peers-map` with the ordinary read tools but not change them. The
app reads and writes them in-process, a moment after a change settles, so
arranging the map leaves no job records (`MapSettingsStoreTest`). Acting as
the operator keeps a separate arrangement.

## Steps

1. **A model behind the map** (in place). The map reads peer data from a
   source and map settings from their own store, joined by DID; the samples
   are one source (`SamplePeerSource`). Stars are venues, planets users,
   moons agents. `MapSettingsTest` covers the relation to orbit, the hashed
   defaults, resizing and the stored form.
2. **Your venue live** (in place). The sun, its admin and you, with each
   one's agents and their run state, read in-process from the embedded venue
   (`HomePeers`, `HomePeersVenueTest`); the other venues stay samples.
3. **Local services live.** The real connections listed above, as systems
   with real status.
4. **A P2P node in the venue** ([covia#587](https://github.com/covia-ai/covia/issues/587)). Discovery and connection, under the
   venue's identity, off by default and joined from Settings, with a
   read-only peers operation and change events. Tau reads it through
   the embedded API, as it reads everything else.
5. **Relations.** Show introductions, follows and granted permissions without
   conflating them; the underlying authority model needs agreement first.
6. **Agents and traffic.** Moons from the agents a peer lets you see; packets
   from real remote jobs and messages.
7. **Actions.** The card's Message and Manage access become real.

## Open decisions

- **Joining the network:** opt-in and off by default is the recommendation;
  before joining, the map shows your venue and your services.
- **The P2P key:** the venue key is simplest; a separate key rotates more
  easily ([tau#5](https://github.com/covia-ai/tau/issues/5)).
- **Strangers:** a network may hold far more nodes than are worth drawing;
  show those actually seen, grouped by region, unlabelled until pointed at.
- **Your devices:** more than one device under one person is more than one
  node of that person; how they are drawn waits until Tau runs on more
  than one.
