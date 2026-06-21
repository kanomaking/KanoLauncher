# KanoLauncher

A personal, non-commercial **Minecraft: Java Edition** launcher for Windows and Linux, built for my own use.

## What it is

KanoLauncher is a private hobby launcher that lets the account owner sign in with their own
Microsoft account, manage Minecraft instances and mods (via Modrinth), and launch the copy of
Minecraft Java Edition they own. It is not distributed commercially and is not affiliated with
Mojang or Microsoft.

- **Stack:** Java 21, JavaFX, Gradle
- **Content:** Modrinth (mod browsing / dependency resolution)
- **Loaders:** Vanilla + Fabric (NeoForge planned)
- **Platforms:** Windows (`.msi`) and Ubuntu (`.deb`)

## Authentication

The launcher uses the standard Microsoft OAuth2 **device-code flow** with the `XboxLive.signin`
scope, then performs the normal Minecraft sign-in chain:

```
Microsoft (MSA) -> Xbox Live -> XSTS -> Minecraft -> player profile
```

It requests `XboxLive.signin` solely so the account owner can authenticate their own Microsoft
account and obtain a Minecraft access token to launch the game they own. The app:

- performs **only** the standard auth chain and reads the player profile (username / UUID);
- **never** collects, transmits, or shares credentials with any third party;
- stores tokens **locally, encrypted** (AES-256-GCM, key wrapped in the OS keystore).

## Repository contents

- [`KanoLauncher.md`](KanoLauncher.md) — full build plan and design.
- [`login-test/`](login-test/) — a minimal, self-contained Microsoft/Minecraft login tester
  (single-file Java, device-code flow) used to validate the auth chain.

## Status

In development. Online features depend on Microsoft approving this app's registration for the
Minecraft API.
