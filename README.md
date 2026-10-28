# @tracescout/react-native

Session replay and loss-accounted capture for React Native, for Android and iOS.

The SDK records what an application tells it: a route was entered, a navigation
happened, the app backgrounded, this route must never be recorded. Capture is
privacy-first by construction — text is masked before anything is rasterized,
and a screen the application declares off-limits is never traversed at all.

## Install

```sh
npm install @tracescout/react-native
cd ios && pod install
```

Autolinking registers the native module on both platforms. No `Podfile` or
`MainApplication` package-list edit is needed.

### Supported versions

The declared range is what the package allows. The "built and run on" column
is the single toolchain a clean external application was actually compiled and
launched against; every other combination in the declared range is expected to
work and has not been built here. Nothing in this table is a tested matrix.

| | |
|---|---|
| React Native | declared `>= 0.73`; built and run on **0.88.1** |
| Android | minSdk **26** (declared); built and run on compileSdk 37, Gradle 9.4.1, Kotlin 2.3.0, JDK 21 |
| iOS | declared 13.4 or later, Swift 5.9; built and run on the iOS simulator |
| New Architecture | works; the module is a classic bridge module, which the New Architecture supports through interop |
| Expo | not verified. It needs a config plugin for the native configuration step below, which this package does not yet ship |

Installing this SDK raises an Android application's `minSdk` to 26, because the
capture transport's floor is 26 and that propagates through the manifest
merger. That is a support-matrix decision, not a build warning to suppress.

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
clean application: on **Android** the SDK still accepts each call and reports
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
| `state()` | transport state; status only, never records or credentials |

Session identity, project, environment, protocol version, sequence numbers,
both clocks and the privacy posture are native and have no parameter to set.

## Status

This is a prerelease, published under the `next` tag. A clean external React
Native application has been built and launched with this package on both
platforms, and every function in the API table above was called from
JavaScript on a running device. See the repository for what each release was
tested against.

## License

MIT. See [LICENSE](./LICENSE) and [NOTICE](./NOTICE).
