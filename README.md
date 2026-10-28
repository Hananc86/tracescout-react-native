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

Verified against the toolchain below. Earlier versions in the declared range
are expected to work and are not yet covered by a build in this repository.

| | |
|---|---|
| React Native | `>= 0.73`, built and verified on 0.88 |
| Android | minSdk **26**, compileSdk 35, Gradle 8.14.3, AGP 8.7.3, Kotlin 2.1.20, Java 17 |
| iOS | 13.4 or later, Swift 5.9 |
| New Architecture | works; the module is a classic bridge module, which the New Architecture supports through interop |
| Expo | not verified. It needs a config plugin for the native configuration step below, which this package does not yet ship |

Installing this SDK raises an Android application's `minSdk` to 26, because the
capture transport's floor is 26 and that propagates through the manifest
merger. That is a support-matrix decision, not a build warning to suppress.

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

This is a prerelease, published under the `next` tag. The Android library and
the TypeScript surface are built and verified; see the repository for what each
release was tested against.

## License

MIT. See [LICENSE](./LICENSE) and [NOTICE](./NOTICE).
