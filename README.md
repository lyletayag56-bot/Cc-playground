# Orbit Space

A virtual space for Android. Apps and games you add run inside Orbit Space's own
processes, each with separate data and sign-ins, without being installed a second
time. Because the guest's code runs directly on the phone's CPU and GPU, games
built on native engines (Unity, Unreal and similar) run at close to their normal
speed.

It has two parts:

- **The launcher** (`app/`): the app you see. New code, about 1,900 lines of Java.
- **The engine** (`Bcore/`, `black-reflection/`, `compiler/`): the open-source
  [NewBlackbox](https://github.com/ALEX5402/NewBlackbox) virtual engine, Apache-2.0,
  with the changes listed in `NOTICE`.

> **Status: builds, not yet run on a phone.** GitHub Actions builds signed APKs
> from this source. Nobody has installed one on a device yet, so expect problems
> on first run and report them with a log (see the end of this file).

## Get an APK

### Option 1: download a build

Every push to this branch is built by GitHub Actions. The release APKs from the
latest build are on the `orbit-space-apk` branch of this repository, and each
workflow run also has them (plus debug builds) in its **orbit-space-apks**
artifact.

To build your own copy, push this folder to any GitHub repository on a branch
named `main`, `master` or `orbit-space`; the "Build APK" workflow does the rest.

### Option 2: Android Studio

Open the folder in a current Android Studio, let it sync (it downloads NDK
`29.0.13846066`), then run **Build > Build APK(s)**. JDK 21 is required.

### Which APK

| File | Use it when |
|---|---|
| `arm64-v8a-release` | Almost always. Runs 64-bit apps, which is every current game. |
| `armeabi-v7a-release` | You need an old 32-bit-only app. Only installs on phones that still support 32-bit. |
| `universal-release` | You are not sure what the phone is. On a 64-bit phone it behaves like the arm64 build. |
| `*-debug` | The release build misbehaves and you want fuller logs. Slower. |

Android will warn that the app was built for an older version of Android. That is
expected: the engine has to target Android 9 (API 28) to be allowed to run other
apps' code in its own processes. Play Protect may also flag it, as it does every
app of this kind.

## Using it

- **Add an app:** tap **+**, pick an installed app. It is cloned in place, using
  the APK files already on the phone, so the original must stay installed. This is
  the right way to add large games, including ones delivered as split APKs.
- **APK file:** installs a single `.apk`. Bundles (`.xapk`, `.apks`) are not
  supported; install those on the phone first and add them from the list.
- **Long-press an app** for game mode, a home-screen shortcut, force stop, clear
  data and remove.
- **Spaces:** the chip at the top right switches between spaces. Each space has
  its own copy of every app's data, so the same game can be signed in to a
  different account in each.
- **Google services:** in the ⋮ menu. Adds Play services, Play Store and Play
  Games to the current space so games can sign in with Google.

### Game mode

Apps the system classes as games get a dot on their icon and have game mode on.
You can switch it per app. For those apps Orbit Space can:

- stop every other app running in your spaces before the game starts,
- ask the display for its fastest refresh rate,
- keep the screen on,
- turn on sustained performance mode (off by default).

The app is also declared to Android as a game, so the phone's own game booster
(Game Booster, Game Turbo and so on) can be pointed at Orbit Space and will then
cover everything running inside it.

## What "fast" means here

- Native code runs unmodified on the real CPU and GPU. There is no emulation and
  no graphics translation.
- Calls from the guest into Android (starting activities, looking up packages,
  opening files under its data directory) go through the engine's hooks. That adds
  a little overhead to startup and file-heavy loading screens, not to rendering.
- Java/Kotlin code in guest apps may run JIT-compiled rather than precompiled,
  so app-style (non-game) software can start more slowly than normal.
- Guests share Orbit Space's memory budget. On phones with 6 GB of RAM or less,
  use the "free memory" option and avoid running two heavy apps at once.

## Root

Orbit Space does not need root and does not create it.

On a rooted phone, guest apps run under Orbit Space's own Linux user. When one of
them runs `su`, your root manager (Magisk, KernelSU) shows the request as coming
from Orbit Space. Granting it gives root to **every** app in **every** space, so
only do that if you trust all of them. This path is untested; the engine's file
redirection may interfere with some root tools.

The engine's root-hiding option is switched off and the launcher has no setting
for it.

## Limits you should know about

- **Online games with anti-cheat** generally detect virtual environments and may
  refuse to run or ban the account. Nothing here tries to defeat that: the
  engine's native device-spoofing module was removed and root hiding is off. The
  engine does still contain a compatibility shim that skips a few known
  sandbox-detection components in guest apps (`isAntiDetectProvider` in
  `BActivityThread.java`); it was left as upstream wrote it.
- **Game data is per space.** A game that downloads several GB of assets will
  download them again inside the space.
- **Not every app works.** App-level virtualization chases Android's internals;
  each Android release breaks things, and the engine is a community project of
  uneven quality. It claims support up to Android 14 and beyond; newer versions are
  less tested.
- **Prebuilt binaries:** the engine ships two prebuilt copies of the Dobby hooking
  library (`Bcore/src/main/cpp/Dobby/*/libdobby.a`) that were not audited.
- **Apps in a space are not isolated from each other at the OS level.** They all
  run as the same Linux user. Do not put an app you distrust next to one that
  holds something valuable.

## Privacy changes to the engine

As published, the engine uploaded logcat output and device details to a
third-party server whenever a guest app crashed, and continuously copied the
device log into `Downloads/logs`. Both are disabled here. See `NOTICE` for the
full list of changes.

## The signing key

`signing/orbit-debug.keystore` is a throwaway key with a public password, included
so that every build can update the previous one without wiping your spaces. Anyone
with this repository can sign an APK that Android accepts as an update to yours.
Keep the repository private, and generate your own key before sharing builds.

## Reporting a problem

```
adb logcat -c
# reproduce the problem, then:
adb logcat -d > orbit-log.txt
```

The useful lines are tagged `OrbitSpace`, `OrbitEngine`, `OrbitGameMode`,
`BlackBoxCore` and `AndroidRuntime`.

## Rebranding

Change `applicationId` in `app/build.gradle` and `app_name` in
`app/src/main/res/values/strings.xml`.
