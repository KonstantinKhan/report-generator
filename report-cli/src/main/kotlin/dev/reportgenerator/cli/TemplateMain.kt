package dev.reportgenerator.cli

import dev.reportgenerator.layout.DefaultFontRegistry
import dev.reportgenerator.layout.PdfBoxTextMeasurer
import dev.reportgenerator.layout.layOutTemplate
import dev.reportgenerator.layoutir.LaidOutDocument
import dev.reportgenerator.renderpdf.renderToPdf
import dev.reportgenerator.rendersvg.render
import dev.reportgenerator.template.DataContext
import dev.reportgenerator.template.DataYaml
import dev.reportgenerator.template.PageKind
import dev.reportgenerator.template.Template
import dev.reportgenerator.template.TemplateContract
import dev.reportgenerator.template.TemplateException
import dev.reportgenerator.template.TemplateLoader
import dev.reportgenerator.template.TemplateResolver
import dev.reportgenerator.template.dataContext
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
            TemplateLoader.load(templateFile.toPath())
        } else {
            TemplateLoader.load(
                requireNotNull(object {}.javaClass.getResourceAsStream(DEFAULT_TEMPLATE)) { "resource missing: $DEFAULT_TEMPLATE" }
                    .readBytes().toString(Charsets.UTF_8)
            )
        }
        // --- Данные: из файла (типы по виду значений) или демо ---
        val data = if (dataFile != null) DataYaml.load(dataFile.toPath()) else DEMO_DATA
        renderTemplate(template, data, pages, outputDir).forEach { println("wrote ${it.absolutePath}") }
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
internal fun renderTemplate(template: Template, data: DataContext, pages: Int, outputDir: File): List<File> {
    TemplateContract.require(template, data.schema)
    outputDir.mkdirs()

    // --- Шрифты и измеритель текста: нужны, чтобы центрировать и выравнивать текст внутри ячеек ---
    val fonts = DefaultFontRegistry.load()
    val textMeasurer = PdfBoxTextMeasurer(fonts.registry, fonts::resolve)

    // --- Раскладка: для каждой страницы шаблон разрешается в абсолютные координаты, затем превращается в Layout IR ---
    val laidOut = LaidOutDocument(
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

// Печатает сообщение в stderr и завершает процесс с кодом 1. Nothing: компилятор знает, что дальше код не идёт.
private fun fail(message: String): Nothing {
    System.err.println("error: $message")
    exitProcess(1)
}
