package com.tracescout.mobile.rn

import android.app.Activity
import com.tracescout.rn.InteractionPolicy
import com.tracescout.rn.RnEnvelope
import java.util.UUID

/**
 * The ONLY path from an admitted interaction decision to the durable queue.
 *
 * IT DECIDES NOTHING. Every privacy rule was settled upstream by #124 and the
 * geometry gate; this builds the wire shape the decision already implies. A
 * second place that could choose whether to include a coordinate is a second
 * policy, and the two would disagree exactly once.
 *
 * NOTHING IS ALLOCATED BEFORE THE SHAPE IS FINAL. `RnTransport.enqueue` takes
 * a BUILDER and assigns the sequence inside the same critical section as the
 * durable append, so the number is issued only for an event that is definitely
 * being written. A positional event is never allocated and then stripped into a
 * redacted one — the outcome arrives here already decided.
 */
internal object RnInteractionEmitter {

    fun emit(
        activity: Activity,
        fin: RnInteractionDecider.Final,
        down: RnInteractionDecider.Snapshot,
        /**
 * The class the recogniser observed, carried through unchanged. It
 * is NOT re-derived here from the final shape: an outcome cannot say
 * which gesture happened (a tap and a swipe on the same target produce
 * the same outcome), so a second derivation would be a guess wearing
 * the authority of a measurement.
 */
        kind: com.tracescout.rn.InteractionKind,
        /**
 * The CURRENT session identity, injected exactly as the network host
 * receives it. It must never CREATE one: an interaction is not a
 * reason to start a session, and a producer that could would attribute
 * a tap to a session the user never began.
 */
        identityOf: () -> RnEnvelope.Identity?,
        /**
 * The CURRENT WebView carrier for this session, or null — injected
 * exactly as [identityOf] is, and read at EMIT time for the same
 * reason: a correlation is retired by a navigation or a revoked
 * policy, so a value read once would keep riding taps after the
 * segment was dropped.
 *
 * DEFAULTED so the decision stays the host's. A tap on a native
 * screen supplies none and the row is UNATTRIBUTED — which the server
 * stores as a third state distinct from "native", so an
 * absent carrier is never laundered into a WebView claim.
 */
        webViewRef: () -> RnEnvelope.WebViewRef? = { null },
    ) {
        if (fin.outcome == InteractionPolicy.Outcome.SUPPRESS) return
        val positional = fin.outcome == InteractionPolicy.Outcome.EMIT_POSITIONAL
        val identity = identityOf() ?: return

        RnTransport.enqueue(activity) { seq ->
            RnEnvelope.interaction(
                id = identity,
                eventId = UUID.randomUUID().toString(),
                sequence = seq,
                timestampMillis = System.currentTimeMillis(),
                // From ACTION_DOWN: where the user aimed and when.
                monotonicNanos = down.downMonotonicNanos,
                // The interaction envelope's posture projection. The eight
                // posture fields belong to hierarchy capture; an interaction
                // carries the two the schema requires.
                posture = RnEnvelope.Posture(allowedViewCount = 0, screenNeverRecord = false),
                screenId = down.screenId,
                gestureId = down.gestureId,
                // The POSITION channel, not the text channel.
                onRedactedTarget = !positional,
                replayX = if (positional) fin.replayX else null,
                replayY = if (positional) fin.replayY else null,
                pointerCount = if (positional) 1 else null,
                kind = kind,
                // Read INSIDE the builder, so the carrier is the one live when
                // the sequence is issued and the bytes are appended — not the
                // one live when the gesture was first observed.
                webView = webViewRef(),
            ).toByteArray(Charsets.UTF_8)
        }
    }
}
