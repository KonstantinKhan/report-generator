# Модули

12 Gradle-модулей, `settings.gradle.kts` — источник истины по списку. См.
[architecture.md](architecture.md) для графа зависимостей целиком.

| Модуль | Пакет | Назначение | Зависит от (main) |
|---|---|---|---|
| `report-geometry` | `dev.reportgenerator.geometry` | `Length` (fixed-point Long, 1/100мм), `Point`, `Size`, `Rect`, `Insets`, `PageFormat`, `Corner`, `placeOrigin`, `resolveAnchor` | — |
| `report-template` | `dev.reportgenerator.template` | Декларативный шаблон страницы: YAML → модель → валидация → абсолютная геометрия (`TemplateLoader`, `TemplateResolver`, `flowRegion`) | `report-geometry` |
| `report-ir` | `dev.reportgenerator.ir` | Semantic IR: `IrDocument`, `IrTable`, `IrGroup`, `IrRow`, `IrCell`, `Styles`, `LayoutConstraints`, DSL (`document{}`), `FrameSpecs` + `gost-spec.yaml` (статические блоки ГОСТ из шаблона) | `report-geometry`, `report-template` |
| `report-layout-ir` | `dev.reportgenerator.layoutir` | Layout IR: `LaidOutDocument`, `Page`, `PositionedText`, `Line`, `Rectangle`, `FontRef`, `TextOrientation`, `BASELINE_RATIO` | `report-geometry` (НЕ `report-ir`) |
| `report-layout` | `dev.reportgenerator.layout` | Layout Engine: `layOut()`, `TextMeasurer`/`PdfBoxTextMeasurer`, `FontRegistry`, пагинация, `LayoutOverflowException`; расстановка и резерв статических блоков из `PageSetup.staticTemplate` | `report-ir`, `report-layout-ir`, PDFBox |
| `report-render-svg` | `dev.reportgenerator.rendersvg` | `render()`/`renderPage()`: Layout IR → SVG-строка | `report-layout-ir` только |
| `report-render-pdf` | `dev.reportgenerator.renderpdf` | `renderToPdf()`: Layout IR → байты PDF через PDFBox | `report-layout-ir`, `report-layout` (только `FontRegistry`) |
| `report-api` | `dev.reportgenerator.api` | DTO (`ItemDto`, `SpecificationDto`), интерфейс `PdmClient`, `InMemoryPdmClient` для тестов | — (контракт, без HTTP-библиотеки) |
| `report-data` | `dev.reportgenerator.data` | `ItemKind`, `SpecificationItem`, `SpecificationData`, `mapToSpecificationData()` | `report-api` |
| `reports/specification` | `dev.reportgenerator.reports.specification` | Первый реальный Report Builder: `specification()` — Report Data → Semantic IR | `report-data`, `report-ir`, `report-geometry` |
| `report-cli` | `dev.reportgenerator.cli` | Раннер: `./gradlew :report-cli:run` — гоняет весь pipeline, пишет `.svg`/`.pdf` на диск | всё вышеперечисленное + Kotlin `application` plugin |
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
