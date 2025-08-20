package org.usvm.ps.weighters

private fun Long.stableToInt(): Int {
    return when {
        this < Int.MIN_VALUE -> Int.MIN_VALUE
        this > Int.MAX_VALUE -> Int.MAX_VALUE
        else -> this.toInt()
    }
}

fun Int.stableAdd(other: Int): Int {
    val longResult = toLong() + other
    return longResult.stableToInt()
}

fun Int.stableMul(other: Int): Int {
    val longResult = toLong() * other
    return longResult.stableToInt()
}

fun Int.stableMul(other: Float): Int {
    val longResult = (this.toDouble() * other).toLong()
    return longResult.stableToInt()
}

fun Int.stableUnaryMinus(other: Int): Int {
    return if (this == Int.MIN_VALUE) Int.MAX_VALUE else -this
}
