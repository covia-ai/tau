# Architecture

Tau is one JVM process holding a Swing desktop app and a complete Covia
venue: single Maven module `ai.covia:tau`, launcher `tau.Main`
and desktop application `tau.Tau`. The desktop application is a client of the Embedded
Venue API within that process, with direct access to venue state and services.

- **The Embedded Venue API is the application API.** Tau deliberately
  couples to the venue's Java APIs, immutable lattice values, services and live
  events. The remote HTTP, MCP and A2A APIs serve external clients; they do not
  define or limit the desktop application's integration.
- **The venue owns application state and behaviour.** Views read state directly
  and perform actions through the appropriate embedded APIs. Chat uses the
  venue's agent framework under the acting principal. View selection and
  presentation belong to the UI.
- **Configuration is data.** The owner's `venue` map is merged over defaults
  and handed to the venue, so new venue options need no Tau change.
- **One UI kit, themed by FlatLaf.** Every screen is composed from
  `ui/components`; no screen hard-codes a colour or a font.
- **Skills carry the abilities.** The assistant starts small and loads what a
  task needs ([SKILLS.md](SKILLS.md)).

Product principles are in [DESIGN.md](DESIGN.md); startup, takeover and exit
in [LAUNCH.md](LAUNCH.md); keys and recovery in [SECURITY.md](SECURITY.md);
the file and API keys in [CONFIGURATION.md](CONFIGURATION.md).

## Operation addresses

Tau publishes `v/ops/tau/<operation>` with `tau_` MCP tool names. The app adapter
also keeps `v/ops/brightside/<operation>` and its existing MCP tool names, so
stored jobs, skills and integrations can migrate calls individually. Both
names use the same implementation, state and authorisation checks. The internal
dispatch id stays `brightside:<operation>`; it is independent of the public
catalogue path. The adapter's catalogue view remains under `v/adapters/brightside`, and its state and
per-user view retain the same id ([APP_STATE.md](APP_STATE.md)). Java classes
and packaged resources use `tau` independently of these public addresses.

## The shape of it

```
Tau application ──► Embedded Venue API (in-process Java)
                            ├─ state reads and live subscriptions
                            ├─ engine, adapters and venue services
                            └─ LocalVenue operations as the acting principal
                                         │
                                 embedded Covia venue
                                 lattice state → venue.etch
                                         │
External clients ──► HTTP / MCP / A2A on loopback
```

For MCP clients that launch a stdio subprocess, `Main` selects `McpStdio`
before loading the desktop app. It bridges to the venue using the existing
local-tool token, starting the desktop app separately when needed;
the MCP SDK handles HTTP sessions and streams.
The bridge owns no venue or store. Setup is in
[CONFIGURATION.md](CONFIGURATION.md#from-a-stdio-mcp-client).

## Reads

Read application state through the Embedded Venue API. Direct lattice reads
and subscriptions use the venue's in-process data structures and events,
without serialisation or durable jobs. Computed reads can use the venue's
read-only operations transiently. A remote-client abstraction is unnecessary
for these local interactions.

## Actions

Make application state changes through the appropriate embedded venue APIs,
using their validation, permissions and persistence semantics. Use operations
where the venue models an action as an operation, and direct service APIs where
those are the idiomatic interface. The lattice remains authoritative; the UI
projects its state rather than maintaining a separate application model.

## Key decisions

**Keep I/O off Swing's event thread.** Launch, shutdown, model calls, file
access and desktop integration run in the background. Deliver UI changes on
the event thread and discard results belonging to a previous selection or
principal.

**Views follow venue state.** Visible views use the shared
[watcher](WATCHER.md); explicit observers, such as the [Inbox](INBOX.md),
continue off screen. The UI owns presentation and selection, not a second
copy of the application model. [Jobs](JOBS.md) uses Covia's job records.

**Files retain their access boundary.** The file browser works through Covia's
file and DLFS adapters as the acting user. Reads create no jobs; changes use
operations and leave records. Folder access and personal drives have different
scopes ([CONFIGURATION.md](CONFIGURATION.md#files-and-personal-drives)).

**Recovery follows typed conditions.** A missing saved session starts a new
conversation only for the agent adapter's recognised unknown-session code.
Other failures preserve the selection and surface the error; message wording
never decides recovery.

**The venue owns conversation execution.** Chat displays its history and live
events. A turn has no overall deadline; individual tool calls have the
configured timeout ([CONFIGURATION.md](CONFIGURATION.md#chat)). In-flight
status shows reported facts, not an invented account of the model's thinking.

**Speech is an explicit choice.** Voice typing writes into the draft and uses
the chosen send rule. Reading aloud is separately enabled. Both use the
venue's speech adapter; engine settings and data handling belong in
[CONFIGURATION.md](CONFIGURATION.md#voice-typing). Assistant Markdown renders
through Covia Workbench; the owner's text stays as typed.

**Lifecycle must preserve state.** Tray support is optional. Shutdown closes
the venue before process exit, with a shutdown hook for process termination.
Takeover and recovery rules live in [LAUNCH.md](LAUNCH.md).

## Packaging

The Covia version is selected in `pom.xml`; the snapshot build instructions
are in [README.md](../README.md#try-it). Every in-process Covia module is
the same version. Integration follows Covia's
[embedder contract](https://github.com/covia-ai/covia/blob/develop/venue/docs/EMBEDDING.md): live-user components
for reads, explicit workspace paths and transactions for writes, and the
venue clock for timestamps and short-lived grants. Expected operation
refusals use Covia's typed exceptions so their reason reaches the caller.

The runnable jar is built by `maven-shade-plugin` with the services
transformer, because Jetty, Javalin and LangChain4j rely on
`META-INF/services`, and drops convex-restapi's bundled OpenAPI plugin so the venue
serves its own document. Logging is configured programmatically from
`tau/logback.xml` because the venue jar ships a root `logback.xml` of
its own.

Native installers wrap that same jar. `packaging/package.sh` links a reduced
runtime with `jlink` from `packaging/runtime-modules.txt` (the modules `jdeps`
finds plus those libraries load reflectively), keeping its `java` command for
`Relaunch`, then builds an application image and installers from it with
`jpackage`: dmg, msi and deb, and an AppImage through `appimagetool`. Signing
happens between the image and the installer so the launcher inside is signed
too. `.github/workflows/installers.yml` runs it on each platform; the
installers own only their program folder and never touch the data directory.
