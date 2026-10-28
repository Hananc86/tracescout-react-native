/**
 * The TraceScout React Native SDK's JavaScript surface.
 *
 * An application states semantics here: a route was entered, a navigation
 * happened, the app backgrounded, this route must never be recorded. Producer
 * identity, protocol version, session, project, environment, sequence, both
 * clocks and the privacy posture are native and have no parameter to set.
 *
 * The ingest credential is installed natively, by the application, in
 * `Application.onCreate` on Android and `application(_:didFinishLaunching…)`
 * on iOS. There is no JavaScript method that accepts a credential: handing the
 * plaintext key to the JavaScript runtime would defeat the platform keystore
 * that protects it.
 *
 * Every call is fire-and-forget and resolves to a short outcome string. A call
 * never rejects and never throws into its host: an observability SDK that
 * throws has done more damage than the problem it was installed to find.
 */

import { NativeModules } from 'react-native';

/** The outcome string the native bridge reports for a call. */
export type Outcome = string;

/** How a navigation happened, as the application understands it. */
export type Transition = 'push' | 'pop' | 'replace' | 'tab' | 'modal' | string;

/** An application lifecycle transition. */
export type Lifecycle = 'foreground' | 'background' | string;

interface NativeBridge {
  startSession(initialRouteId: string | null): Promise<Outcome>;
  navigate(
    fromRouteId: string | null,
    toRouteId: string,
    toRouteName: string | null,
    transition: string,
  ): Promise<Outcome>;
  screenSnapshot(routeId: string): Promise<Outcome>;
  lifecycle(transition: string): Promise<Outcome>;
  declareNeverRecord(routeId: string, never: boolean): Promise<Outcome>;
  hostAttached(routeId: string, hostId: string, rootHandle: number): Promise<Outcome>;
  hostReadinessPending(routeId: string, hostId: string): Promise<Outcome>;
  hostReady(routeId: string, hostId: string): Promise<Outcome>;
  hostFailed(routeId: string, hostId: string): Promise<Outcome>;
  hostDetached(routeId: string, hostId: string): Promise<Outcome>;
  state(): Promise<string>;
}

const native: NativeBridge | undefined = (
  NativeModules as { TraceScoutRn?: NativeBridge }
).TraceScoutRn;

/**
 * Whether the native module is linked and reachable from JavaScript right now.
 *
 * It exists because "the symbol is in the binary" and "JavaScript can call it"
 * are different claims. An application can branch on this rather than
 * discovering the answer from a silent no-op.
 */
export function isAvailable(): boolean {
  return typeof native?.startSession === 'function';
}

const UNAVAILABLE = 'unavailable:native_module_not_linked';

async function call<K extends keyof NativeBridge>(
  method: K,
  ...args: Parameters<NativeBridge[K]>
): Promise<Outcome> {
  const bridge = native;
  if (!bridge || typeof bridge[method] !== 'function') return UNAVAILABLE;
  try {
    // The native signatures are fixed by the bridge; the cast is the one place
    // the variadic forwarding meets them.
    const fn = bridge[method] as (...a: unknown[]) => Promise<Outcome>;
    return await fn.apply(bridge, args as unknown[]);
  } catch (e) {
    return 'failed:' + (e instanceof Error ? e.message : String(e));
  }
}

/**
 * Begin a session. The session identity, project and environment come from the
 * native configuration the application installed; nothing here can set them.
 *
 * Calling this before the application has configured the SDK natively is safe
 * and reports that it was not configured.
 */
export function startSession(initialRouteId: string | null = null): Promise<Outcome> {
  return call('startSession', initialRouteId);
}

/** Record that the application navigated from one route to another. */
export function navigate(
  fromRouteId: string | null,
  toRouteId: string,
  toRouteName: string | null = null,
  transition: Transition = 'push',
): Promise<Outcome> {
  return call('navigate', fromRouteId, toRouteId, toRouteName, transition);
}

/** Ask for a snapshot of the named route as it is on screen now. */
export function screenSnapshot(routeId: string): Promise<Outcome> {
  return call('screenSnapshot', routeId);
}

/** Record an application lifecycle transition, such as backgrounding. */
export function lifecycle(transition: Lifecycle): Promise<Outcome> {
  return call('lifecycle', transition);
}

/**
 * Declare that a route must never be recorded.
 *
 * The declaration is consulted before a screen is traversed, so a declared
 * route's content never enters the capture path at all. The session still
 * records that the route was visited.
 */
export function declareNeverRecord(routeId: string, never = true): Promise<Outcome> {
  return call('declareNeverRecord', routeId, never);
}

/**
 * Declare that a component owns the capture root for a route.
 *
 * `rootHandle` is a React node tag the caller already owns, found with
 * `findNodeHandle`. Pass 0 to take part in route-host outcomes while
 * authorizing no capture. An application that calls none of the host methods
 * is unaffected: no host, no verdict.
 */
export function hostAttached(
  routeId: string,
  hostId: string,
  rootHandle = 0,
): Promise<Outcome> {
  return call('hostAttached', routeId, hostId, rootHandle);
}

/** Declare that an attached host is not ready to be captured yet. */
export function hostReadinessPending(routeId: string, hostId: string): Promise<Outcome> {
  return call('hostReadinessPending', routeId, hostId);
}

/** Declare that an attached host has become ready. */
export function hostReady(routeId: string, hostId: string): Promise<Outcome> {
  return call('hostReady', routeId, hostId);
}

/** Declare that an attached host failed to become ready. */
export function hostFailed(routeId: string, hostId: string): Promise<Outcome> {
  return call('hostFailed', routeId, hostId);
}

/** Declare that a host is gone, for example because its screen unmounted. */
export function hostDetached(routeId: string, hostId: string): Promise<Outcome> {
  return call('hostDetached', routeId, hostId);
}

/**
 * The transport's current state. Status only: never the pending records and
 * never the credential.
 */
export function state(): Promise<string> {
  if (!native || typeof native.state !== 'function') {
    return Promise.resolve(UNAVAILABLE);
  }
  return native.state().catch(() => UNAVAILABLE);
}

export default {
  isAvailable,
  startSession,
  navigate,
  screenSnapshot,
  lifecycle,
  declareNeverRecord,
  hostAttached,
  hostReadinessPending,
  hostReady,
  hostFailed,
  hostDetached,
  state,
};
