package dev.reportgenerator.template

import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.format.DateTimeFormatter
import java.util.Locale

// `format:` of a bound cell / text block (closed set of keys):
//   Decimal: {pattern: "0.##", locale: ru, rounding: HALF_UP}   java.text.DecimalFormat pattern
//   Date:    {pattern: "dd.MM.yyyy", locale: ru}                java.time.DateTimeFormatter pattern
// `locale` picks separators / month names; default ROOT (dot, no localisation). `rounding` default HALF_UP.
data class FormatSpec(
    val pattern: String? = null,
    val locale: String? = null,
    val rounding: RoundingMode? = null
)

// Closed set of locales accepted in `format.locale`.
val FORMAT_LOCALES: Map<String, Locale> = linkedMapOf(
    "ru" to Locale.forLanguageTag("ru"),
    "en" to Locale.forLanguageTag("en"),
    "de" to Locale.forLanguageTag("de")
)

internal val FORMAT_ROUNDINGS: List<RoundingMode> = listOf(
    RoundingMode.HALF_UP, RoundingMode.HALF_EVEN, RoundingMode.HALF_DOWN,
    RoundingMode.UP, RoundingMode.DOWN, RoundingMode.FLOOR, RoundingMode.CEILING
)

object ValueFormatter {
    // Why the format cannot be applied to this type; null = fine. Includes pattern validity.
    fun problem(type: DataType, format: FormatSpec): String? {
        val locale = format.locale?.let { FORMAT_LOCALES[it] ?: return "unknown locale '${it}' (${FORMAT_LOCALES.keys.joinToString("|")})" } ?: Locale.ROOT
        return when (type) {
            DataType.Decimal -> {
                val pattern = format.pattern ?: return "format for Decimal needs 'pattern'"
                runCatching { DecimalFormat(pattern, DecimalFormatSymbols(locale)) }.exceptionOrNull()
                    ?.let { "invalid decimal pattern '$pattern': ${it.message}" }
            }
            DataType.Date -> {
                if (format.rounding != null) return "'rounding' applies to Decimal only"
                val pattern = format.pattern ?: return "format for Date needs 'pattern'"
                runCatching { DateTimeFormatter.ofPattern(pattern, locale) }.exceptionOrNull()
                    ?.let { "invalid date pattern '$pattern': ${it.message}" }
            }
            else -> "format is not supported for ${type.typeName} (only Decimal and Date)"
        }
    }

    // Text of a scalar value. Defaults: String as is, Integer/Decimal plain (BigDecimal.toPlainString, no
    // locale), Date ISO (yyyy-MM-dd), Boolean true/false, Enum its name.
    fun render(value: DataValue, format: FormatSpec? = null): String {
        if (format != null) problem(value.type, format)?.let { error(it) }
        val locale = format?.locale?.let(FORMAT_LOCALES::getValue) ?: Locale.ROOT
        return when (value) {
            is DataValue.Str -> value.value
            is DataValue.Integer -> value.value.toString()
            is DataValue.Decimal -> if (format == null) value.value.toPlainString() else
                DecimalFormat(format.pattern, DecimalFormatSymbols(locale)).apply {
                    roundingMode = format.rounding ?: RoundingMode.HALF_UP
                }.format(value.value)
            is DataValue.Date -> if (format == null) value.value.toString() else
                DateTimeFormatter.ofPattern(format.pattern!!, locale).format(value.value)
            is DataValue.Bool -> value.value.toString()
            is DataValue.Enum -> value.name
            is DataValue.ListOf, is DataValue.Record -> error("cannot render a ${value.type.typeName} as text")
        }
    }
}
