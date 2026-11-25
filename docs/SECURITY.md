# Security and vault recovery

The security properties of Tau as implemented, for reviewers,
operators and anyone testing whether an installation can be recovered. It is
not a claim that the host computer, the Java process, the model provider or
user-installed skills are trusted in all circumstances.

- **One root seed.** It establishes the venue's signing identity and derives
  the key that opens the store. Provider credentials and treasury keys are
  separate secrets.
- **Recovery needs the data.** The phrase reproduces the identity; the phrase
  plus a retained store recovers its contents. The passphrase does not
  reproduce either.
- **Encryption has a scope.** The store and seed envelope are encrypted.
  Logs, configuration, ordinary files and optional remembered credentials
  are not all protected by that encryption.
- **Authenticated loopback is the default.** Configuration can override it.
  Models, tools and integrations may send data to external services.
- **Principals separate venue authority, not processes.** Chat acts as the
  owner's user; the desktop application and embedded venue share one JVM and
  one trust boundary.

The implementation is `Vault`, `Mnemonic` and the recovery flow in
`Tau`; the person-facing flow is [ONBOARDING.md](ONBOARDING.md); the
files are listed in [CONFIGURATION.md](CONFIGURATION.md).

## The recovery invariant

The seed has two independent uses: it creates the venue's Ed25519 key pair,
its `did:key` and its signing authority, including the access tokens the venue
trusts; and it deterministically derives the master key that opens
`venue.etch`. The BIP39 recovery phrase reproduces that seed. Therefore:

> **Recovery phrase + retained `venue.etch` = the same venue identity and the
> same lattice state.**

The phrase cannot recreate data if the store has been lost, and a copy of the
store cannot be decrypted without the seed. The salt is not needed when the
phrase is available: Tau creates a new salt and wraps the recovered seed
under a new passphrase. The salt is needed to unlock the existing identity
envelope with the old passphrase.

## Key hierarchy

```text
new 12-word phrase (128 bits of entropy + BIP39 checksum)
                  │
                  ├─ BIP39 PBKDF2-HMAC-SHA-512/2048, empty BIP39 passphrase
                  ▼
             64-byte BIP39 seed
                  │
                  ├─ SLIP-0010 Ed25519 master derivation, empty path
                  ▼
          PRIMARY ED25519 SEED (32 bytes)
             │                         │
             │                         └─ SHA-256("brightside-etch-v1" || seed)
             │                                            │
             │                                  32-byte Etch master key
             │                                            │
             │                         Etch HKDF-SHA-256 + per-file salt
             │                              ┌──────────────┴──────────────┐
             ▼                              ▼                             ▼
      Ed25519 key pair              ChaCha20 file key          HMAC-SHA-256 header key
             │                              │                             │
      venue DID and signatures       encrypted data records      authenticated v3 header

login passphrase + vault.salt
                  │
                  └─ Argon2id ──► 32-byte passphrase key
                                      ├─ AES-256-GCM ──► identity.enc (primary seed)
                                      └─ AES-256-GCM ──► keys.enc (onboarding key staging)
```

New installations generate twelve words; import and recovery also accept a
valid twenty-four-word phrase. The BIP39 passphrase is empty, so there is no
"25th word". The recovery phrase is a bearer-equivalent master secret and
belongs offline. The raw seed can be revealed under *Settings → Identity*
behind the vault passphrase; it reproduces the identity and the store key but
cannot be turned back into the phrase. A passphrase remembered in plaintext
passes that gate, and the one on *Settings → Secrets*, without being asked
for: anyone who can read the file already has it, so asking would prove
nothing.

## Algorithms and parameters

| Purpose | Construction | Specification |
|---|---|---|
| Recovery words | BIP39 English mnemonic; 12 words by default, from `SecureRandom` | [BIP-0039](https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki) |
| Phrase to BIP39 seed | PBKDF2-HMAC-SHA-512, 2,048 iterations, salt `"mnemonic"`, 64-byte output | [BIP-0039](https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki) |
| BIP39 seed to primary seed | SLIP-0010 Ed25519 master derivation, empty path, left 32 bytes | [SLIP-0010](https://github.com/satoshilabs/slips/blob/master/slip-0010.md) |
| Venue identity and signatures | Ed25519 from the primary seed | [RFC 8032](https://www.rfc-editor.org/rfc/rfc8032) |
| Venue access tokens | JWT/JWS signed with Ed25519 (`EdDSA`); `iss`, `sub`, `aud`, `iat`, `exp` | [RFC 7519](https://www.rfc-editor.org/rfc/rfc7519), [RFC 8037](https://www.rfc-editor.org/rfc/rfc8037), [RFC 8725](https://www.rfc-editor.org/rfc/rfc8725) |
| Passphrase hardening | Argon2id v1.3; 64 MiB, 3 iterations, parallelism 1, 16-byte random salt, 32-byte output | [RFC 9106](https://www.rfc-editor.org/rfc/rfc9106) |
| Seed and key-staging envelopes | AES-256-GCM; fresh 96-bit nonce, 128-bit tag, no associated data; `nonce ‖ ciphertext ‖ tag` | [NIST SP 800-38D](https://csrc.nist.gov/pubs/sp/800/38/d/final) |
| Etch master key | `SHA-256(UTF8("brightside-etch-v1") ‖ primarySeed)` | [FIPS 180-4](https://csrc.nist.gov/pubs/fips/180-4/upd1/final) |
| Etch file subkeys | HKDF-SHA-256 with Etch's per-file salt and separate cipher and MAC contexts | [RFC 5869](https://www.rfc-editor.org/rfc/rfc5869) |
| Etch data confidentiality | ChaCha20 random-access overlay with a 256-bit derived file key | [RFC 8439](https://www.rfc-editor.org/rfc/rfc8439) |
| Etch header authenticity | HMAC-SHA-256 over each of two v3 header copies | [RFC 2104](https://www.rfc-editor.org/rfc/rfc2104) |
| Lattice value identity | Canonical CAD3 encodings and SHA3-256 content identifiers, a Merkle DAG | [CAD003](https://docs.convex.world/docs/cad/encoding) |

The `brightside-etch-v1` label is part of the encrypted-store format, not the
product name. It stays fixed so the same seed continues to open the same data.

Argon2id comes from Bouncy Castle; AES-GCM and `SecureRandom` from the Java
Cryptography Architecture; BIP39, SLIP-0010, Ed25519 and Etch from the
Covia/Convex runtime. Versions are in `pom.xml`. The SHA-256 derivation is
used only with a uniformly random seed and is not a password KDF; Etch then
performs its own domain-separated HKDF derivations.

### Etch confidentiality and integrity boundary

Tau requests Etch v3 with the ChaCha20 cipher and does not encrypt the
index, so the root radix index and file-layout metadata are plaintext; data
records, including their content-address keys and encodings, are encrypted.
File size and layout are therefore visible to someone who copies the file.

Etch v3 is not a whole-file AEAD container. Its dual-copy header, including
the root hash and clean-close state, is authenticated; ChaCha20 gives the body
confidentiality but not a per-record authentication tag, and CAD3's Merkle
structure is not an AEAD tag over every physical byte. Backups are still
required against corruption, malicious modification and storage failure.

A new file carries a non-secret hint derived from the Etch master key, and a
wrong key fails that check before the header is verified, so a wrong seed
fails before the venue starts. Tau uses that property to validate a
candidate recovery phrase before replacing any credential.

## Files and their recovery role

The data home is the directory holding `config.json`, by default
`~/.tau/`. The full listing is in
[CONFIGURATION.md](CONFIGURATION.md); what matters for recovery:

| Path | Protection | Recovery significance |
|---|---|---|
| `venue.etch` | ChaCha20 under the seed-derived key; see the boundary above | **Required** to recover conversations, memory, skills, agents and the secret stores |
| `identity.enc` | the seed, AES-GCM under the passphrase key | With `vault.salt` and the passphrase, recovers the seed. Replaceable from the phrase |
| `vault.salt` | non-secret, never silently replaced | Needed for old-passphrase unlock; not for phrase recovery. Back it up anyway |
| `keys.enc` | transient onboarding key staging, AES-GCM | Normally absent; provider keys live in the stores inside `venue.etch` |
| `unlock.passphrase` | **plaintext**, after explicit opt-in | Anyone who reads it can unlock the vault. Exclude from ordinary backups; cleared on recovery |
| `tool-token.json` | **plaintext** user bearer token, after explicit opt-in | Anyone who reads it can act as the user until it expires (a day at most). Not needed for recovery; removed on quit and on recovery |
| `operator-token.json` | **plaintext** venue operator bearer token, after explicit opt-in | Anyone who reads it can act as the venue itself until it expires (a day at most): its users, adapters and secrets, and relaunching Tau into any jar. Not needed for recovery; removed on quit and on recovery |
| `config.json`, `prefs.properties`, `file-roots.json` | plaintext, no secrets; folder overrides include local paths and access choices | Back up when customised |
| `skills/`, `files/`, `code/` | plaintext, the owner's own | Outside the encrypted store; back up if they matter. Review third-party skill instructions and the tools they expose; `code/tau` is a checkout Claude Code works in without asking (auto mode), as your OS user ([CONFIGURATION.md](CONFIGURATION.md#claude-code)) |
| `logs/` | plaintext, rotated | Not needed; may contain operational detail |
| `cache/speech/` | public downloads, each pinned by SHA-256: the speech model and sherpa-onnx's native libraries, which load into the process | Not needed; fetched again only once the owner agrees ([CONFIGURATION.md](CONFIGURATION.md#voice-typing)) |

Sensitive files are set to mode `0600` where the filesystem supports it; on
Windows they inherit the user's directory ACL. The seed and derived store key
enter the venue configuration in memory just before launch and are never
written to `config.json`; there is no plaintext `venue.key` and no plaintext
legacy mode.

## Backups

`Backups` snapshots the data store into `backups/<date>-<reason>/` in the data
folder: before a new build first opens the store (when any schema upgrade
runs, with the store still closed), before relaunching into a new build, and
when the owner chooses *Back up now* (*Settings → General → Data store*). A
snapshot holds `venue.etch`, `identity.enc`, `vault.salt` and the plain
settings files. It never holds `unlock.passphrase`, `tool-token.json`,
`operator-token.json` or `keys.enc`, so opening a snapshot takes the
passphrase or the recovery phrase, as the store itself does. The last five are
kept. `skills/`, `files/` and `code/` are outside the store and outside
snapshots; back them up separately if they matter.

**Restoring a snapshot.**

1. Quit Tau (tray icon → Quit), so nothing holds the store.
2. Move the current `venue.etch` (and `identity.enc` and `vault.salt`, if
   restoring those too) aside, out of the data folder, rather than deleting
   them.
3. Copy the snapshot's files into the data folder.
4. Start Tau and open it with the passphrase, or the recovery phrase.

A snapshot taken while Tau ran (*Back up now*, before a relaunch) restores like
a store that was not closed cleanly: Tau checks and rebuilds it at the first
start ([LAUNCH.md](LAUNCH.md#when-the-store-was-not-closed-cleanly)).
Keep the moved-aside files until the restored data is verified.

## Recovery procedure

**What to back up.** The recovery phrase, offline and apart from the computer;
and consistent backups of the whole data home, above all `venue.etch`. Quit Tau before copying so the store records a clean
close. Keep the original until the recovered installation is verified.
Recording the non-secret venue DID and public key beside the backup gives an
independent fingerprint to compare afterwards; the identicons on the Identity
page are a quick visual aid, and a reviewer compares the full value.

**Forgotten passphrase.** From the unlock screen, choose *Forgot passphrase?*,
enter the phrase and a new passphrase. Tau then:

1. normalises and checksum-validates the words;
2. derives the candidate seed through BIP39 and SLIP-0010, and the candidate
   Etch master key;
3. if a store exists and is not open, opens it far enough to check the key
   identity and authenticate the header; if the venue is running, compares the
   candidate with the in-memory seed;
4. only then deletes any staged key file, clears the remembered passphrase and
   writes the recovered seed into a new `identity.enc`;
5. launches the venue with the recovered seed and reopens the store.

Without a store, step 3 has nothing to validate and recovery restores the
identity only. A valid but unrelated phrase is rejected when a store is
present because it cannot authenticate the header. Afterwards, verify the DID
and public key, look at conversations and memory, and confirm the expected
slug is in use. Provider keys reopen with the store; nothing to re-enter.

**Recovery matrix.**

| Material retained | Identity | Lattice data | Provider keys |
|---|---|---|---|
| Whole data home + passphrase | Yes | Yes | Yes |
| `venue.etch` + phrase | Yes; new envelope and salt are made | Yes | Yes |
| `venue.etch` + raw seed | Yes, with Convex tooling | Yes | Yes |
| Phrase only | Yes; same seed and venue identity | No; an empty store can be created | No |
| Envelope + salt + passphrase, no store | Yes | No | No |
| `venue.etch` alone | No | No | No |

There is no online recovery service, no escrow and no operator backdoor.

## Authentication and network exposure

By default the venue binds HTTP to `127.0.0.1`, disables the anonymous public
principal, requires venue authentication for the API and MCP, and, while
bound to loopback, permits browser Private Network Access preflights so local
web tooling can reach the authenticated API. Bound to any other address, the
venue refuses those preflights unless `allowPrivateNetwork` is set. These are defaults: `config.json` overrides the
venue map, including bind, CORS and authentication. The endpoint is plain
HTTP; binding beyond loopback needs a TLS boundary and restrictive policy, and
a reviewer inspects the effective configuration first.

*Settings → Access* makes two kinds of access, and says which it is making.
Outside tools get named tokens: *Connect a tool* makes one with a scoped
preset and gives the tool's settings. Logins, and the *Unrestricted* preset,
which carry everything the user can do, sit behind *Show advanced options*
with a warning and a confirmation, and show at once only when a local login is
already kept. No scoped preset names Claude Code's tools; a preset that can
hand the assistant work says beside it that the assistant can run commands.

- **A login** is an Ed25519-signed bearer token for the current user or, as an
  advanced option, the venue operator: `iss = aud = <venueDID>`, `sub` the
  chosen principal. Its requests are that principal's. It is shown for copying
  and never persisted. The exceptions are opt-in, each by its own setting:
  *Keep a login as you for local tools* writes a user login to
  `tool-token.json`, and *Keep a venue operator login for local tools* writes
  an operator login to `operator-token.json`. Each is valid for a day and
  replaced every hour while Tau runs, so a tool on this computer can read it
  at each connect; each is deleted on quit, on *Log out* (or Lock) until the
  next unlock, when its option is turned off, and on recovery, which also
  turns both options off. Treat a copied login as a password until it
  expires.
- **A named token** is a Covia access token for the venue's MCP endpoint: its
  requests are the user's, but only with the tools and caps set on it, and the
  agent sees the token named on each turn. Tau makes, rotates and
  revokes it in-process as the user in person (`Tokens`), so its key never
  passes through an operation's result or the model. The venue keeps the key
  sealed under its secret store (`adapters.token.keepRawTokens`, on by
  default); Settings shows it again behind the passphrase, as a secret's
  value. A named token expires after the lifetime chosen when it is made:
  30 days unless the owner picks 1, 7, or 90 days, or until revoked.

Instance takeover uses a five-minute operator token and invokes only
`brightside:info` and the authenticated shutdown operation.

The desktop chat acts as the named user, not the operator, and Covia's
capability checks remain the boundary between principals. The user has no key
of its own; the venue key signs for it and is shown separately on the Identity
page. The identity record in the store pins slug and DID, and launch requires the saved DID to
equal `<runningVenueDID>:u:<slug>`, so a copied or edited profile cannot move
the UI to another principal. The in-process application and the venue share
one trust boundary.

## External requests

Covia records a request's route separately from its content and renders it
as a venue-verified `From` label for the model. The route names authenticated
principals and handovers through tokens, channels, agents and sudo. A token
identifies its issuer, not the person holding it. A copied label in message
text cannot replace the recorded route. Unreadable routes are marked unknown.

**A signed owner login does not identify an external tool.** An MCP request
using one, including Tau's optional local-tool login, has the same `auth`
route as the owner speaking directly. The model receives no external-source
label for an initial message on that route. Use a named access token when
the agent needs to distinguish the tool from the owner; a copied login
cannot prove the owner's presence or intent.

Tau's shared context and the gods' instructions require the whole route to
be considered: an external sender does not become the owner merely because
an intermediate call acts with the owner's authority. Token use, scheduled
work and agent messages are not evidence of the owner's present approval.
The ask bridges preserve provenance and token context while explicitly
granting a request to the named god ([GODS.md](GODS.md#the-bridges)).
A new agent cycle starts as that agent; the incoming messages keep
their routes, but its outgoing work does not automatically inherit every
source that influenced it.

**Provenance informs judgement; it does not confine the agent.** An admitted
request can cause the assistant to use its own authority. Its caps are checked
at actions, but they are not automatically reduced to the external sender's
caps. New owner assistants default to unrestricted caps; routine chat setup
preserves subsequent restrictions ([SKILLS.md](SKILLS.md)). A model persuaded
by hostile content can still misuse any power it holds. Native code running
in the process is outside that boundary ([ARCHITECTURE.md](ARCHITECTURE.md)).

The regression tests check route preservation, separation of forged content
from provider-facing attribution, token enforcement and persistent agent
restrictions. They do not establish resistance to every prompt injection or
test a production model's judgement. Enforced approval for consequential
actions and route-specific trust remain design work in [TRUST.md](TRUST.md).

## Runtime secret handling

- Passphrase and temporary seed arrays are cleared on the paths that expose
  them, best-effort. Java strings, UI components, the clipboard, provider SDKs
  and JVM copies cannot be reliably zeroed; use seed export and token copy only
  in a trusted desktop session and clear the clipboard after.
- The seed and derived keys necessarily exist in the running JVM; a debugger,
  a malicious same-user process or a compromised JVM can read them.
- *Log out* clears the user's UI and session state but leaves the venue
  running: a session lock, not key erasure. Quit to release in-memory keys.
- Model calls send the assembled context to the chosen provider under its
  terms; a local model avoids that disclosure.

## Assumptions and limitations

- The phrase compromises both identity and any copied store, because both
  converge on one seed by design. Keep it apart from data backups.
- The minimum passphrase length is eight characters; Argon2id raises the cost
  of guessing but cannot make a weak passphrase strong.
- The remembered passphrase is plaintext by the owner's decision and relies
  only on OS-account and filesystem protection. While it is remembered, the
  reveals it would otherwise gate (the primary seed, a secret's value) open
  without asking. Starting at Windows sign-in, which needs it, brings the
  app up unlocked for whoever signs in to that Windows account. Only the owner
  can turn that on, but while it is on, the jar Windows starts is whichever
  one the app last ran from, including one Odin or the improvement loop
  relaunched it into.
- Etch's index is plaintext and its body cipher is not whole-file AEAD.
- Envelope writes are in place, not atomic, and recovery removes the staged
  key file before rewriting the identity envelope, which is why an untouched
  backup is kept until it succeeds; a failed rewrite never invalidates the
  phrase or the store key.
- A custom store path is recovery metadata the phrase does not reproduce.
- Filesystem ACLs, disk encryption, backups, malware protection and physical
  security are the host's responsibility.
- A default assistant runs with all of the owner's authority, so although its
  `mcp-tokens` skill offers only listing and revoking, Covia's own token
  operations would let it make a token and read its key. Asking the owner to
  make one, approved in the Inbox, waits for an ask that runs one exact call
  on approval ([covia#592](https://github.com/covia-ai/covia/issues/592)).
- Capabilities constrain venue operations, not native code. Odin can load
  modules and relaunch into another jar; Claude Code runs as the OS user.
  Those paths can reach data and keys outside venue capability checks.
- External-message caution is prompt policy. Route-specific delegated
  authority is proposed in [TRUST.md](TRUST.md), not enforced today.
- Separating the signing seed from the store key, and transactional rekey, are
  [tau#5](https://github.com/covia-ai/tau/issues/5).

## Reviewer verification

`MnemonicTest`, `VaultTest` and `RememberedPassphraseTest` verify
deterministic phrase-to-seed derivation, authenticated failure on a wrong
passphrase, preservation of the salt, rejection of a wrong recovery seed,
reopening the same stored value with the same seed under a new passphrase, and
the plaintext remembered-unlock contract:

```shell
mvn "-Dtest=MnemonicTest,VaultTest,RememberedPassphraseTest" test
```

A release review adds a copy-based drill: create a test installation, record
its DID and public key, write a distinctive marker, quit, copy the data home,
discard the passphrase, recover the copy from the phrase, and compare the
fingerprint and the marker.
