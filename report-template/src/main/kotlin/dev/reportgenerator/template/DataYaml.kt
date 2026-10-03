package dev.reportgenerator.template

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeParseException

// Plain nested YAML map -> DataContext, the schema inferred from the file. Roots `doc` / `item` (and
// `page`, whose number / total are overridden by the engine). Scalar types by shape:
//   123 -> Integer, 1.5 -> Decimal, 2026-01-31 -> Date, true|false -> Boolean, anything else -> String.
// A tag forces the type: `!str "007"`, `!int`, `!decimal`, `!date`, `!bool`. `~` declares a String field
// without a value. A mapping is a Record, a sequence of mappings is a List of records (scalar lists are
// not supported yet). Error paths look like `doc.mass`.
// The `item` root may also be a sequence of mappings: the rows of the flow table (DataFile.items); the
// schema of the `item` root is then the union of the rows' fields.
class DataFile(val context: DataContext, val items: List<DataValue.Record>)

object DataYaml {
    fun parse(yaml: String): DataContext = parseFile(yaml).context

    fun parseFile(yaml: String): DataFile {
        val root = try {
            Yaml.default.parseToYamlNode(yaml)
        } catch (e: YamlException) {
            throw TemplateException("", "invalid YAML: ${e.message} (line ${e.line}, column ${e.column})")
        }
        val map = (root.unwrap() as? YamlMap) ?: throw TemplateException("", "data file must be a mapping with roots ${DATA_ROOTS.joinToString()}")
        val types = LinkedHashMap<String, DataType.Record>()
        val values = LinkedHashMap<String, DataValue.Record>()
        var items = emptyList<DataValue.Record>()
        for ((k, v) in map.entries) {
            val name = k.content
            if (name !in DATA_ROOTS) throw TemplateException(name, "unknown root '$name' (${DATA_ROOTS.joinToString()}) (line ${k.location.line})")
            if (name == "item" && v.unwrap() is YamlList) {
                val (type, value) = list(v.unwrap() as YamlList, name)
                types[name] = (type as DataType.ListOf).of
                items = (value as DataValue.ListOf).items
                continue
            }
            val (type, value) = record(v, name)
            types[name] = type
            values[name] = value
        }
        return DataFile(MapDataContext(DataSchema(types), values), items)
    }

    fun load(path: Path): DataContext = parse(Files.readString(path))

    fun loadFile(path: Path): DataFile = parseFile(Files.readString(path))

    private fun record(node: YamlNode, path: String): Pair<DataType.Record, DataValue.Record> {
        val map = (node.unwrap() as? YamlMap) ?: fail(path, node, "expected a mapping")
        val types = LinkedHashMap<String, DataType>()
        val values = LinkedHashMap<String, DataValue>()
        for ((k, v) in map.entries) {
            val (type, value) = field(v, "$path.${k.content}")
            types[k.content] = type
            if (value != null) values[k.content] = value
        }
        return DataType.Record(types) to DataValue.Record(values)
    }

    private fun field(node: YamlNode, path: String): Pair<DataType, DataValue?> {
        val inner = node.unwrap()
        return when (inner) {
            is YamlNull -> DataType.Str to null
            is YamlMap -> record(node, path)
            is YamlList -> list(inner, path)
            is YamlScalar -> scalar(inner, (node as? YamlTaggedNode)?.tag, path)
            else -> fail(path, node, "unsupported value")
        }
    }

    private fun list(node: YamlList, path: String): Pair<DataType, DataValue> {
        val rows = node.items.mapIndexed { i, n ->
            if (n.unwrap() !is YamlMap) fail("$path[$i]", n, "list items must be mappings (lists of scalars are not supported)")
            record(n, "$path[$i]")
        }
        val schema = DataType.Record(rows.fold(emptyMap<String, DataType>()) { acc, r -> r.first.fields + acc })
        return DataType.ListOf(schema) to DataValue.ListOf(rows.map { it.second }, schema)
    }

    private fun scalar(node: YamlScalar, tag: String?, path: String): Pair<DataType, DataValue> {
        val text = node.content
        val value: DataValue = try {
            when (tag?.trimStart('!')) {
                null -> infer(text)
                "str", "string" -> DataValue.of(text)
                "int", "integer" -> DataValue.of(text.toLong())
                "decimal" -> DataValue.of(BigDecimal(text))
                "date" -> DataValue.of(LocalDate.parse(text))
                "bool", "boolean" -> DataValue.of(parseBool(text) ?: fail(path, node, "expected true|false"))
                else -> fail(path, node, "unknown tag '$tag' (!str !int !decimal !date !bool)")
            }
        } catch (e: NumberFormatException) {
            fail(path, node, "'$text' is not a valid ${tag?.trimStart('!')}")
        } catch (e: DateTimeParseException) {
            fail(path, node, "'$text' is not an ISO date (yyyy-MM-dd)")
        }
        return value.type to value
    }

    private val INTEGER = Regex("-?\\d+")
    private val DECIMAL = Regex("-?\\d+\\.\\d+")
    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

    private fun infer(text: String): DataValue = when {
        INTEGER.matches(text) -> text.toLongOrNull()?.let { DataValue.of(it) } ?: DataValue.of(BigDecimal(text))
        DECIMAL.matches(text) -> DataValue.of(BigDecimal(text))
        DATE.matches(text) -> runCatching { LocalDate.parse(text) }.getOrNull()?.let { DataValue.of(it) } ?: DataValue.of(text)
        else -> parseBool(text)?.let { DataValue.of(it) } ?: DataValue.of(text)
    }

    private fun parseBool(text: String): Boolean? = when (text.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun YamlNode.unwrap(): YamlNode = if (this is YamlTaggedNode) innerNode else this

    private fun fail(path: String, node: YamlNode, message: String): Nothing =
        throw TemplateException(path, "$message (line ${node.location.line})")
}
