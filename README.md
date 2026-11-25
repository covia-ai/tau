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
and sample remote nodes. Other planned work includes native installers,
Telegram setup in the app, richer messages and an offline model default.
Ideas and disagreement are welcome in issues.

## Try it

You need **Java 25+** and **Maven 3.9+**. When `covia.version` in `pom.xml` is
a SNAPSHOT, install Covia first, as *Building against a local Covia checkout*
below describes.

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

On first launch, choose a passphrase, save the recovery phrase and choose what
it should call you. *Settings → Models* then opens: choose a model, enter its
key and press *Test*. Without a key, choose the echo bot; the app works end to
end, with echoed replies.

**Enter** sends and **Shift+Enter** starts a new line. Right-click a message, a
conversation or an agent for what you can do with it; everything else is under
**Settings**.

### Building against a local Covia checkout

`covia.version` in `pom.xml` names the Covia line. A release resolves from
Maven Central; a SNAPSHOT needs Covia cloned beside Tau and installed locally:

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

Branch from `develop` and PR into it. [AGENTS.md](AGENTS.md) is the short
guide for anyone — human or AI — working here; read it and
[docs/DESIGN.md](docs/DESIGN.md) before a first PR. The `docs/` folder explains
*why* things are the way they are.

## Licence

Eclipse Public License 2.0 — see [LICENSE](LICENSE).

Built on the [Covia Grid](https://covia.ai) and [Convex](https://convex.world).
