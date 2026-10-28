package com.tracescout.rn

/**
 * THE CLOSED SET OF PROTOCOL INTERACTION KINDS THIS PRODUCER MAY NAME.
 *
 * `mobile_interaction.schema.json` declares SEVENTEEN values. The frozen v1
 * producer policy (`mobile-protocol/src/interaction-policy.cjs` and its Kotlin
 * twin [InteractionPolicy]) admits exactly TWO single-pointer gesture classes —
 * a tap, or a swipe — and says why in writing:
 *
 * `scroll` / `pan` / `fling` REMAIN PROTOCOL VALUES THIS PRODUCER DOES
 * NOT CLAIM. The host UI moving is not evidence of which gesture the
 * finger performed, and inferring one from the other would be naming a
 * thing we did not measure.
 *
 * `pinch` and `rotate` are refused one layer further down, structurally: the
 * policy requires `pointerCount == 1`, so a multi-pointer class is not
 * expressible at all. `text_input` and the keyboard events cut across the
 * charter's text-entry prohibition, under which capture STOPS while any
 * editable control holds focus.
 *
 * THIS TYPE EXISTS SO "DO NOT INVENT A NEW PROTOCOL KIND" IS STRUCTURAL
 * RATHER THAN A PROMISE. The wire value used to be a bare `"tap"` literal
 * inside the envelope builder — a string, which any later edit could widen to
 * any of the seventeen (or to a value the schema does not declare at all)
 * without anything refusing. There is now no way to reach the `interaction`
 * field except through one of these two constants, so widening the vocabulary
 * requires editing THIS file, where the reason each exclusion exists is written
 * down next to the decision.
 *
 * Adding a value here is a frozen-contract amendment: it needs the policy, the
 * shared parity vectors and the server's own expectations moved together, not a
 * one-line enum addition.
 */
enum class InteractionKind(val wire: String) {
    TAP("tap"),
    SWIPE("swipe"),
}
