# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run a single test class
./gradlew test --tests "top.clspd.apps.MyClipboardTransferHelper.ExampleUnitTest"
```

## Architecture

Encrypted clipboard transfer over LAN: Sender sends text to Receiver via TLS + TLV protocol with password authentication.

### Key components

- **`AppClass`** (`app/.../AppClass.java`) — Application subclass. On first launch, generates an EC-384 key pair and self-signed X.509 certificate (pure Java ASN.1 DER encoding, no BouncyCastle). Saves to `files/receiver/cert.{cer,key}`. Creates `files/sender/` and `files/receiver/` directories.
- **`CryptoHelper`** (`security/CryptoHelper.java`) — Self-signed cert generation with manual ASN.1 DER encoding. TLS `SSLContext` factory for server (with server cert + trust-all clients) and client (trust-all, fingerprint check at app layer). Known-hosts file management (`IP <SHA256 fingerprint>` format).
- **`TLVHelper`** (`protocol/TLVHelper.java`) — Binary protocol framing: Type(1) + Length(4) + Value(N). Types: 0=Heartbeat, 1=Auth-Query, 2=Auth, 3=Auth-Feedback, 4=TextData.
- **`ReceiverActivity`** — Starts `SSLServerSocket`, password auth, needConfirm flow, clipboard write. Single active client at a time. Sends heartbeat every 15s.
- **`SenderActivity`** — `SSLSocket` connect, known-hosts check (dialog for unknown/changed), password dialog, sends clipboard text. Heartbeat every 15s.
- **`ReceiverNewConnectionConfirmActivity`** — `startActivityForResult` dialog showing client IP, port, server cert fingerprint. Returns RESULT_OK/CANCELED.

### Transport security

TLS with self-signed certs. Server presents its cert; client verifies via known-hosts fingerprint (SHA-256). No client cert — client auth is done at app layer via password. All data is TLS-encrypted; TLV framing runs on top of the encrypted `DataInput/OutputStream`.

### Auth flow order

1. TLS handshake (server cert verified by client via known_hosts)
2. Client sends Auth-Query (Type=1)
3. Server: no password set → needConfirm → auto-auth; password set → reply 0x0 (not authenticated)
4. Client sends Auth (Type=2) with password
5. Server: verify password first → if correct, THEN check needConfirm → reply 0x1 (authenticated) / 0x0 (wrong)
6. Only authenticated connections can send/receive TextData (Type=4)

**needConfirm is checked AFTER password verification** — this prevents wrong-password users from triggering confirmation dialogs (harassment prevention).

### Package naming quirk

The source code uses two base packages:
- Package declaration: `app.MyApp.MyClipboardTransferHelper`
- R class / build namespace: `top.clspd.apps.MyClipboardTransferHelper`

These are different. Import `top.clspd.apps.MyClipboardTransferHelper.R` for resource references.

### Critical rules

- **All TLS socket close must happen off main thread.** `SSLSocket.close()` sends TLS `close_notify` which does network I/O. Both `disconnect()` (Sender) and `ClientConnection.close()` (Receiver) spawn background threads internally for the actual close.
- **Activities survive config changes.** All activities have `android:configChanges="orientation|screenSize|screenLayout|uiMode|keyboardHidden"` so rotation/dark-mode don't destroy server sockets or connections.
- **Only one client at a time.** `ReceiverActivity` rejects new connections if `activeClient != null`.
