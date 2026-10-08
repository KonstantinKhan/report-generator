# Модули

13 Gradle-модулей (12 верхнего уровня + `reports:specification`), `settings.gradle.kts` — источник истины по списку. См.
[architecture.md](architecture.md) для графа зависимостей целиком.

| Модуль | Пакет | Назначение | Зависит от (main) |
|---|---|---|---|
| `report-geometry` | `dev.reportgenerator.geometry` | `Length` (fixed-point Long, 1/100мм), `Point`, `Size`, `Rect`, `Insets`, `PageFormat`, `Corner`, `placeOrigin`, `resolveAnchor` | — |
| `report-template` | `dev.reportgenerator.template` | Декларативные YAML-шаблоны: модель -> `TemplateLoader` (разбор + валидация, `TemplateValidator`) -> `TemplateResolver` (якоря, `attach`, наборы, поворот, `when`, `PageKind`, `flowRegion`); типизированные данные (`DataType`, `DataSchema`, `DataValue`, `DataContext`, `Binding`, `ValueFormat`, `DataYaml` для `--data`) и контракт шаблона с данными (`TemplateContract`); таблица потока: модель `FlowTableSpec`, правила данных `FlowShaper` (`where` / `sortBy` / `groupBy` / `computed` / `cases` / `totals`), `FlowContract`, `FlowArithmetic`, `FlowTotals`, `TableGrid` (общая сетка блока `table` и шапки `rows`). Справка: [template-yaml.md](template-yaml.md) | `report-geometry` (+ kaml) |
| `report-ir` | `dev.reportgenerator.ir` | Semantic IR: `IrDocument`, `IrTable` (`IrColumn`, `IrTableHeader` + `IrHeaderGrid`, `IrGroup`, `IrRow`, `IrCell`, `IrGroupTitle`, `IrTotalRow`, `IrLineNumbers`, `IrFillRemainder`), `Styles` (закрытый набор `Styles.named`), `LayoutConstraints`, DSL (`document{}`), `PageSetup` (слоты рамок, `staticTemplate`, `dataContext`, `bindStaticSlots`), `FrameSpec`/`FrameCell`/`FrameBindings`, `StaticSlot`; `FrameSpecs` + `GostSpecTemplate` читают `gost-spec.yaml` (статические блоки ГОСТ и таблица потока); `FlowTables.build` (описание таблицы + записи `item` -> `IrTable`) | `report-geometry`, `report-template` |
| `report-layout-ir` | `dev.reportgenerator.layoutir` | Layout IR: `LaidOutDocument`, `Page`, `PositionedText`, `Line`, `Rectangle`, `FontRef`, `TextOrientation`, `BASELINE_RATIO` | `report-geometry` (НЕ `report-ir`) |
| `report-layout` | `dev.reportgenerator.layout` | Layout Engine: `layOut()`, `layOutTemplate()` (рисует блоки шаблона примитивами), `TextMeasurer`/`PdfBoxTextMeasurer`, `FontRegistry`, `DefaultFontRegistry` (PT Sans + GOST Type B из ресурсов модуля), пагинация, дозаполнение страницы (`fill: blank`, `remainder`), нумерация физических строк, `LayoutOverflowException`; расстановка и резерв статических блоков из `PageSetup.staticTemplate` (`StaticBlocks.kt`) | `report-ir`, `report-layout-ir`, PDFBox |
| `report-render-svg` | `dev.reportgenerator.rendersvg` | `render()`/`renderPage()`: Layout IR → SVG-строка | `report-layout-ir` только |
| `report-render-pdf` | `dev.reportgenerator.renderpdf` | `renderToPdf()`: Layout IR → байты PDF через PDFBox | `report-layout-ir`, `report-layout` (только `FontRegistry`) |
| `report-api` | `dev.reportgenerator.api` | DTO (`ItemDto`, `SpecificationDto`), интерфейс `PdmClient`, `InMemoryPdmClient` для тестов | — (контракт, без HTTP-библиотеки; реальная реализация: `report-loodsman`) |
| `report-data` | `dev.reportgenerator.data` | `ItemKind`, `SpecificationItem`, `SpecificationData`, `mapToSpecificationData()` | `report-api` |
| `reports/specification` | `dev.reportgenerator.reports.specification` | Report Builder спецификации: `specification()` = адаптер `SpecificationData.toDataContext()` / `itemRecords()` + `FlowTables.build` по `gost-spec.yaml`; правил таблицы в коде нет. Тесты: golden-эталоны раскладки, parity со старым кодом | `report-data`, `report-ir`, `report-geometry` |
| `report-cli` | `dev.reportgenerator.cli` | Раннеры: `Main.kt` (`./gradlew :report-cli:run`, спецификация на зашитой фикстуре) и `TemplateMain.kt` (`./gradlew :report-cli:runTemplate`, свой YAML + `--data`), пишут `.svg`/`.pdf`; шаблоны `src/main/resources/templates/` (`tutorial/`, `purchased-list.yaml`, `sheet-frame.yaml`, `table-demo.yaml`). См. [running-the-project.md](running-the-project.md) | `reports/specification`, `report-layout`, `report-template`, рендереры, Kotlin `application` plugin |
| `report-loodsman` | `dev.reportgenerator.loodsman` | Реализация `PdmClient` поверх реального Loodsman API v4 (HTTP, авторизация по сессии). См. [loodsman-integration.md](loodsman-integration.md) | `report-api` |
| `report-server` | `dev.reportgenerator.server` | Ktor REST-сервер: `POST /specifications/{versionId}` — тянет данные из Loodsman, рендерит PDF | `report-api`, `report-loodsman`, `report-data`, `reports/specification`, `report-layout`, `report-render-pdf` |

## Тестовые (не main) зависимости стоит знать

- `report-render-svg` и `report-render-pdf` в тестах ДОПОЛНИТЕЛЬНО зависят
  от `report-ir` и `report-layout` — чтобы собрать фикстуру через весь
  реальный pipeline для snapshot-тестов. Это нормально: тестовая
  зависимость не нарушает границу main-кода (см. [architecture.md](architecture.md)).
- `reports/specification` в тестах зависит от `report-layout`,
  `report-render-svg`, `report-render-pdf` — по той же причине
  (end-to-end тест).

## Общие тестовые ресурсы

Каждый модуль, которому нужен реальный шрифт для тестов, хранит свою копию
`PT_Sans-Regular.ttf` (+ `OFL.txt`) под `src/test/resources/fonts/` —
скопирована руками, не через shared-ресурс модуль (сознательно, чтобы не
плодить ещё один Gradle-модуль только для тестовых ассетов). См.
[fonts-and-licensing.md](fonts-and-licensing.md).
