# The τ character

The character is Tau's figure beside your work: a few signed poses and the
eyes that move over them, played by a small Swing player. Its art is not in
this repository; a build without it shows the τ glyph in every place the
character would stand.

- **The art lives outside the app.** A pack of images is named at run time; without one, everything falls back to the τ glyph, and nothing else changes.
- **Signed poses only.** Poses are crossfaded, never rotated or re-drawn; the eyes are their own layer, so they can look, squash, and blink.
- **Motion reports a fact.** The figure changes because Tau's work did: a request arrives, the model thinks, a tool runs, another agent is asked, something waits on you.
- **Few places, and never in some.** Chats, the sidebar status, first launch, greeting empty states, and About; never the Record, the Vault, the Constellation, Rooms, agent rows or profiles, refusals, or errors.
- **Reduced motion is honoured.** The system's setting, or `tau.motion`, holds a still pose per beat.

## The art and the switch

The pack is a directory holding `character.json` and its images: per pose a
transparent square base and, where the eyes move, one screen-blend sprite per
eye with its box as fractions of the frame. Tau reads the directory named by
the system property `tau.character.dir`, or else the environment variable
`TAU_CHARACTER_DIR`. With neither, it reads the pack in the data folder's
`character/` directory (`~/.tau/character/` by default) when that holds a
`character.json`. A pack that is named wins even when it can't be read, so
naming one is never silently overridden. With no pack, or with one that is
missing, unreadable, or has no rest pose, `CharacterArt` is empty and every slot shows the τ glyph
(today's plain τ in the greeting places, the brand τ in About and on first
launch). A pose the pack lacks falls back the same way. Images are decoded the
first time a pose shows, never on the start-up path.

**Settings → Theme → Add character pack…** puts a pack into the data folder:
choose the pack's folder, or a zip of it, either holding the pack itself or one
directory that does. `CharacterPacks` takes only the pack's own files (plain
names, no subdirectories, at most 64 files and 32 MB), decodes every pose, and
only then replaces the pack there; a pack that fails leaves the old one in
place and says why. Views made after that show the character; the ones already
on screen keep the glyph until Tau restarts, and the dialog says so. While the
property or the environment names a pack, that one stays in use and the
dialog says that too. Uninstalling Tau leaves the data folder, and so the
pack, where it is.

Builds made from this repository carry no pack, and its tests run without one;
they make stand-in shapes of their own where they need art.

## Beats and poses

`Presence` maps each beat to what shows: rest for ready, working, and asking
an agent, with the eyes on the work or on the agent; the chin glove for
thinking; the clasp for waiting on you, with the floor glow warm, and for a
timed-out task; the asleep pose for a long idle; rest with the eyes squashed
and one hop for done; the excited pose and its jump for a milestone only.
Paused, cancelled, failed, refused, offline, and locked have no pose: the
glyph and a calm badge carry them.

A request coming in plays the catch: one spark flies in from your message and
the figure crossfades to the clasp and back. Each agent asked gets one spark
out to the step line, and none comes back; nothing flies when Tau hands back.
`LiveTurn` gives the turn's phase from the venue's agent events, so the figure
follows what really happened.

## Sizes and motion

Below 32 px only the glyph shows. The 32 px sidebar figure crossfades and
blinks, nothing more. From 96 px the eyes move, the figure breathes, and the
hop and jump play; from 128 px it floats too. Tau draws its own floor shadow,
which narrows as the figure rises.

`Motion` is Full, Reduced, or Off: the system property `tau.motion` wins,
else the operating system's own setting where it can be read, else Full.
Reduced keeps the crossfade and nothing else (no breathing, blinks, hops, or
sparks); Off swaps stills. The player's timer runs only while its view is
showing and something moves, and the loops stop a minute after the last beat.

## Where it shows

| Place | Size | Plays |
|---|---|---|
| Chats, beside the step line while a reply runs | 96 px | every beat of the turn |
| Sidebar status, with its label | 32 px (the glyph when collapsed or on a quiet screen) | crossfade and blink |
| First launch, Welcome and Finish | 176 px | the welcoming pose |
| Greeting empty states (an empty chat, the Inbox, the Feed, no skills) | 104 px | rest |
| About | 160 px | rest |

Nothing else makes the character; a test holds the list. Notifications stay
still images, as they are today.

## Known limits

- The eyes look towards the step line and the agent asked, not at exact things on screen, and there is no listening beat while you type.
- Finished steps land in place in the chip; there is no Record count in the window to go up.
- There is no Motion control in Settings yet: the system's setting or `tau.motion` decides.
