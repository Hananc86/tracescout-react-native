# @tracescout/react-native

Session replay and loss-accounted capture for React Native, for Android and iOS.

The SDK records what an application tells it: a route was entered, a navigation
happened, the app backgrounded, this route must never be recorded. Capture is
privacy-first by construction — text is masked before anything is rasterized,
and a screen the application declares off-limits is never traversed at all.

## Install

This package is **not on the npm registry**: `npm install @tracescout/react-native`
cannot resolve it, and the first publication is the package owner's to make.
Install a packed artifact instead.

For a released version, download the `.tgz` attached to its GitHub release and
check it against the digest the release notes name. For the version in this
repository, pack it yourself — there is no lockfile here, so use `npm install`:

```sh
npm install && npm run build && npm pack   # -> tracescout-react-native-<version>.tgz
```

Then, in your application:

```sh
npm install /path/to/tracescout-react-native-<version>.tgz
cd ios && pod install
```

Autolinking registers the native module on both platforms: no `Podfile` edit
and no `MainApplication` package-list edit is needed. One **build-file** change
is required on Android, below.

### Required on Android: raise the app's `minSdkVersion` to 26

The SDK declares `minSdk 26` and the React Native template ships 24, so the
manifest merger stops the application's build until it raises its own floor:

```
> Task :app:processReleaseMainManifest FAILED
> Manifest merger failed : uses-sdk:minSdkVersion 24 cannot be smaller than
  version 26 declared in library [:tracescout_react-native] …
```

In the application's `android/build.gradle`, under `buildscript { ext { … } }`:

```diff
-        minSdkVersion = 24
+        minSdkVersion = 26
```

Raise it only as far as 26 — if your application already requires 27 or higher,
leave your own value alone. Do not suppress the merger with
`tools:overrideLibrary`: that declares support for API levels below the floor
the capture transport actually requires, and the requirement is real rather
than a warning to silence.

That one line is the whole native-build change. With it, a clean template
application compiles and links the module; see *Status* for exactly what was
measured, and *Supported versions* for what integrating costs.

### Supported versions

The declared range is what the package allows. The "measured" column names the
single toolchain a claim was actually exercised against **and the version of
this package it was exercised with** — the two are not always the same version,
and a measurement is never carried forward to a later one. Every other
combination in the declared range is expected to work and has not been built
here. Nothing in this table is a tested matrix.

| | declared | measured |
|---|---|---|
| React Native | `>= 0.73` | **0.88.1** — run at next.1, built at next.3 |
| Android | minSdk **26** | at next.3: compileSdk 37, buildTools 37.0.0, targetSdk 36, Gradle 9.4.1, Kotlin 2.2.0, JDK 21 — **release build only, not run** |
| iOS | 13.4 or later, Swift 5.9 | at next.1, on an iOS 18.5 simulator. **Not built at next.3** |
| New Architecture | works | the module is a classic bridge module, which the New Architecture supports through interop |
| Expo | not verified | it needs a config plugin for the native configuration step below, which this package does not yet ship |

Installing this SDK raises an Android application's `minSdk` to 26, because the
capture transport's floor is 26 and that propagates through the manifest
merger. That is a support-matrix decision, not a build warning to suppress —
it is the required build-file change described under *Install*.

### Permissions it adds

The SDK's own manifest declares two permissions, and its WorkManager dependency
contributes three more through the manifest merger. Measured on a merged
release manifest, not estimated:

| permission | contributed by |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | this SDK, to upload captured records |
| `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK` | `androidx.work`, which the SDK uses to defer a drain when there is no usable network |

It declares no location, storage, camera, contacts or advertising-identifier
permission, and reads no hardware or advertising identifier.

## Configure

The ingest credential is installed in **native** code, by your application.
There is no JavaScript method that accepts a key: handing a plaintext
credential to the JavaScript runtime would defeat the platform keystore that
protects it. You supply your own project id and ingest key; this package
contains no credential of any kind.

**Android** — `MainApplication.onCreate`:

```kotlin
import com.tracescout.mobile.rn.RnSecureConfig

override fun onCreate() {
  super.onCreate()
  RnSecureConfig.configure(
    this,
    projectId   = BuildConfig.TRACESCOUT_PROJECT_ID,
    ingestKey   = BuildConfig.TRACESCOUT_INGEST_KEY,
    environment = "production",
    baseUrl     = "https://api.tracescout.com",
  )
}
```

On Android the key is encrypted with an AES-256/GCM key held in the Android
Keystore and never extractable from it. The plaintext key is never written to
AsyncStorage, SharedPreferences, the JavaScript bundle, a log, a bridge
response or crash metadata.

**iOS** — `application(_:didFinishLaunchingWithOptions:)`:

```swift
import TraceScoutRN

SecureConfig.configure(
  projectId: "YOUR_PROJECT_ID",
  ingestKey: yourIngestKey,
  environment: "production",
  endpoint: "https://api.tracescout.com"
)
```

On iOS the credential is held in the keychain. Note the argument is
`endpoint:`, where Android's is `baseUrl`.

### Start the uploader

Configuring stores the credential; it does not start the upload transport.
Start it once, in the same native place, immediately after configuring:

```kotlin
RnTransport.start(this)              // Android, import com.tracescout.mobile.rn.RnTransport
```

```swift
RnTransport.shared.start()           // iOS
```

Both are idempotent, so a second call cannot start a second uploader.

Until this runs, `state()` reports `NOT_CONFIGURED` and nothing is ever
uploaded. What else happens differs by platform, and both were measured in a
clean application at **0.1.0-next.1**: on **Android** the SDK still accepts each call and reports
`RECORDED`, so semantics are captured and never sent; on **iOS** the transport
owns session identity, so calls report `NOT_CONFIGURED` or `NO_SESSION` and
nothing is captured at all. After it runs, `state()` reports `READY` on both.

That one call is the difference between an SDK that is installed and an SDK
that is reporting, so check `state()` once during integration.

## Use

```ts
import TraceScout from '@tracescout/react-native';

await TraceScout.startSession('home');

// On navigation.
await TraceScout.navigate('home', 'checkout', 'Checkout', 'push');

// A screen that must never be recorded. Declared before it renders, so its
// content never enters the capture path.
await TraceScout.declareNeverRecord('payment_details');

// From an AppState listener.
await TraceScout.lifecycle('background');
```

Every call resolves to a short outcome string and never rejects. An
observability SDK that throws into its host has done more damage than the
problem it was installed to find. If the native module is not linked, calls
resolve to `unavailable:native_module_not_linked` and `isAvailable()` is
`false`.

### Privacy settings

Privacy is native and is not settable from JavaScript, so a JavaScript bug
cannot widen what is captured.

| setting | default | where |
|---|---|---|
| text, inputs, labels and images | protected | always; masking happens before rasterization |
| visual frames | **off** | `RnSecureConfig.setFramesEnabled(context, true)` |
| network capture | **off** | `RnSecureConfig.setNetworkCaptureEnabled(context, true)` |
| interaction capture | **off** | `RnSecureConfig.setInteractionCaptureEnabled(context, true)` |
| never-record screens | none | `declareNeverRecord(routeId)` from JavaScript |

`declareNeverRecord` is the one privacy control exposed to JavaScript, because
it can only ever *reduce* what is captured.

## API

| | |
|---|---|
| `isAvailable()` | whether the native module is linked and callable now |
| `startSession(initialRouteId?)` | begin a session |
| `navigate(from, to, toName?, transition?)` | record a navigation |
| `screenSnapshot(routeId)` | snapshot the named route as it is now |
| `lifecycle(transition)` | record a lifecycle transition |
| `declareNeverRecord(routeId, never?)` | never record this route |
| `hostAttached(routeId, hostId, rootHandle?)` | declare a capture-root owner |
| `hostReadinessPending` / `hostReady` / `hostFailed` / `hostDetached` | host readiness |
| `identify(userId)` | name the end user this session belongs to |
| `state()` | transport state; status only, never records or credentials |

Session identity, project, environment, protocol version, sequence numbers,
both clocks and the privacy posture are native and have no parameter to set.

## Status

A prerelease, and **not published**: the package is absent from the npm
registry, so there is no `next` dist-tag to install from and every version so
far exists only as a packed artifact (see *Install*). The first publication is
the package owner's to make.

What has been measured, stated per version — a proof belongs to the version it
was run against and is not inherited by a later one:

- **0.1.0-next.3 (this version) — Android build only.** An application
  generated from the official React Native template (0.88.1) installed this
  version's packed tarball, autolinked it on both platforms, type-checked the
  public entry point against the shipped types, and built both the autolinked
  module and the whole application to a release APK, after the one required
  `minSdkVersion` change above. The installed copy under `node_modules` was
  byte-identical to all 249 files in the tarball, so nothing was being read
  from a source tree. **Nothing was run**: no device, emulator or simulator.
  **iOS was not built** at this version — autolinking resolves
  `TraceScoutRN.podspec`, which is not the same thing as a pod that compiled.
- **0.1.0-next.1 — an integration run on both platforms.** A clean React
  Native 0.88.1 host application was built and launched against the next.1
  artifact, and 15/15 JavaScript bridge calls were exercised on an Android
  API-35 emulator and an iOS 18.5 simulator. That is next.1's evidence and is
  not re-claimed for next.3. It was an integration proof only: it ran against
  a synthetic ingest key and an unroutable endpoint, so nothing was uploaded
  and no server received a chunk.

So the strongest current claim for **this** version is that it installs,
autolinks, type-checks and builds on Android; the both-platform runtime
evidence is next.1's, and no version here has been shown to upload.

`identify` is new in this prerelease. It is declared by all four native and
JavaScript surfaces, the Android library compiles with it exposed to the React
bridge, and the native implementation it forwards to carries its own tests in
the upstream SDK — but it has **not** yet been exercised from JavaScript on a
device, so it is listed here as unvalidated rather than as tested. See the
repository for what each release was tested against.

## License

MIT. See [LICENSE](./LICENSE) and [NOTICE](./NOTICE).
