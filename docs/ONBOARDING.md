# Onboarding, unlock and recovery

First run creates the identity and vault before the venue starts. Returning
users unlock the same installation; recovery restores access using the phrase
and retained store.

- **Explain the choices.** Keep the flow short, but make recovery and any
  plaintext remembered passphrase an informed decision.
- **Save the phrase offline.** It restores the identity; it cannot recreate a
  lost store.
- **Choose the processing provider.** Model calls send context to that provider;
  the echo bot needs no key.
- **Keep later changes in Settings.** Key and file protection belong in
  [SECURITY.md](SECURITY.md), configuration in [CONFIGURATION.md](CONFIGURATION.md).

## Three entry states

- **First run** — no encrypted identity on disk: the first-run wizard, in the
  main window.
- **Returning** — the identity exists: the unlock dialog on its own, and the
  main window only once the passphrase is accepted and the venue is up.
- **Running** — later changes go through Settings.

The identity seed and the store key are needed before the venue can launch, so
the wizard and unlock both run before it. The code is `OnboardingWizard`,
`UnlockDialog` and `RecoveryDialog` under `ui/onboarding`, `Vault` and
`Mnemonic` under `vault`, with `Tau` orchestrating.

## First run

Eight cards behind five dots: Welcome, Passphrase, Identity (then Recovery
and Confirm, or Import), Name, and Model. Each sits on a card, white over the
sidebar's gold on Light and raised navy on Dark; Enter presses the primary
button. The τ greets on Welcome and again as Finish sets things up, large
and in the brand gradient; every other card carries the small τ.

1. **Welcome**: your own agent, on your own machine, under your own
   identity; your data is stored here, and models and connected services may
   receive data when you use them. One button.
2. **Passphrase**: choose and confirm, with a live strength meter (a bar and a
   word, in the status tones) and a minimum length. A card with a navy
   outline, never red, warns that the recovery phrase is what rescues a
   forgotten passphrase. The salt is created and the passphrase key derived in
   memory; nothing is written yet.
3. **Identity**: create a new one, or import a recovery phrase. Creating shows
   twelve words in a four-by-three grid, in mono, with Copy, and asks for two
   of them back; importing accepts twelve or twenty-four words with live
   validation.
4. **Name**: "What should I call you?". The identity just made appears under
   the field the moment it exists: its generated mark and short fingerprint,
   in mono, from the `did:key` of the phrase's seed (the DID the venue will
   present).
5. **Model**: provider and model from the venue's catalogue (for OpenRouter
   and Ollama, any model id typed in), an API key with a link to that
   provider's console, and a keyless "use the offline echo bot" escape so the
   app works end to end without a key. The key is staged encrypted and moved
   into the venue's secret stores at first launch. Finish derives the seed,
   writes it encrypted, fixes the venue's DID, and goes straight into the chat.

A test call for the key, and a closing card to connect an agent you already
use, are planned; connecting one today is Agents → Add an agent, or Vault →
Access.

## Unlock

A small window of its own, shown before the main window exists: passphrase,
*Unlock*, and *Forgot it? Restore from your recovery phrase*. A wrong
passphrase fails the authenticated decryption and is reported there, not in
the log. While the venue starts, the dialog shows progress and the main window
appears only once it is ready. *Lock* (Settings → General) hides the window and
brings the dialog back; the venue keeps running, so named tokens made for
outside tools keep working. *Remember me* stores the passphrase in plaintext after
explicit opt-in, a trade-off SECURITY.md spells out. With it stored, Settings →
General → Startup can have a launch unlock with it without asking; Lock still
asks ([LAUNCH.md](LAUNCH.md#startup)).

## Recovery from the phrase

*Forgot passphrase?* takes the recovery phrase and a new passphrase. Tau
verifies the recovered seed against the retained store before touching any
credential file, then rewrites the identity envelope and reopens the existing
store. Conversations, memory, skills and the secret stores, provider keys
included, come back with the store. The phrase alone, without the store,
restores the identity only.

## Settings afterwards

- **Model** — provider, model and key. The model choice persists in
  Tau's venue state ([APP_STATE.md](APP_STATE.md)) and applies on the
  next message; the key goes to the venue's
  secret stores at once and never to `config.json`. Stored values are viewable,
  passphrase-gated, under *Settings → Secrets* (a passphrase remembered on
  this computer passes the gate unasked).
- **Identity** — name, DIDs, public key, the seed behind the passphrase, and
  switching to the operator ([IDENTITY.md](IDENTITY.md)).
- **Advanced** — change passphrase, view the recovery phrase behind the
  passphrase.

The model catalogue and discovery rules are owned by `model/Providers` and
the venue. The app also offers provider-discovered models, including installed
Ollama models, and preserves the current selection if it leaves the catalogue.
