package dev.reportgenerator.cli

import dev.reportgenerator.geometry.Insets
import dev.reportgenerator.geometry.mm
import dev.reportgenerator.ir.FlowTables
import dev.reportgenerator.ir.IrDocument
import dev.reportgenerator.ir.PageSetup
import dev.reportgenerator.ir.Styles
import dev.reportgenerator.layout.DefaultFontRegistry
import dev.reportgenerator.layout.PdfBoxTextMeasurer
import dev.reportgenerator.layout.layOut
import dev.reportgenerator.layout.layOutTemplate
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.renderpdf.renderToPdf
import dev.reportgenerator.rendersvg.render
import dev.reportgenerator.template.DataContext
import dev.reportgenerator.template.DataFile
import dev.reportgenerator.template.DataValue
import dev.reportgenerator.template.DataYaml
import dev.reportgenerator.template.FlowBlock
import dev.reportgenerator.template.FlowTableSpec
import dev.reportgenerator.template.PageKind
import dev.reportgenerator.template.Template
import dev.reportgenerator.template.TemplateContract
import dev.reportgenerator.template.TemplateException
import dev.reportgenerator.template.TemplateLoader
import dev.reportgenerator.template.TemplateResolver
import dev.reportgenerator.template.dataContext
import dev.reportgenerator.template.declaredEnumValues
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.system.exitProcess

// Шаблон по умолчанию: ресурс из report-cli/src/main/resources/templates. Используется, если путь к YAML не передан.
private const val DEFAULT_TEMPLATE = "/templates/sheet-frame.yaml"

// Данные по умолчанию для bind-ов вида ${doc.designation}. Здесь нет реальных данных (Loodsman), поэтому
// схема и значения заглушки; свои данные передаются через --data file.yaml. Поле approvedBy объявлено в схеме
// без значения: bind на него должен быть `optional: true`, тогда выводится пустая строка.
private val DEMO_DATA: DataContext = dataContext {
    doc {
        string("designation", "AAA.00.000")
        string("name", "Тестовое изделие")
        decimal("mass", BigDecimal("12.5"))
        date("issued", LocalDate.of(2026, 1, 15))
        string("approvedBy")
    }
}

// Запуск: TemplateMain [template.yaml] [outputDir] [--pages N] [--data data.yaml]
// Только шаблон, без данных документа: страница 1 строится как FIRST, остальные как REST (поле `when` в YAML).
// Bind-ы берутся из --data (схема выводится из файла) или из DEMO_DATA, плюс page.number и page.total.
// Перед раскладкой проверяется контракт шаблона с данными (TemplateContract): неизвестный путь, не скаляр или
// неподходящий format это ошибка `путь: сообщение`, код выхода 1.
fun main(args: Array<String>) {
    // --- Разбор аргументов ---
    // Позиция флага --pages (-1, если его нет).
    val pagesIdx = args.indexOf("--pages")
    // Число страниц: значение после --pages (должно быть числом >= 1), иначе 1.
    val pages = if (pagesIdx >= 0) args.getOrNull(pagesIdx + 1)?.toIntOrNull()?.takeIf { it >= 1 } ?: fail("--pages expects a number >= 1") else 1
    // Позиция флага --data и файл данных после него (null, если флага нет).
    val dataIdx = args.indexOf("--data")
    val dataFile = if (dataIdx >= 0) File(args.getOrNull(dataIdx + 1) ?: fail("--data expects a file path")) else null
    // Обычные аргументы без флагов и их значений: [0] путь к YAML, [1] папка вывода.
    val flagIdx = setOfNotNull(pagesIdx, dataIdx).filter { it >= 0 }.flatMap { listOf(it, it + 1) }.toSet()
    val positional = args.filterIndexed { i, _ -> i !in flagIdx }
    // Файл шаблона; null, если не указан, тогда берётся встроенный DEFAULT_TEMPLATE.
    val templateFile = positional.getOrNull(0)?.let(::File)
    // Папка для результата. Путь относительный: считается от рабочей папки процесса (report-cli/ для runTemplate).
    val outputDir = File(positional.getOrElse(1) { "output" })

    try {
        // --- Загрузка шаблона: разбор YAML и валидация (ошибки: TemplateException с путями вида blocks[2].attach.to) ---
        val template = if (templateFile != null) {
            TemplateLoader.load(templateFile.toPath(), Styles.named.keys)
        } else {
            TemplateLoader.load(
                requireNotNull(object {}.javaClass.getResourceAsStream(DEFAULT_TEMPLATE)) { "resource missing: $DEFAULT_TEMPLATE" }
                    .readBytes().toString(Charsets.UTF_8),
                Styles.named.keys
            )
        }
        // --- Данные: из файла (типы по виду значений) или демо ---
        // Поля item, по которым шаблон группирует (groupBy.field), читаются как enum: домен задаёт сам шаблон
        // (order / omit), поэтому в файле данных теги не нужны.
        val itemEnums = template.blocks.filterIsInstance<FlowBlock>().firstNotNullOfOrNull { it.table }
            ?.let { t -> t.groupBy?.let { g -> mapOf(g.field to t.declaredEnumValues().getValue(g.field)) } } ?: emptyMap()
        val data = if (dataFile != null) DataYaml.loadFile(dataFile.toPath(), itemEnums) else DataFile(DEMO_DATA, emptyList())
        renderTemplate(template, data.context, pages, outputDir, data.items).forEach { println("wrote ${it.absolutePath}") }
    } catch (e: TemplateException) {
        // Ошибка в YAML: печатаем каждую как "путь: сообщение" и выходим с кодом 1.
        System.err.println("template error:")
        e.errors.forEach { System.err.println("  $it") }
        exitProcess(1)
    } catch (e: java.nio.file.NoSuchFileException) {
        // Указанный файл шаблона не существует.
        fail("template file not found: ${e.file}")
    } catch (e: IllegalStateException) {
        // Нет значения для обязательного bind-а (путь есть в схеме, а значения в данных нет).
        fail(e.message ?: e.toString())
    } catch (e: IllegalArgumentException) {
        // Прочие ошибки аргументов или содержимого (например, неподдерживаемый поворот текста).
        fail(e.message ?: e.toString())
    }
}

// Контракт, раскладка страниц и запись SVG (по файлу на страницу) и PDF. Возвращает записанные файлы.
// Контракт проверяется до раскладки: TemplateException со всеми ошибками.
// Если в шаблоне есть `flow` с `table:`, строки таблицы берутся из `items` (корень `item:` файла данных),
// число страниц определяет пагинация настоящего движка, а `pages` игнорируется.
internal fun renderTemplate(template: Template, data: DataContext, pages: Int, outputDir: File, items: List<DataValue.Record> = emptyList()): List<File> {
    TemplateContract.require(template, data.schema)
    outputDir.mkdirs()

    // --- Шрифты и измеритель текста: нужны, чтобы центрировать и выравнивать текст внутри ячеек ---
    val fonts = DefaultFontRegistry.load()
    val textMeasurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    // --- Раскладка: для каждой страницы шаблон разрешается в абсолютные координаты, затем превращается в Layout IR ---
    val flowIndex = template.blocks.indexOfFirst { it is FlowBlock && it.table != null }
    val laidOut = if (flowIndex >= 0) {
        layOutWithFlow(template, (template.blocks[flowIndex] as FlowBlock).table!!, "blocks[$flowIndex].table", data, items, textMeasurer, fonts)
    } else LaidOutDocument(
        (1..pages).map { number ->
            // Страница 1 видит блоки с when: first (и all), остальные видят when: rest (и all).
            val kind = if (number == 1) PageKind.FIRST else PageKind.REST
            // resolve: считает координаты всех блоков по привязкам (attach). layOutTemplate: рисует их примитивами
            // (прямоугольники, линии, текст), которые понимают оба рендерера; page.number и page.total добавляет сам.
            layOutTemplate(TemplateResolver.resolve(template, kind), data, textMeasurer, fonts::resolve, number, pages)
        }
    )

    // --- Вывод ---
    // Имя файлов берётся из поля name: в YAML (если пусто: "template").
    val name = template.name.ifEmpty { "template" }
    // SVG: по одному файлу на страницу, <name>-page-N.svg.
    val written = render(laidOut).mapIndexed { index, svg ->
        File(outputDir, "$name-page-${index + 1}.svg").also { it.writeText(svg) }
    }
    // PDF: один файл на весь документ, <name>.pdf.
    val pdf = File(outputDir, "$name.pdf")
    pdf.writeBytes(renderToPdf(laidOut, fonts.registry))
    return written + pdf
}

// Шаблон с таблицей потока: таблица (IrTable из YAML-описания и строк данных) идёт через настоящий движок
// (измерение, перенос, пагинация, заполнение пустыми строками), блоки шаблона (рамка, надписи, таблицы) дорисовываются
// на каждую получившуюся страницу, page.total известен после пагинации. Блоки шаблона в раскладке движка только
// резервируют место (reserves), рисует их layOutTemplate; блоки с id слотов (stamp, leftMargin, ...) движок
// считает своими и здесь не учитывает, их в таком шаблоне лучше не называть. Рамку листа движок рисует сам
// (по полям листа), рамка из шаблона ляжет поверх неё.
private fun layOutWithFlow(
    template: Template,
    spec: FlowTableSpec,
    specPath: String,
    data: DataContext,
    items: List<DataValue.Record>,
    textMeasurer: PdfBoxTextMeasurer,
    fonts: DefaultFontRegistry
): LaidOutDocument {
    val first = TemplateResolver.resolve(template, PageKind.FIRST)
    val m = template.sheet.margins
    val setup = PageSetup(
        format = first.sheet.format,
        margins = Insets(m.top.mm, m.right.mm, m.bottom.mm, m.left.mm),
        dataContext = data,
        staticTemplate = template
    )
    val table = FlowTables.build(spec, data.schema, items, specPath)
    val flowPages = layOut(IrDocument(setup, listOf(table)), textMeasurer, fonts::resolve).pages
    val total = flowPages.size
    return LaidOutDocument(
        flowPages.map { page ->
            val kind = if (page.number == 1) PageKind.FIRST else PageKind.REST
            val chrome = layOutTemplate(TemplateResolver.resolve(template, kind), data, textMeasurer, fonts::resolve, page.number, total)
            page.copy(elements = page.elements + chrome.elements)
        }
    )
}

// Печатает сообщение в stderr и завершает процесс с кодом 1. Nothing: компилятор знает, что дальше код не идёт.
private fun fail(message: String): Nothing {
    System.err.println("error: $message")
    exitProcess(1)
}
