# Launch, takeover and exit

How a Tau process starts, what happens when one is already running, and
how it stops or restarts. Code: `Tau`, `Takeover`, `Relaunch`,
`EmbeddedVenue`, `Startup`, `TrayManager`.

- **Nothing shows without live data behind it.** The unlock dialog or wizard
  comes first; the main window appears only once the venue is ready.
- **Tau's own state is put right before anything runs.** A startup
  pass in Covia's launch hook migrates, repairs and verifies it, and submits
  jobs only for what it changes. The owner's agent is configured on the first
  message.
- **Two processes cannot share the store**, so a newcomer asks the running
  instance to step aside with a request only the venue's own key can sign, and
  waits for it to release the store before opening it. A relaunch is such a
  newcomer, started by the instance it replaces; a restart stays in the
  process and starts only the venue again.
- **Closing the window quits** unless the owner opts into the tray; quitting
  always flushes state, and waits for a launch in progress first.
- **The store is compacted only when asked**, at the start of the next
  launch, before the venue holds it.

## Startup

The launcher `Main` normally enters `Tau.main`. With `--mcp-stdio`,
it runs a connection to the app, starting it with the existing unattended
unlock flow when absent. `McpStartup` serialises concurrent cold connections
and waits for authenticated readiness; an existing app is never taken over.
Standard output carries MCP messages only; diagnostics go to standard error.
Closing the client's input ends that connection and leaves the app running.
See [stdio setup](CONFIGURATION.md#from-a-stdio-mcp-client).

Startup follows this order:

1. Read configuration, initialise logging and theme, and check for an existing
   instance before showing a window.
2. Complete onboarding or unlock the vault. The seed and store key must be
   available before the venue opens. The main chat remains hidden until ready.
3. Handle an authorised takeover, any required store repair and requested
   compaction before opening the venue.
4. Register Tau's adapters and integrations in Covia's pre-start hook, then run
   the [startup pass](#the-startup-pass), before recovering jobs or starting
   workers. A failed hook closes the partially assembled venue.
5. Bind the owner's identity, transfer any staged provider keys, import changed
   filesystem skills and bind the views. Report launch or binding failures
   where the owner can see them. The main agent is configured on its first
   message; startup may separately configure gods and request Eir's help.

The default listener is authenticated loopback; configuration can override it
([SECURITY.md](SECURITY.md#authentication-and-network-exposure)).

| Launch mode | Unlock and visibility | Existing instance |
|---|---|---|
| Ordinary launch | ask, unless automatic remembered unlock is enabled; show the window | ask to take over, unless the owner enabled automatic takeover |
| `--unlock` | require a valid remembered passphrase; start in the tray, or minimised without one | leave it running and exit |
| `--takeover` | use the remembered passphrase if available, otherwise ask; show the window | take over without asking |
| `--unlock --takeover` | unattended unlock; start in the tray or minimised | take over without asking |

An unattended launch with missing setup or an invalid remembered passphrase
fails without a window. If it has already displaced the previous instance,
it reports a subsequent failure in a dialog where possible. Settings → General
→ Startup controls ordinary-launch preferences; command-line modes override
them. Lock always asks for the passphrase and leaves the venue running.

### Starting at Windows sign-in

The opt-in setting under General → Startup requires a remembered passphrase
and a jar launch. It installs a per-user Windows `Run` entry using `--unlock`;
Task Manager can disable it. Each successful start updates the entry to the
Java executable and jar actually running, including a build selected by Odin.

Turning it off or forgetting the passphrase removes the entry. Entries for
other data directories are left alone. `StartAtSignIn` owns this integration;
its tests use a stand-in registry.

### The startup pass

`Startup` runs after adapters are registered and before recovered work starts.
It compares stored versions, brings gods onto the stored model and current
caps, resumes fault suspensions, and verifies the result. Without a stored
model, god setup waits. Deliberate suspensions remain in force.

The pass records versions and a fingerprint of the standard agent setup and
shipped skills. Once serving, Tau asks Eir about unresolved problems and asks
her to upgrade the owner's agents when versions or that fingerprint changed
([EIR.md](EIR.md)). Repeated reports of the same problems are suppressed.

Unresolved god checks are logged and shown; they do not by themselves abort launch.
`StartupTest` checks that an over-privileged god is narrowed before it acts.

## Taking over a running instance

Two processes cannot share the store. `Takeover` checks the configured port
before the UI opens and again before venue launch. Any HTTP response counts
as occupied, including an authentication refusal.

A returning owner can authorise takeover; a new installation cannot, because
it does not hold the incumbent's seed. After unlock, the newcomer identifies
the incumbent using a short-lived operator token signed by that seed. It
requires a matching Tau identity and obtains the process and store details,
then requests authenticated shutdown.

These calls deliberately use `v/ops/brightside/info` and
`v/ops/brightside/shutdown` so an older running instance can be replaced.
The newcomer waits for the listener, process and store lock to be released:
a closed port alone does not mean the store has finished flushing. Only then
does it open the venue. `TakeoverTest` exercises the private-venue handshake.

## Restart and relaunch

Tau starts again in one of two ways, each an operation that needs
Covia's `admin/venue/restart` on the venue's process, as shutdown does: the
venue itself, or an agent whose caps include it, which Odin's do
([ODIN.md](ODIN.md)). Covia's own venue restart needs process control that
only a standalone venue has.

- **`brightside:restart`** stops the venue and starts it again inside this
  process: the same program, with the key it already holds. It picks up
  `config.json` and a compaction asked for, not new code.
- **`brightside:relaunch`** starts a new process that takes over, as above,
  running this program again or another Tau jar: this is how a new
  build comes in.

Either way an agent's work in flight resumes once the venue is back: it finds
the operation's answer in its conversation, and a call cut off by the stop was
cut off because the restart or relaunch worked. Both answer before anything
stops. `RestartTest` runs both on a venue whose app does nothing.

### Restart

The app validates the new configuration before stopping. After answering the
operation, it releases UI bindings, retains the key, closes the venue and
launches it again. An unlocked app returns to a clean Home chat as the owner;
a locked app remains behind the unlock dialog.

Restart is refused during startup, shutdown or another restart, and without a
desktop app. Launch failures are shown in the current view. Behind Lock,
unlocking retries launch; otherwise the owner can quit and start again.

### Relaunch

`Relaunch` starts the same Java and configuration with the current jar or class
path, or with another Tau jar supplied as a caller-readable content reference.
For example, `file://code/tau/target/tau.jar` selects a local build.
The jar is copied under `run/` so Windows file locking does not prevent later
builds; its main class must be Tau's. This check is not a code signature or
a sandbox.

The successor uses takeover and preserves whether the app was visible or in
the tray. Without a remembered passphrase it asks for one. The incumbent
continues until the successor unlocks and requests shutdown, so failures
before handover leave it available. Failures after handover can leave neither
instance serving and are reported by the successor.

The operation returns the successor's process ID when it starts, not when it
is ready. A caller must verify readiness and the new process through
`brightside:info`. There is no “newer jar” check; an incompatible store is
refused at launch ([store versions](#opening-a-store-an-older-build-wrote)).

On Windows the successor attempts to leave the parent's process job, so ending
a coding session does not end Tau. Restrictive enclosing jobs can prevent
that; a failure is logged. `WindowsProcess` owns the platform mechanism.

Relaunch is refused while locked, while an earlier successor remains pending,
without a desktop app, or from an embedded launcher such as `mvn exec:java`
whose class path cannot start independently.

## Tray, minimise, close, quit

- Minimise goes to the taskbar. *Send to the tray when minimised* is an opt-in
  under Settings → General.
- Close quits. *Keep running in the tray on close* is an opt-in. Closing the
  unlock dialog follows the same rule.
- *Hide to tray* (Settings → General, or the hide shortcut) hides the window and
  keeps the venue running; the tray icon brings it back.
- Quit (tray menu or Settings → General) always flushes and exits.
- Without a tray (unsupported desktop, or `TAU_NO_TRAY=1`) there is
  nowhere to hide: close quits, minimise minimises.

## Exit and flush

Exit closes view bindings and the tray, waits boundedly for any launch in
progress, and closes the venue off the event thread before ending the process.
A shutdown hook covers Ctrl-C and SIGTERM. Concurrent closes wait for the
same final save; the venue closes before Etch's later file-close hook.
Forced termination can still require store repair.

## When the store was not closed cleanly

Etch refuses a writable open after an unclean stop. With `repairStore` enabled,
`StoreRepair` rebuilds into a fresh file, preserves the original as
`venue.etch.unclean-<stamp>` and installs the result before launch. If installing
the new file fails, it attempts to restore the original.

Repair restores the last checkpoint, not necessarily every published change.
Keep the preserved file until the recovered data is verified; diagnostic
problems are logged. Set `repairStore: false` to stop instead and restore a
backup manually. Interrupted collection has additional limits below.

## Compacting the store

Etch only appends, so a value replaced or deleted stays in `venue.etch` until
the store is collected: everything the lattice root still reaches is copied
into a fresh file and the rest dropped. Covia can collect a running venue, but
the store can span two files. Etch recovers completed cutovers and discards
an abandoned target that never held a checkpoint. A crash after a collection
target was checkpointed and then changed can leave dirty files the
launch-time repair cannot put right. The start is the safe point: nothing
holds the store and nothing writes to it.

So compaction is asked for, from Settings → General → Data store or by Odin
(`brightside:storage`), and leaves a `venue.etch.compact` request beside the
store. The next launch spends the request before anything else, so a
compaction cut short is not tried again at every start; `StoreSpace` then
opens the store, runs Covia's start-up collection on it and launches the venue
on the result, and the chat opens with a note of what it held before and
after. A collection that fails before it cuts over leaves the store as it was,
and the note says so. Where Windows still pins the old file, the venue runs on
the compacted one under its own name and Etch swaps them at the next start.

How much disk comes back depends on how much was garbage: Etch grows a file in
steps as it writes, so a small store stays near the size of its last step.

## Opening a store an older build wrote

Covia migrates versioned venue state transactionally during launch; some
session data migrates lazily when used. Tau's adapters own their state versions
and migrations. A build that recognises a newer unsupported state version
refuses it unchanged.

Do not assume an older jar can undo an upgrade. Builds predating version
checks may not refuse newer data safely, and migrated stores may be unreadable
to earlier builds. Keep a pre-upgrade store backup. `CoviaMigrationTest`
exercises an installation written by an older build.

## When launch fails

`Venue failed to start` in the log names the cause. `File lock failed on
venue.etch` means another process still holds the store without answering on
the port — one stopped dead mid-exit, or a different tool holding the file:
wait a moment, or quit it. A wrong passphrase is reported on the unlock screen,
not in the log.

## Known limits

- A crash part-way through a compaction leaves the store in files that only a
  repair by hand can open, and the launch stops rather than repairing the
  wrong one (`StoreRepair.isInterruptedCollection`), telling the owner to keep
  every `venue.etch` file as it is. Putting it right needs
  Convex's `etch` tools; doing it at launch belongs in Convex and Covia
  ([covia-ai/covia#614](https://github.com/covia-ai/covia/issues/614)).
- A relaunch cannot name its jar by a plain `https://` URL: Covia resolves no
  web address as content yet
  ([covia-ai/covia#653](https://github.com/covia-ai/covia/issues/653)).
