# Статус разработки

47 тестов, 10 модулей, всё зелёное. История коммитов ниже — от старого к
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
| — | Архитектура 0.2: `FrameSpec`/`StampSpec` как §34 | `docs` | `736001a` |
| — | `FrameSpec`/`StampSpec` реализован: основная надпись + доп. графы, два прохода `renderPages()`, фикс углов ячеек | `report-ir`, `report-layout`, `reports/specification` | `6550853` |
| — | Вики по `FrameSpec`/`StampSpec` | `docs` | `08f9f17` |
| — | Подписи "Копировал"/"Формат" под рамкой, `BorderWeight.NONE` | `report-ir`, `report-layout`, `reports/specification` | `da88960` |
| 7 | Форматирование таблицы спецификации: фиксированная высота 8мм, 4 блока (СЕ/Детали/Стандарты/Материалы), заголовки блоков курсивные+подчёркнутые 3.5мм, тонкие границы на каждой ячейке, синтетический italic (shear в PDF / font-style в SVG), word-wrap в физические строки, дозаполнение страницы пустыми строками | все модули, особенно `report-layout`, рендереры | `adee345` |

## Loodsman API интеграция (WIP)

**Статус:** В разработке (feature/service). Server partially working.

- `report-loodsman`: клиент Loodsman API v4, преобразование спецификации в DTO
- `report-server`: REST server (Ktor) обслуживает `/specifications/{versionId}`
- **Проблемы/особенности Loodsman API:**
  - Старые versions используют поля `idLink`/`idChild`/`idType` (не camelCase)
  - Требует cookie jar + `web-loodsman-session` header (не Authorization)
  - Batch attribute endpoints не работают — использовать single-object endpoints
  - Количество из `minQuantity`/`maxQuantity`, не из text attributes

Подробная документация — [../memory/loodsman-integration.md](../../.claude/memory/loodsman-integration.md).

## Что сознательно НЕ начато

Фаза 7 (XLSX) — отложена по явному решению пользователя.
Подробности — [known-gaps.md](known-gaps.md).

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
- маленький квадратный вырез на внешних углах ячеек рамки/штампа — не
  виден на превью 1:1, найден покадровым кропом PDF через `pdftoppm` в
  высоком DPI (см. [eskd-title-block.md](eskd-title-block.md))

Golden-снапшоты (SVG-текст, PNG-растеризация PDF) используются как
регрессионный барьер — самозагружаются при первом прогоне, сравниваются
байт-в-байт при последующих.
