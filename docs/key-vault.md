# The household key vault

The app uses two API keys the household pays for: Anthropic's, for the assistant
([assistant.md](assistant.md)), and Google's, for Google Maps ([maps.md](maps.md)). Neither is kept
on a phone. Both live on the server, in the relay's vault, sealed so that the relay (or anyone with a
copy of `relay.db`) can't read them, and only a phone signed in with a household member's Frigate
login can. A phone opens them into memory when it signs in and forgets them when the app ends.

## How it is sealed

All of it happens on the phone (`shared/.../vault/VaultCrypto.kt`), with the platform's own
cryptography through [cryptography-kotlin](https://github.com/whyoleg/cryptography-kotlin): the
JDK on Android; CryptoKit, and CommonCrypto for PBKDF2, on iOS.

| What | How |
| --- | --- |
| Login key | PBKDF2-SHA-256 of the Frigate password, 600,000 rounds, salted with the username |
| Member key pair | P-256; the private half sealed with the login key (AES-256-GCM) |
| Household key | 256 random bits; one copy, a *grant*, per member: ECDH with a one-off key pair against the member's public key, HKDF-SHA-256, AES-256-GCM |
| Each secret | AES-256-GCM under the household key, its name bound in as associated data |

The relay (`relay.py`, "vault") keeps the public keys, the sealed private keys, the grants and the
sealed secrets in `relay.db`'s `state` table, under `vault`. It decides only who may write what:

- `GET /vault`: the signed-in user's own sealed key pair and grant, everyone's public key and whether
  they hold the household key, and the sealed secrets.
- `PUT /vault/me`: sets the user's key pair (a first sign-in, or a new password). A grant made for
  an older pair stops counting.
- `PUT /vault/grants/{user}`: gives another member the household key. Only from a member holding it.
- `POST /vault/household`: starts a new household key. Only while nobody holds the current one.
- `PUT /vault/secrets`: sets or removes sealed secrets. Only from a member holding the household key.

## What a phone does on sign-in (`KeyVault`)

1. Works out the login key from the password the sign-in used (typed, or released by the saved
   login's fingerprint or Face ID check). About a second, once per sign-in.
2. Opens this login's key pair, or makes one and sends it up sealed.
3. Opens the household key from this login's grant. With no grant: if nobody holds the household
   key, it starts one; otherwise it waits ("Someone already using the household's keys has to open
   the app once to let your login in").
4. Opens the secrets, and grants the household key to any member still waiting.
5. Moves a key an older build kept on the phone (the assistant's) into the vault, and deletes the
   phone's copy.

While the vault can't be opened (signed out, the server out of reach, waiting to be let in), the
assistant can't be asked and the maps stay on OpenStreetMap; both say why. It is tried again every
three minutes, and whenever Settings or the assistant opens.

## What to know

- **A new Frigate password** makes a new key pair on the next sign-in. Someone else in the household
  lets it in again by opening the app. If that login was the only member, the old secrets can't be
  opened by anyone: the vault starts again empty, and the keys are typed in once more.
- **A new household member** is let in by the next phone that opens the app holding the household key.
- **The relay never sees** a password, the login key, a private key, the household key or an API key.
  A copy of `relay.db` gives someone the sealed blobs, which they would have to break one password at
  a time, at PBKDF2's 600,000 rounds a guess.
- **The keys do reach a phone's memory**: Google's and Anthropic's APIs are called from the phone,
  with the key in the request. Restrict each key at its provider (the Map Tiles API only; an
  Anthropic workspace with a spend limit).
- **iOS builds** link CryptoKit's Swift libraries from `/Applications/Xcode.app`. With Xcode anywhere
  else, add the library's Gradle plugin with `configureSwiftLinkerOpts = true` (see its
  "Xcode / Swift compatibility" page).
