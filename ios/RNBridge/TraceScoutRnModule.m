#import <React/RCTBridgeModule.h>

// The exported JS surface. Seven semantic methods plus `state` — and nothing
// else. A method that is not listed here does not exist for JS, which is how
// the diagnostics surface stays fixture-only and native-only: it is simply
// never exported.
//
// THE COUNT WENT SIX -> SEVEN WITH G2 `identify`, and the pin is unchanged
// in kind: this list admits SEMANTIC declarations and refuses everything that
// would let JS decide what gets observed or claim a producer identity. A user
// id is the one identity fact the SDK cannot establish for itself (only the
// host's auth system knows who is signed in), so it must come from the host —
// which is exactly why `appIdentifier`, posture, capture mode and both clocks
// are still absent from here and read natively instead.
@interface RCT_EXTERN_MODULE (TraceScoutRn, NSObject)

RCT_EXTERN_METHOD(startSession:(NSString *)initialRouteId
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(navigate:(NSString *)fromRouteId
                  toRouteId:(NSString *)toRouteId
                  toRouteName:(NSString *)toRouteName
                  transition:(NSString *)transition
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(screenSnapshot:(NSString *)routeId
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(lifecycle:(NSString *)transition
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(declareNeverRecord:(NSString *)routeId
                  never:(BOOL)never
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

// G2 — name the identified user for the live session.
//
// `nullable`, DELIBERATELY. A JS `null` must reach Swift as nil so the
// shared `IdentityPolicy` can refuse it as INVALID_INPUT. Declaring it
// `nonnull` would let the bridge substitute an empty string and the
// blank-is-a-refusal rule would be decided here instead of in the policy both
// platforms share — and a refusal is NOT a sign-out, which this field cannot
// express at all.
RCT_EXTERN_METHOD(identify:(nullable NSString *)userId
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

// The route-host integration contract. Four semantic declarations, nothing more.
// `rootHandle` IS THE CAPTURE ROOT — a react tag naming the view this host
// owns. Readiness and ownership are separate declarations; a host may attach
// with 0 here, participate on the outcome axis, and authorize no capture.
RCT_EXTERN_METHOD(hostAttached:(NSString *)routeId
                  hostId:(NSString *)hostId
                  rootHandle:(nonnull NSNumber *)rootHandle
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(hostReadinessPending:(NSString *)routeId
                  hostId:(NSString *)hostId
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(hostReady:(NSString *)routeId
                  hostId:(NSString *)hostId
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(hostFailed:(NSString *)routeId
                  hostId:(NSString *)hostId
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(hostDetached:(NSString *)routeId
                  hostId:(NSString *)hostId
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

RCT_EXTERN_METHOD(state:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)

@end
