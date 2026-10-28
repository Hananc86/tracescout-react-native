package com.tracescout.rn

/**
 * THE ONE conversion from a native touch point into replay viewport space.
 *
 * Selected in `ANDROID-INTERACTION-COORDINATE-ANALYSIS-r1` from 15 measured
 * rows across three real runtime configurations (API 35 1080x2400@420, API 26
 * 1080x1920@420, API 35 800x1280@240):
 *
 * replayX = nativeWindowX
 * replayY = nativeWindowY
 *
 * ## Why every other term is absent
 *
 * Each was tested against the measurements and falsified — none was omitted for
 * tidiness:
 *
 * - **content-root origin subtraction** fails on API 26, whose content root is
 * inset 63 px below the status bar while API 35 is edge-to-edge at 0. That
 * one difference is the discriminator, and a single-API sample could not
 * have settled it;
 * - **status-bar inset subtraction** fails on every row (max 127.5 px off);
 * - **density scaling is falsified outright** by the 240 dpi configuration —
 * hundreds of pixels off. `density` is an explanatory variable, NOT a
 * transform term, and it is absent from this signature so it cannot be
 * quietly reintroduced;
 * - **content→decor scaling** fails wherever the rectangles differ.
 *
 * The reason it is identity is not a coincidence to be relied on blindly:
 * `Window.Callback` delivers window-local coordinates, and the replay hierarchy
 * expresses node bounds via `getLocationInWindow` — the same space. The
 * measurement confirms the two agree; the semantics are why they should.
 *
 * THE CONVERTER CANNOT SEE THE EXPECTED ANSWER. It takes no target bounds,
 * no target centre, no node identity, no `nativeID` and no declaration token —
 * so a certification run cannot be satisfied by the conversion quietly steering
 * toward the rectangle it is about to be checked against.
 *
 * AND IT NEVER CLAMPS. A point outside the viewport is returned as-is and a
 * degenerate viewport is REFUSED. Clamping would turn a wrong transform into a
 * plausible one, which is precisely the failure a coordinate proof exists to
 * catch.
 */
object ReplayCoordinates {

    /** A touch point in the application window's coordinate space. */
    data class NativeTouchPoint(val windowX: Float, val windowY: Float)

    /** The replay viewport the hierarchy and frame geometry are expressed in. */
    data class ReplayViewport(val width: Int, val height: Int)

    data class ReplayViewportPoint(val replayX: Float, val replayY: Float)

    /**
 * The runtime window geometry a positional coordinate is only valid within.
 *
 * MEASURED, NEVER HARD-CODED. No 1080x2400, no density, no inset, no API
 * level appears here — the certified class is "the Activity window IS the
 * display", and that is a RELATIONSHIP between two runtime rectangles, not
 * a list of devices we happened to test on.
 */
    data class WindowGeometry(
        val windowLeft: Int, val windowTop: Int,
        val windowWidth: Int, val windowHeight: Int,
        /**
 * THE FULL DISPLAY, NOT THE APP-USABLE AREA AND NOT THE CURRENT
 * WINDOW. Measured (`FULL-DISPLAY-BOUNDS-AUDIT`):
 *
 * API 26 fullscreen window 1080x1920 · DisplayMetrics 1080x1794
 * API 35 freeform window 577x1080 · DisplayMetrics 577x1080
 * · currentWindowMetrics 577x1080
 * · maximumWindowMetrics 1080x2400
 *
 * `DisplayMetrics` excludes the navigation bar, so a fullscreen API-26
 * window looked REDUCED and every positional interaction was silently
 * downgraded. And in freeform BOTH `DisplayMetrics` and
 * `currentWindowMetrics` equal the window — the comparison collapses
 * into "the window equals itself" and the boundary evaporates. Only the
 * MAXIMUM/real display extent distinguishes the two cases.
 */
        val displayWidth: Int, val displayHeight: Int,
        /**
 * Defence in depth only. The rectangle relation remains the primary
 * certified boundary; this cannot rescue an eligible-looking rectangle
 * and is never the sole reason for a verdict.
 */
        val multiWindow: Boolean = false,
    )

    /** Why a positional coordinate was refused. Closed set, never free text. */
    enum class GeometryIneligibility(val wire: String) {
        NON_ZERO_WINDOW_ORIGIN("non_zero_window_origin"),
        REDUCED_WINDOW("reduced_window"),
        INVALID_GEOMETRY("invalid_geometry"),
        /** Android says so outright. Defence in depth, never the only check. */
        MULTI_WINDOW_MODE("multi_window_mode"),
    }

    /**
 * `POSITIONAL GEOMETRY v1: FULLSCREEN-ELIGIBLE OR COORDINATE-FREE`.
 *
 * DOCUMENTING THE MULTI-WINDOW LIMITATION IS NOT ENFORCING IT. The
 * certification covers full-screen Activity windows only; a freeform run
 * measured `event.x/y` that were NOT window-local relative to the window
 * origin, so the identity mapping does not hold there. Without this gate an
 * otherwise valid tap on a `ts-position-safe` target in split-screen would
 * emit a confidently wrong coordinate — a false pulse, which is worse than
 * no pulse.
 *
 * Eligible means, from runtime metrics only:
 * - the window is anchored at the display origin;
 * - its extent IS the display's, not a reduced region;
 * - every value is positive and finite.
 *
 * Anything else — freeform, split-screen, offset, reduced, or simply
 * unclassifiable — is ineligible, and the interaction becomes
 * coordinate-free rather than coordinate-wrong.
 */
    fun positionalEligibility(g: WindowGeometry): GeometryIneligibility? {
        if (g.windowWidth <= 0 || g.windowHeight <= 0
            || g.displayWidth <= 0 || g.displayHeight <= 0
        ) return GeometryIneligibility.INVALID_GEOMETRY
        if (g.windowLeft != 0 || g.windowTop != 0)
            return GeometryIneligibility.NON_ZERO_WINDOW_ORIGIN
        if (g.windowWidth != g.displayWidth || g.windowHeight != g.displayHeight)
            return GeometryIneligibility.REDUCED_WINDOW
        // AFTER the rectangle rule, deliberately. If the platform's own flag
        // were checked first, a rectangle regression could hide behind it and
        // the primary boundary would stop being exercised.
        if (g.multiWindow) return GeometryIneligibility.MULTI_WINDOW_MODE
        return null
    }

    fun isPositionalEligible(g: WindowGeometry): Boolean = positionalEligibility(g) == null

    /**
 * @return the converted point, or `null` when the inputs cannot support a
 * conversion. **Null means "no position", never a fabricated one** — an
 * interaction may legitimately be emitted coordinate-free, and inventing
 * a coordinate to keep an event well-formed is the one thing this must
 * never do.
 */
    fun convert(point: NativeTouchPoint, viewport: ReplayViewport): ReplayViewportPoint? {
        if (!point.windowX.isFinite() || !point.windowY.isFinite()) return null
        // A zero or negative viewport is not a viewport. Refusing is the only
        // honest answer: there is no space to express the point in.
        if (viewport.width <= 0 || viewport.height <= 0) return null
        return ReplayViewportPoint(point.windowX, point.windowY)
    }
}
