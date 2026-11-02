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
| 6 | End-to-end на mock-данных: `report-api`/`report-data`/`reports/specification` (модули `report-api` и `report-data` позже удалены, см. [architecture-improvements.md](architecture-improvements.md)) | `report-api`, `report-data`, `reports/specification` | `c94b574` |
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

## Ветка `refactor/loodsman-api`

Интеграция с Loodsman переведена на слои: `loodsman-client` (библиотека: `LoodsmanApi`, DTO, сессия) →
`report-loodsman` (`PdmNodeStructureClient`, DTO → домен) → `reports/specification` (`ProductNode.toSpecificationData()`) →
`report-server`. Легаси-путь (`PdmClient.fetchSpecification`, `LoodsmanPdmClient`, `SpecificationAssembly`) удалён. Следующий слой легаси —
порт `PdmClient`, модуль `report-pdm` и `LoodsmanKtorPdmClient` — удалён в коммите `8ccefb1` (подробно —
[design-decisions.md](design-decisions.md#удаление-старого-пути-pdmclient-и-report-pdm)).
Правила — [loodsman-integration.md](loodsman-integration.md), решения — [design-decisions.md](design-decisions.md#решения-ветки-refactorloodsman-api),
остаток — [known-gaps.md](known-gaps.md#loodsman-интеграция-ветка-refactorloodsman-api).

Коммиты (от старого к новому, 18 шт., поверх `refactor/pdm-domain-port`):

| Этап | Что | Коммиты |
|---|---|---|
| Фасад и клиенты | `LoodsmanApi` поверх ktor-клиентов; `PdmNodeStructureClient` на `LoodsmanApi` (`LoodsmanKtorPdmClient` позже удалён, см. ниже) | `03f71f7`, `bd2c4ba`, `2ced4be` |
| Правила данных | отказ от полузаполненного диапазона количества; `PdmNode` → `SpecificationData`; материал в столбец «Наименование»; сведение ё к е в `KindMapper` | `bbe2af5`, `5a7f8be`, `2724c00`, `2f14321` |
| Регрессия | parity-тест добавлен, затем превращён в регрессионный тест конвейера `SpecificationPipelineTest` | `2117681`, `460fa32` |
| Сервер | `specificationQuery`; спецификация из структуры; `SpecificationDataException` и 502; не глотать отмену при остановке | `e0d215a`, `560ea9b`, `7b753d1`, `9e209da`, `5a02a32` |
| Легаси | удалён старый путь `fetchSpecification` | `306aebf` |
| Библиотека | модуль `loodsman-client` выделен; пакет `io.github.konstantinkhan.loodsman`; настроена Maven-публикация | `c5d12a0`, `14159c7`, `c36cadb` |

`settings.gradle.kts` содержит 14 модулей (13 верхнего уровня и `reports:specification`). Число тестов в эту запись не
пересчитывалось.

## Loodsman API интеграция

**Статус:** Работает (feature/service). Клиент + сервер end-to-end проверены на
реальном Loodsman, известные баги с классификацией типов объектов исправлены.

- `loodsman-client`: клиент Loodsman API v4 (библиотека, `LoodsmanApi`)
- `report-loodsman`: DTO → домен (`LoodsmanProductStructureClient`, маппинг по YAML)
- `report-server`: REST server (Ktor) обслуживает `POST /specifications/{versionId}`

Все особенности и грабли API (авторизация, разделение свойств/атрибутов, правило
ключевого атрибута по типу объекта, путаница `idType` связи vs типа объекта,
нерабочие batch-эндпоинты) — **[loodsman-integration.md](loodsman-integration.md)**.
Читать перед любыми правками `loodsman-client` и `report-loodsman`.

Группы замен (допускаемые замены, `get-info-about-version-mode-20`) — в домене `PdmNode.replacements` при
`StructureQuery.replacements = true`, предел уровней `replacementDepth`. Сопоставление с usages через
`CompositionUsage.linkId`. Этап 1 (загрузка и домен), этап 2 — спецификация выводит допускаемые замены корня (раздел «Допускаемые замены» в specification-table-format.md). Детали — раздел «Группы замен» в
[loodsman-integration.md](loodsman-integration.md#группы-замен-допускаемые-замены).

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
