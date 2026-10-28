#import <Foundation/Foundation.h>
#import <objc/runtime.h>
#import <objc/message.h>

/// THE ORDERING GUARANTEE, AND NOTHING ELSE.
///
/// `NSURLSession` asks its delegate `respondsToSelector:` once, WHEN THE SESSION
/// IS CREATED, and caches the answer for the session's whole life. React Native
/// creates that session lazily on its first request. So an observer installed
/// after a host application's startup fetch is cached as "does not respond" and
/// then SILENTLY NEVER FIRES — network capture dead for the rest of the process,
/// with every unit test still green. That is not an edge case: host apps
/// routinely issue configuration, auth-refresh or feature-flag requests before
/// any analytics SDK is initialised.
///
/// `+load` runs at dyld image load, BEFORE `main`, therefore before any
/// application code — JS or native — can issue a request. It is the only hook
/// that is early enough by construction rather than by convention.
///
/// THIS IS NOT A SWIZZLE. Nothing here exchanges, replaces or wraps an
/// implementation. The work it triggers is a single `class_addMethod` of a
/// selector React Native does not implement, refused outright if anything else
/// already implements it. No `URLProtocol`, no `NSURLSessionConfiguration`
/// provider, no request interceptor, no `method_setImplementation`, no
/// `method_exchangeImplementations`.
///
/// AND IT DELIBERATELY RESOLVES ITS TARGET BY NAME. A direct Swift call would
/// require the generated interface header and a link-time dependency, which
/// would make this file un-buildable in the plain SwiftPM target the unit suite
/// uses. Resolving by name keeps ONE source tree: `swift test` compiles the
/// Swift half, the pod compiles both.
@interface TraceScoutRNObserverLoad : NSObject
@end

@implementation TraceScoutRNObserverLoad

+ (void)load {
    @autoreleasepool {
        Class bootstrap = NSClassFromString(@"TSRNNetworkObserverBootstrap");
        if (bootstrap == Nil) {
            // Fail closed and SILENT: no observation, nothing altered. Logging
            // here would mean file IO before main on every launch.
            return;
        }
        SEL sel = NSSelectorFromString(@"installFromImageLoad");
        if (![bootstrap respondsToSelector:sel]) {
            return;
        }
        ((void (*)(id, SEL))objc_msgSend)(bootstrap, sel);
    }
}

@end
