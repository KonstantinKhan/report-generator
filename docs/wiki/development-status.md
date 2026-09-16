# Статус разработки

39 тестов, 10 модулей, всё зелёное. История коммитов ниже — от старого к
новому (`git log --oneline`, реверс порядка вывода).

## Фазы (по `../dev-plan-0.1.md`)

| Фаза | Что | Модули | Коммит |
|---|---|---|---|
| 1 | Foundation: geometry, Semantic/Layout IR skeleton, минимальный DSL | `report-geometry`, `report-ir`, `report-layout-ir` | `9c925b3`, `1f81ff8` |
| — | Рефакторинг: `PageFormat` → data class, `TextMeasurement.lines` | `report-geometry`, `report-layout-ir`, `report-layout` | `91df9c9` |
| 2 | Text measurement: `FontRegistry`, `PdfBoxTextMeasurer` | `report-layout` | `3191675` |
| 3 | Layout Engine MVP: пагинация, `keepWithNext`/`keepTogether`, overflow hard-error | `report-layout` | `2900795` |
| 4 | SVG renderer (debug-first) + golden snapshot | `report-render-svg` | `b601519` |
| 5 | PDF renderer через PDFBox, Y-flip, font registry по id | `report-render-pdf` | `38c14e3` |
| 6 | End-to-end на mock-данных: `report-api`/`report-data`/`reports/specification` | `report-api`, `report-data`, `reports/specification` | `c94b574` |
| — | `report-cli` — раннер полного пайплайна | `report-cli` | `375a8a6` |
| — | Фикс: `fontSizePt` был реально `fontSizeMm` (баг единиц измерения) | `report-ir`, `report-layout` | `15839e6` |
| — | ЕСКД-заголовок: вертикальный текст, `manualLines`, фиксированная высота | `report-ir`, `report-layout-ir`, `report-layout`, рендереры, `reports/specification` | `0d409db` |
| — | Внешний вид заголовка: шрифт GOST B, центрирование, рамки ячеек | те же | `c5d00ba` |
| — | Фикс: съезжал повёрнутый текст (баг анкора при центрировании) | рендереры | `fcada34` |
| — | Реальный шрифт GOST Type B (ASCON, лицензия подтверждена) | `report-cli` | `aa494d2` |
| — | Само-ревью: убраны 2 найденных хардкода | `report-ir`, `report-layout-ir`, рендереры, `report-cli` | `2a77830` |

## Что сознательно НЕ начато

Фаза 7 (XLSX) — отложена по явному решению пользователя. Реальная
интеграция с PDM — ждёт появления реального API. Подробности —
[known-gaps.md](known-gaps.md).

## Как проверялась каждая фаза

Каждая фаза проверялась не только тестами, но и **визуально** — реальный
PDF генерировался через `report-cli` и читался глазами (через `Read` на
сгенерированный `.pdf`), особенно там, где математика (повороты текста,
Y-flip координат PDF) не поддаётся проверке одним чтением кода. Несколько
реальных багов найдены именно так, не тестами:

- горизонтальный overflow колонок за пределы страницы (фаза 6, фикстура)
- съезжающий влево повёрнутый текст (анкор центрирования, после ЕСКД-заголовка)
- баг единиц измерения `fontSizePt`/`fontSizeMm` (замечен пользователем на
  глаз — "мелкий текст")

Golden-снапшоты (SVG-текст, PNG-растеризация PDF) используются как
регрессионный барьер — самозагружаются при первом прогоне, сравниваются
байт-в-байт при последующих.
