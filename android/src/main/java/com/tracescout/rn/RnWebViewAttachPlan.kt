package com.tracescout.rn

/**
 * G2-ATTACH — WHETHER A BRIDGE MAY BE INSTALLED IN ONE WEB VIEW, AND WITH
 * EXACTLY WHAT.
 *
 * `RnWebViewBinding.decide` answers "may this SESSION correlate at all"
 * (capability → session → origin). This answers the next question, once per
 * web view: given that session's admitted allowlist and the facts the host can
 * read about THIS surface, may a message listener and a document-start script
 * be installed — and if so, with which origin rules and which payload.
 *
 * It is pure. The `androidx.webkit` calls themselves are two lines in the host
 * app; everything that is a decision is here, in a `kotlin("jvm")` module with
 * no Android and no support library, so every refusal branch is provable in an
 * ordinary JVM test.
 *
 * ## THE ORIGIN RULES ARE THE INJECTION-TIME HALF OF THE SAME ALLOWLIST
 *
 * `addWebMessageListener` and `addDocumentStartJavaScript` enforce admission
 * **at injection time**: a frame outside the rules never obtains the object and
 * never runs the script, so an unadmitted third party cannot speak at all. That
 * is strictly stronger than `addJavascriptInterface`, which injects into EVERY
 * frame and leaves the allowlist to be checked after a message has already
 * arrived.
 *
 * So there are now two independent refusals for one unadmitted origin — the
 * browser never delivers it, and [RnWebViewSegment.decide] would refuse it if
 * it somehow arrived. **Neither is redundant.** The injection rule is what
 * makes the guarantee structural; the decision is what keeps it true on the
 * API-26 floor, where neither feature exists. Do not "simplify" either away:
 * removing the rules widens the surface to every frame, and removing the
 * decision trusts the rules on an engine that does not implement them.
 *
 * ## THE RULES ARE A STRICT SUBSET OF THE ADMITTED SET, BY CONSTRUCTION
 *
 * [originRules] is derived from the origins `RnWebViewBinding.Admission.Admit`
 * already carries, which `RnWebViewOriginPolicy.admit` already normalized and
 * accepted. Nothing is widened, nothing is repaired, no wildcard is synthesized
 * and `"*"` is refused outright rather than passed through — a `"*"` rule is a
 * total bypass of the host's declaration, so it is the one value that must be
 * unrepresentable here.
 */
object RnWebViewAttachPlan {

    /**
 * The JS object the admitted frame posts through, and the global the
 * document-start script writes.
 *
 * A DISTINCT NAME FROM THE NATIVE ANDROID SDK'S (`__TS_NATIVE__`). The
 * two producers can be present in one process only by accident, but if they
 * ever are, one bridge silently answering for the other's segment would be
 * a correlation nobody can attribute. Different name, different surface.
 */
    const val BRIDGE_OBJECT = "__tsRnWebViewBridge"

    /** The global the document-start script writes into an ADMITTED world. */
    const val IDENTITY_GLOBAL = "__tsRnNative"

    /** `"*"` as an origin rule is an allow-all and is never produced or accepted. */
    const val RULE_WILDCARD = "*"

    /**
 * The decision.
 *
 * `Install` CARRIES THE VALUES, so the host app has nothing to decide
 * and nothing to assemble. A host that computed its own origin rules, or
 * its own payload, would be a second home for the two facts that define the
 * boundary — and the second home is the one that drifts.
 */
    sealed class Plan {
        data class Install(
            val bridgeObject: String,
            /** Passed verbatim as `allowedOriginRules`. Never empty, never `"*"`. */
            val originRules: Set<String>,
            /** Passed verbatim as the document-start script source. */
            val identityScript: String,
        ) : Plan()

        /** Install nothing. Carries the typed reason, for an observable refusal. */
        data class Refuse(val why: String) : Plan()
    }

    /** A web view surface's facts, as the host app can read them. */
    data class SurfaceFacts(
        /**
 * `webView.settings.javaScriptEnabled` — the FRAMEWORK's own answer,
 * read from `android.webkit`, which needs no support library.
 *
 * IT IS READ, NEVER SET. The RN fixture's help-document route
 * deliberately runs `javaScriptEnabled={false}`, and a producer that
 * turned JS on to make its own handshake possible would have changed
 * the subject it was measuring. The honest answer on that route is a
 * refusal.
 */
        val javaScriptEnabled: Boolean,
    )

    /**
 * Decide one web view's attachment.
 *
 * THE REFUSAL ORDER MIRRORS `RnWebViewBinding.decide`'S — capability,
 * then the session's correlation, then the origin material, then this
 * surface's own facts. One operator, one device, two sites answering the
 * same questions in different orders is two different stories about one
 * fault.
 */
    @JvmStatic
    fun decide(
        capability: RnWebViewSegment.Capability,
        /** The origins the session's bind already admitted. */
        admittedOrigins: List<String>?,
        /** The natively minted segment id this web view would echo. */
        segmentId: String?,
        surface: SurfaceFacts,
    ): Plan {
        // (1) CAPABILITY. the specification: an unsupported engine yields no segment row at
        // all, and the two APIs this attach calls do not exist there. Android's
        // own reference states that invoking a `WebViewCompat` API whose
        // feature check is false is unsafe, so this refusal is not merely
        // tidiness.
        if (capability != RnWebViewSegment.Capability.SUPPORTED) {
            return Plan.Refuse(capability.wire)
        }

        // (2) THE SESSION MUST HAVE SOMETHING TO CORRELATE TO. A bridge with no
        // minted id could never admit a message — `RnWebViewSegment.decide`
        // compares the echo byte-for-byte against it — so installing one would
        // put a listener in a live page that can only ever refuse, silently.
        if (segmentId == null || segmentId.trim().isEmpty()) {
            return Plan.Refuse("segment_id_absent")
        }

        // THE SEGMENT ID IS VALIDATED BEFORE IT IS INTERPOLATED INTO SCRIPT
        // SOURCE. The mint produces unpadded base64url, so today nothing can
        // need escaping — but this is the one place a producer-controlled value
        // becomes executable text in a page, and "today's mint is safe" is not a
        // property of this function. A value outside the safe alphabet is
        // REFUSED rather than escaped: an escape is a repair, and a repair here
        // is a guess about what a hostile value meant.
        //
        // VALIDATED RAW, NEVER TRIMMED — AND MY OWN TEST CAUGHT THE FIRST
        // VERSION DOING THE OPPOSITE. A trim before the alphabet check made
        // `"seg\n"` install as `"seg"`: a silent REPAIR, in the one function
        // whose comment forbids repairs, on the one value that must be exact.
        // Identity here is compared BYTE-FOR-BYTE against the page's echo and
        // is interpolated into script source, so a repaired id is either a
        // guaranteed mismatch or a script carrying a different value than the
        // correlation holds.
        //
        // THIS IS DELIBERATELY THE OPPOSITE CALL FROM
        // `RnWebViewPreflight.majorOf`, WHICH DOES TRIM. That value is parsed
        // to an INTEGER and compared against a floor, so surrounding
        // whitespace cannot change the answer or admit an engine below the
        // floor. Exact for an identity, tolerant for a parsed number — and the
        // asymmetry is stated at both ends rather than discovered.
        if (!isSafeIdentityValue(segmentId)) return Plan.Refuse("segment_id_unsafe")
        // The alphabet excludes every whitespace character, so a value that
        // passed cannot have surrounding whitespace — the id below is the RAW
        // one and no trim is applied anywhere after this point.
        val sid = segmentId

        // (3) THE ORIGIN MATERIAL. `Absent` and `empty` are different facts and
        // keep different names — the same distinction
        // `RnWebViewOriginPolicy.Declared` draws, for the same reason.
        if (admittedOrigins == null) return Plan.Refuse("origin_policy_absent")
        val rules = originRules(admittedOrigins)
        if (rules.isEmpty()) return Plan.Refuse("origin_policy_empty")
        // Unreachable while `RnWebViewOriginPolicy.admit` refuses `*` as an
        // entry — and asserted anyway, because this set is what the browser
        // enforces and a wildcard reaching it is a total bypass of the host's
        // declaration. A guard on the boundary is worth stating twice.
        if (rules.contains(RULE_WILDCARD)) return Plan.Refuse("origin_rule_wildcard")

        // (4) THIS SURFACE'S OWN FACTS. Both APIs deliver into a JavaScript
        // world; with scripting off there is no world to deliver into, so the
        // page can never echo the id and the handshake can never complete.
        // Refusing here names the real fact instead of leaving a listener that
        // waits forever.
        if (!surface.javaScriptEnabled) return Plan.Refuse("js_disabled")

        return Plan.Install(
            bridgeObject = BRIDGE_OBJECT,
            originRules = rules,
            identityScript = identityScript(sid),
        )
    }

    /**
 * The `allowedOriginRules` set for the two `androidx.webkit` calls.
 *
 * DERIVED FROM THE ADMITTED ORIGINS AND NOTHING ELSE. Entries are
 * already normalized (lowercase `scheme://host[:port]`, no path, no
 * wildcard) by `RnWebViewOriginPolicy.admit`, so this is a projection, not
 * a second parser — a second parser is how the injection rules and the
 * admission decision start disagreeing about one allowlist.
 */
    @JvmStatic
    fun originRules(admittedOrigins: List<String>): Set<String> =
        admittedOrigins.filter { it.isNotBlank() && it != RULE_WILDCARD }.toSet()

    /**
 * The document-start script.
 *
 * IT CARRIES THE SEGMENT ID AND NOTHING ELSE — no session id, no project
 * id, no posture, no credential. That is `RnWebViewCorrelation`'s own rule
 * and it is deliberately STRICTER than the native Android fixture's
 * payload, which also carries the project id: a page that knew the native
 * session could claim one, and Model B correlates N and S natively
 * precisely so the page never has to be told N.
 *
 * BUILT BY CONCATENATION, NOT BY A JSON LIBRARY. rn-core has no runtime
 * dependency at all ("the producer's own emission stays dependency-free by
 * construction"), and the value is alphabet-validated by [decide] before it
 * reaches here, so there is nothing to escape. `Object.freeze` is not
 * security — the page can shadow any global — it simply stops an accidental
 * overwrite by the page's own code from looking like a native fault.
 */
    @JvmStatic
    fun identityScript(segmentId: String): String =
        "window.$IDENTITY_GLOBAL=Object.freeze({segmentId:\"$segmentId\"});"

    /**
 * The alphabet a value may use before it is interpolated into script source.
 *
 * Unpadded base64url plus nothing: no quote, no backslash, no angle
 * bracket, no whitespace, no control character can pass.
 */
    @JvmStatic
    fun isSafeIdentityValue(s: String): Boolean =
        s.isNotEmpty() && s.length <= 64 && s.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_'
        }
}
