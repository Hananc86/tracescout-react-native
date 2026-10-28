#import <React/RCTBridgeModule.h>

// The exported JS surface. Six semantic methods plus `state` — and nothing
// else. A method that is not listed here does not exist for JS, which is how
// the diagnostics surface stays fixture-only and native-only: it is simply
// never exported.
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
