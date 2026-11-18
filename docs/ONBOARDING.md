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

1. **Welcome:** introduce the local agent and proceed to setup.
2. **Passphrase:** choose and confirm the vault passphrase.
3. **Identity:** save a new twelve-word phrase and confirm the requested words,
   or import a twelve- or twenty-four-word phrase. Advanced import accepts a
   raw seed.
4. **Model:** choose a provider and model, enter a key and optionally test it,
   or use the offline echo bot. The key is staged encrypted until the venue
   starts.
5. **Name:** choose what the assistant should call the owner, then enter chat.

## Unlock

A small window of its own, shown before the main window exists: passphrase,
*Unlock*, and *Forgot it? Restore from your recovery phrase*. A wrong
passphrase fails the authenticated decryption and is reported there, not in
the log. While the venue starts, the dialog shows progress and the main window
appears only once it is ready. *Lock* (Settings → General) hides the window and
brings the dialog back. *Remember me* stores the passphrase in plaintext after
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
