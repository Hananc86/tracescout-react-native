package com.tracescout.mobile.capture

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics

/**
 * The Compose privacy-intent channel.
 *
 * WHY THIS IS A DEDICATED SEMANTICS PROPERTY AND NOT `testTag`
 * -----------------------------------------------------------
 * The obvious design — reuse `Modifier.testTag("ts-block")`, mirroring the
 * Views `view.tag` convention — is WRONG, and the fixture app proved it on a
 * real device before any customer could hit it.
 *
 * `testTag` is a SINGLE-VALUE slot. An app that already tags a composable for
 * its own UI tests (`Modifier.testTag("checkout_total")`) and then adds a
 * privacy marker has to choose: keep its test tag and lose the masking
 * directive, or keep the masking and break its test suite. In Views the two
 * never collide, because a privacy tag and a test id are different mechanisms.
 *
 * A silently-lost masking directive is the worst possible failure mode here:
 * the app author believes a field is blocked, the SDK sees no marker, and the
 * value is captured. So privacy intent gets its own semantics key, which
 * coexists with `testTag` and with any other semantics the app declares.
 *
 * Values are the wire treatment strings from the shared contract —
 * `masked`, `blocked`, `allowed`, `never_record` — so there is one vocabulary
 * across Kotlin, Swift, Dart and the backend.
 */
object ComposePrivacy {

    /** Semantics key carrying host-app privacy intent. Never a test id. */
    val Key = SemanticsPropertyKey<String>("TraceScoutPrivacy")

    private var SemanticsPropertyReceiver.privacyIntent by Key

    /**
 * Declare privacy intent on a composable:
 *
 * ```
 * Text(total, modifier = Modifier.testTag("checkout_total").tsPrivacy(BLOCKED))
 * ```
 *
 * Both survive — that is the entire point.
 *
 * Note this can only ever make a node MORE private in practice: an
 * [ALLOWED] intent is still overridden by a platform-secure declaration,
 * because the policy's precedence 1 is absolute.
 */
    fun Modifier.tsPrivacy(intent: String): Modifier =
        this.semantics { privacyIntent = intent }

    const val MASKED = "masked"
    const val BLOCKED = "blocked"
    const val ALLOWED = "allowed"
    const val NEVER_RECORD = "never_record"
}
