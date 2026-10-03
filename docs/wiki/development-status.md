# Статус разработки

13 модулей, 368 тестов, всё зелёное (`./gradlew test`, 2026-10-03). История коммитов ниже — от старого к
новому (`git log --oneline`, реверс порядка вывода); ветка `engine` — отдельным разделом в конце таблиц фаз.
Нумерация «фаз» тут своя: в `../dev-plan-0.1.md` фаза 7 это XLSX (не начата), а «фаза 7» в таблице ниже — форматирование таблицы
спецификации.

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

## Ветка `engine`: YAML-шаблоны и таблица потока

Ветка `engine`, 15 коммитов поверх `279b40f` (merge PR #1, 2026-10-01), всё 2026-10-03. Хронология возможностей и
как мигрировать: [changelog-engine.md](changelog-engine.md). Справка: [template-yaml.md](template-yaml.md), практикум:
[template-guide.md](template-guide.md).

| Этап | Что | Коммит |
|---|---|---|
| — | Чистка: удалён устаревший `SpecificationEndToEndTest` (красный, сравнивал с протухшим снапшотом), `reports/` больше не в `.gitignore` | `e588fac` |
| — | Loodsman: сборочные единицы (`Сборочная единица`) попадают в спецификацию (`mapItemKind` -> `ASSEMBLY`) | `e0eda3d` |
| 1 | Модуль `report-template`: YAML -> модель -> валидация -> резолвер (якоря, `attach` по осям, поворот таблиц, наборы, `when`, `reserves`, область потока); `Corner`/`resolveAnchor` в `report-geometry`; статические блоки ГОСТ из `gost-spec.yaml`; типизированный контекст данных (`DataType`/`DataSchema`/`DataContext`), `format`/`optional`, контракт; `FrameField`/`FrameBindings`-поля заменены биндом по пути; CLI `runTemplate`; фиксы таблицы (отступы ячейки, перенос длинных слов, цепочка заголовка группы, единица только у материалов) | `928f245` |
| 2 | Таблица потока описана в YAML (`type: flow` + `table:`), `FlowTables` (`report-ir`) собирает `IrTable`; `IrGroupTitle`, `IrTable.fillBlank`, `IrTableHeader.repeat`; `IrColumn.header` удалён; `TemplateMain` рисует поток из `--data`; parity-тест со старым кодом | `c4d8893` |
| 3 | Правила данных таблицы в YAML: `groupBy`, структурные предикаты `where`, `sortBy` (натуральная русская сортировка), `computed.sequence`, `cases`, `format`; `Specification.kt` только поставляет `DataContext`; пример `purchased-list.yaml` | `03ecc29` |
| — | Фикс: `report-server` не компилировался (параметр `customerRepresentative` в `Routing.kt`) | `29a0f2a` |
| — | Тесты слоя раскладки переведены на границы-`Line` (три теста были красными) | `c8a8cda` |
| 4 | Арифметика `computed` (multiply/add/subtract/divide) и итоги `totals` (sum/count/min/max/avg, группа и таблица), `IrTotalRow`, `IrGroup.footer`/`IrTable.footer`, стиль `totalText` | `ff7cbc5` |
| — | Практикум `template-guide.md` + проверенные шаблоны `tutorial/` и `tutorial/errors/` | `314c16c` |
| — | README модуля `report-template` перенесён в вики и удалён (документация живёт только в `docs/wiki`) | `aeec4c7` |
| — | Фиксы: id слотов привязаны к `FrameSpec` только на пути спецификации (`bindStaticSlots`), ранняя проверка поворота текста (0/90), типизированные ошибки `--data` | `f910e64` |
| 5 | Нумерация физических строк `${line.number}` + раздел `lines` (расчёт в раскладке, `IrTable.lineNumbers`) | `1234ffe` |
| 6 | Параметризованные стили (`{base, size, bold, italic, underline}`) и жёсткий перенос `\n` в ячейках данных | `998529a` |
| 7 | Многоуровневая шапка потока: `header.rows`, `span`, `rowSpan` (`IrHeaderGrid`) | `e4f45bd` |
| 8 | `remainder: stretch\|gap` для `fill: blank`; нижняя граница последней строки рисуется всегда (меняет 8 golden-файлов спецификации) | `81fa21d` |

Тесты ветки: golden-эталоны раскладки в `reports/specification/src/test/resources/golden/*.txt` (текстовый дамп Layout IR в сотых
долях мм, значения сверены с ручным расчётом): `StaticBlocksGoldenTest` (спецификация и рамка без таблицы, 9 эталонов), `TotalsGoldenTest`,
`LineNumbersGoldenTest`, `StylesAndBreaksGoldenTest`, `MultiHeaderGoldenTest`, `RemainderGoldenTest`; parity-тесты
`SpecificationTableParityTest` (таблица из YAML = старая таблица из кода, эталон `LegacySpecificationTable` только в тестах),
`SpecificationQuantityFormatTest`, `FrameSpecsTemplateParityTest` (`report-ir`, `FrameSpecs` из YAML = старые константы).
Шаблоны практикума реально запускались через `runTemplate` (числа в `template-guide.md` взяты из SVG), но PDF шаблонов глазами не проверялся:
см. [known-gaps.md](known-gaps.md).

## Loodsman API интеграция

**Статус:** Работает (feature/service). Клиент + сервер end-to-end проверены на
реальном Loodsman, известные баги с классификацией типов объектов исправлены.

- `report-loodsman`: клиент Loodsman API v4, преобразование спецификации в DTO
- `report-server`: REST server (Ktor) обслуживает `POST /specifications/{versionId}`

Все особенности и грабли API (авторизация, разделение свойств/атрибутов, правило
ключевого атрибута по типу объекта, путаница `idType` связи vs типа объекта,
нерабочие batch-эндпоинты) — **[loodsman-integration.md](loodsman-integration.md)**.
Читать перед любыми правками `report-loodsman`.

## Что сознательно НЕ начато

XLSX-проекция (фаза 7 по `../dev-plan-0.1.md`) — отложена по явному решению пользователя.
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

Golden-эталоны используются как регрессионный барьер: SVG-текст и PNG-растеризация PDF
(`report-render-svg`, `report-render-pdf`) и текстовые дампы Layout IR (`reports/specification`, каталог `golden/`).
Отсутствующий эталон создаётся при первом прогоне, дальше сравнение байт-в-байт. Правила обращения — в
[running-the-project.md](running-the-project.md).
