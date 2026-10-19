package dev.reportgenerator.domain

sealed interface PdmType {
    data object Detail : PdmType
    data object Assembly : PdmType
    data object StandardProduct : PdmType
    data object OtherProduct : PdmType
    data class Unused(val value: String) : PdmType
}

fun pdmType(dtoValue: String?): PdmType =
    dtoValue?.let {
        when (dtoValue) {
            "Деталь" -> PdmType.Detail
            "Сборочная единица" -> PdmType.Assembly
            "Стандартное изделие" -> PdmType.StandardProduct
            "Прочее изделие" -> PdmType.OtherProduct
            else -> PdmType.Unused(dtoValue)
        }
    } ?: throw IllegalArgumentException("type cannot be null")


