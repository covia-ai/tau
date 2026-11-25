<div align="center">

# τ Tau

**Your own agent. On your own machine. Under your own identity.**

*And it writes its own skills.*

[![Licence: EPL 2.0](https://img.shields.io/badge/licence-EPL--2.0-blue.svg)](LICENSE)
[![Java 25+](https://img.shields.io/badge/Java-25%2B-orange.svg)](https://adoptium.net/)
[![Built on Covia](https://img.shields.io/badge/built%20on-Covia%20Grid-7c4dff.svg)](https://covia.ai)
[![Status: early preview](https://img.shields.io/badge/status-early%20preview-yellow.svg)](#where-this-is-going)

</div>

---

Tau (τ, two times π) is a desktop personal agent built on Covia. It runs a
venue on your computer and keeps conversations, memory and skills in a local
encrypted store. It is an early preview, in daily use by its author.

Model providers receive the context sent to them; tools and integrations can
send data to other services. Tau has no telemetry. Local storage does not mean
all processing is local: see [security and its limits](docs/SECURITY.md).

<div align="center">
<img src="docs/images/screenshot.png" alt="Tau stellar map" width="820">
</div>

## What it does

- Chats with your chosen model, keeps conversation history and uses shared and
  per-agent memory across sessions.
- Loads skills for a task and can write its own. Imports `SKILL.md` folders
  from `~/.tau/skills/` at launch.
- Offers voice typing, reading aloud, Discord and Claude Code integration.
- Brings requests for decisions to an Inbox. Four specialist agents handle
  administration, security reports, treasury requests and repairs
  ([the gods](docs/GODS.md)).
- Exposes HTTP, MCP and A2A interfaces, authenticated and bound to loopback by
  default, for other tools on your computer.

Skills organise tools and instructions; they are not permission boundaries.
The default assistant has broad authority within your user namespace.
Specialist agents have narrower capabilities, with the limits described in
[GODS.md](docs/GODS.md).

## Where this is going

Federation, skill sharing and delegated payments are proposals, not finished
features ([NETWORK.md](docs/NETWORK.md)). The Peers view has a live home node
and sample remote nodes. Other planned work includes signed installer releases,
Telegram setup in the app, richer messages and an offline model default.
Ideas and disagreement are welcome in issues.

## Try it

You need **Java 25+** and **Maven 3.9+**.

```bash
git clone https://github.com/covia-ai/tau.git
cd tau
mvn package
java -jar target/tau.jar
```

While `covia.version` in `pom.xml` ends in `-SNAPSHOT`, Tau builds on an
unreleased Covia: install it first, as in
[building against a local Covia checkout](#building-against-a-local-covia-checkout)
below, or `mvn package` cannot resolve `ai.covia:venue`.

For an existing installation, see [data directories and manual setup](docs/CONFIGURATION.md#selecting-an-existing-installation).

On first launch, choose a passphrase, save the recovery phrase, choose a
model and enter its key, then choose what it should call you. Without a key,
choose the offline echo bot; the app works end to end, with echoed replies.

**Enter** sends and **Shift+Enter** starts a new line. Right-click a message, a
conversation or an agent for what you can do with it; everything else is under
**Settings**.

### Installers

CI builds native installers with their own Java runtime, so no separate Java
is needed to run them: a `.dmg` for macOS (Apple silicon and Intel), an
`.msi` for Windows and a `.deb` and AppImage for Linux, each for x64 and
arm64 where the platform has both. Each run of the *Installers* workflow
installs every one the way a person would, runs the installed app's
[self-test](docs/LAUNCH.md#self-test) twice, uninstalls it and checks the data
folder was left alone, then attaches the installers as artefacts with SHA-256
checksums.

| Platform | Architectures | Built and tested on |
|---|---|---|
| macOS | Apple silicon (arm64), Intel (x64) | `macos-latest`, `macos-15-intel` |
| Windows | x64, arm64 | `windows-latest`, `windows-11-arm` |
| Linux | x64, arm64 | `ubuntu-latest`, `ubuntu-24.04-arm` (installers on `ubuntu-22.04` and `ubuntu-22.04-arm`) |

Tau is a desktop app: phones and tablets (iOS, iPadOS, Android) are not a
target, and there is no mobile build.
They are unsigned until signing credentials are configured, so macOS and
Windows warn on first launch. Installing, upgrading or uninstalling never
touches `~/.tau`.

To build the installer for your own platform after `mvn package`:

```bash
packaging/package.sh all     # → target/installer/
```

This needs the JDK's `jlink` and `jpackage`, plus WiX on Windows and
`fakeroot` (and `appimagetool` for the AppImage) on Linux. Where things are
installed, and how to point other tools at an installed copy, is in
[CONFIGURATION.md](docs/CONFIGURATION.md#installed-copies).

### Building against a local Covia checkout

`covia.version` in `pom.xml` names the Covia line. A release resolves from
Maven Central; a SNAPSHOT needs the Covia checkout installed locally:

```bash
git clone https://github.com/covia-ai/covia.git ../covia   # once, beside tau
cd ../covia
mvn -pl venue,workbench,covia-discord,covia-telegram,covia-claude-code,covia-speech -am install -DskipTests
cd ../tau
mvn package
```

If the build cannot resolve `ai.covia:venue`, install the matching Covia
version first. Covia normally resolves Convex from Maven Central; if its
`convex.version` is a SNAPSHOT, install `../convex` first with
`mvn clean install -DskipTests`. Install every module listed above together so
the embedded runtime, shared UI and integrations all use the same snapshot.

## Under the hood

Tau runs a complete [Covia](https://covia.ai) venue inside a Swing/FlatLaf
desktop application. Skills are stored as data; the tools they expose may
execute code or act on external services. Configuration is a JSON5 file with
defaults for every key.

- [docs/DESIGN.md](docs/DESIGN.md) — the product principles
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — the shape and the decisions behind it
- [docs/SKILLS.md](docs/SKILLS.md) — how the assistant gets its abilities
- [docs/GODS.md](docs/GODS.md) — the team of super-user agents
- [docs/SECURITY.md](docs/SECURITY.md) — keys, recovery and what is encrypted
- [docs/CONFIGURATION.md](docs/CONFIGURATION.md) — every file and setting, and reaching your agent from other tools
- [docs/NETWORK.md](docs/NETWORK.md) — where this is going

## Data and model choices

The default data directory is `~/.tau/`. The venue store is encrypted;
configuration, logs, imported skills and checked-out code are separate files
and are not all encrypted. Backups and custom paths are described in
[CONFIGURATION.md](docs/CONFIGURATION.md).

Choose a model under *Settings → Models*. A local model or the echo bot avoids
remote model processing; networked tools and integrations still need their
services. The echo bot lets you exercise chat without an API key.

## Join in

Contributions are very welcome, especially now.

```bash
mvn test      # boots real venues, headless; no API key needed
mvn package   # → target/tau.jar
```

CI builds and tests every push and pull request on each platform and
architecture in the table above, runs the jar's self-test, renders every
screen at four window sizes in dark and light with the screenshot harness
(kept as artefacts), and scans the shipped dependencies for known
vulnerabilities and the whole history for committed secrets
(`.github/workflows/ci.yml`). Images other than
the app icon, the Lucide icons and the documentation screenshots fail CI
until they are added to `packaging/allowed-images.txt`.

Branch from `develop` and PR into it. [AGENTS.md](AGENTS.md) is the short
guide for anyone — human or AI — working here; read it and
[docs/DESIGN.md](docs/DESIGN.md) before a first PR. The `docs/` folder explains
*why* things are the way they are.

## Licence

Eclipse Public License 2.0 — see [LICENSE](LICENSE).

Built on the [Covia Grid](https://covia.ai) and [Convex](https://convex.world).
