package dev.reportgenerator.geometry

@JvmInline
value class Length private constructor(val raw: Long) : Comparable<Length> {

    fun toMillimeters(): Double = raw / 100.0

    operator fun plus(other: Length): Length = Length(raw + other.raw)
    operator fun minus(other: Length): Length = Length(raw - other.raw)
    operator fun unaryMinus(): Length = Length(-raw)
    operator fun times(scalar: Int): Length = Length(raw * scalar)
    operator fun times(scalar: Double): Length = Length(Math.round(raw * scalar))
    operator fun div(scalar: Int): Length = Length(raw / scalar)

    override fun compareTo(other: Length): Int = raw.compareTo(other.raw)

    override fun toString(): String = "${toMillimeters()}mm"

    companion object {
        val ZERO: Length = Length(0L)

        fun ofHundredthsOfMillimeter(raw: Long): Length = Length(raw)

        fun ofMillimeters(mm: Double): Length = Length(Math.round(mm * 100.0))

        fun ofMillimeters(mm: Int): Length = Length(mm.toLong() * 100L)
    }
}

val Int.mm: Length get() = Length.ofMillimeters(this)
val Double.mm: Length get() = Length.ofMillimeters(this)
