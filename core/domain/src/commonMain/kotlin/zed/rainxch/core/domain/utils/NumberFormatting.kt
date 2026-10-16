package zed.rainxch.core.domain.utils

import kotlin.math.abs
import kotlin.math.roundToLong

fun Double.formatFixed(decimals: Int): String {
    require(decimals in 0..9)
    if (isNaN() || isInfinite()) return toString()

    var factor = 1L
    repeat(decimals) { factor *= 10 }

    val scaled = (abs(this) * factor).roundToLong()
    val integerPart = scaled / factor
    val fraction = scaled % factor
    val sign = if (this < 0 && scaled != 0L) "-" else ""

    return if (decimals == 0) {
        "$sign$integerPart"
    } else {
        "$sign$integerPart.${fraction.toString().padStart(decimals, '0')}"
    }
}

fun Int.padStartZero(width: Int): String = toString().padStart(width, '0')
