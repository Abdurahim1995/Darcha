package com.tikoncha.darcha.feature.viewer.ui

import kotlin.math.pow

/**
 * How far the fingers have to travel to move the zoom (T35).
 *
 * ## Why this exists at all
 *
 * The pinch arithmetic was never wrong. The gesture loop reports the
 * **incremental** spread ratio `spread / lastSpread` and the reducer multiplies
 * the current zoom by it, so a whole pinch telescopes to `dEnd / dStart` — the
 * ratio of the finger distances, and nothing compounds.
 *
 * That 1:1 mapping is what every photo viewer uses, and it works there because
 * the zoom range is 20x or more. Here the range is **0.5..3.0** — only 3x
 * upward from rest — while a hand on a 6 cm-wide phone comfortably produces a
 * finger-distance ratio of 4x to 6x in one spread. So a single ordinary gesture
 * ran the whole range and there was no way to stop in between: not a bug in the
 * maths, a mismatch between the gesture's dynamic range and the zoom's.
 *
 * ## Why an exponent, and not a damping factor on the delta
 *
 * Raising each increment to a power **telescopes**, which nothing else here
 * does:
 *
 * ```
 * ∏ (dᵢ / dᵢ₋₁)^γ  =  (∏ dᵢ / dᵢ₋₁)^γ  =  (dEnd / dStart)^γ
 * ```
 *
 * The zoom therefore depends only on where the fingers **started and stopped**,
 * never on how many pointer events arrived along the way. That matters
 * concretely: this project is tested on a 60 Hz A31 and a 120 Hz HONOR, and the
 * obvious alternative — `1 + (ratio - 1) * k` — is a *sum* in disguise, so the
 * same gesture would zoom about twice as far on the faster device. It also keeps
 * the gesture reversible, since `r^γ` and `(1/r)^γ` are still exact inverses:
 * pinch in and back out and you land on the zoom you started from.
 *
 * ## Why 0.5
 *
 * `γ = 0.5` is a square root, which states itself: **to double the zoom, spread
 * your fingers four times as far.** Anchored to the device the complaint was
 * measured on (520 dpi, so 1 cm ≈ 205 px):
 *
 * | | finger ratio needed | on a 6 cm-wide screen |
 * |---|---|---|
 * | one useful step, 1.0 → 1.5 | 2.25x | ~2 cm → 4.5 cm, a casual pinch |
 * | 1.0 → 2.0 | 4x | ~1.5 cm → 6 cm, a deliberate one |
 * | the whole range, 1.0 → 3.0 | 9x | past one gesture; takes two |
 *
 * The gesture behind the report — a measured 2.7x in half a second, which pinned
 * the zoom at the 3.0 ceiling — now lands at `√2.7 ≈ 1.64x`, about 24 rows down
 * to 15. Reaching an extreme in two pinches is normal; being unable to stop
 * anywhere in the middle was not.
 *
 * Neighbouring values were considered rather than assumed: `0.7` still crosses
 * the full range in one spread (5.7x) and leaves the same complaint in weaker
 * form, and `0.4` puts the ceiling at a 15.6x spread, which no hand reaches.
 *
 * **This is the one number to turn.** Lower is slower.
 */
internal const val PINCH_GAIN: Float = 0.5f

/**
 * One pinch event's raw spread ratio, damped by [PINCH_GAIN].
 *
 * Applied in the gesture loop rather than in the reducer, deliberately. The
 * reducer's contract is "multiply the zoom by this scale", and double-tap reset
 * relies on it: that animation computes its own scales and feeds them in as
 * ordinary `Zoom` intents, so damping there would bend the animation instead of
 * the gesture. How far a finger travels for a given zoom is a property of the
 * input device, not of the document.
 */
internal fun dampedPinchScale(rawRatio: Float): Float =
    if (rawRatio <= 0f) 1f else rawRatio.pow(PINCH_GAIN)
