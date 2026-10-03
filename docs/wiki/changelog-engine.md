# Что нового в ветке `engine`

Ветка `engine`, 15 коммитов поверх `279b40f` (merge PR #1, 2026-10-01), все 2026-10-03. Страница сверена с `git log
279b40f..HEAD` и кодом (HEAD `81fa21d`). Справка по возможностям: [template-yaml.md](template-yaml.md), практикум:
[template-guide.md](template-guide.md), решения и их причины: [design-decisions.md](design-decisions.md), запуск и тесты:
[running-the-project.md](running-the-project.md).

## Хронология (от старого к новому)

| Коммит | Что появилось |
|---|---|
| `e588fac` | Удалён устаревший `SpecificationEndToEndTest` (красный, сравнивал с протухшим снапшотом); каталог `reports/` больше не в `.gitignore` |
| `e0eda3d` | Loodsman: сборочные единицы (`Сборочная единица`) попадают в спецификацию как `ASSEMBLY` (раньше терялись) |
| `928f245` | **Модуль `report-template`**: YAML-шаблон страницы -> модель -> валидация -> резолвер; блоки `frame` / `rect` / `table` / `text` / `flow`, привязка `attach` (в том числе раздельно по осям x / y), якоря (`sheet.*`, `<id>.*`, `col[i]`, `row[j]`, `cell[r,c]`), наборы блоков (`blocksets`, `use`, `params`, `ports`), поворот таблиц, `when: first\|rest\|all`, `reserves` и область потока; `Corner` / `resolveAnchor` перенесены в `report-geometry`; статические блоки ГОСТ из `gost-spec.yaml`; **типизированный контекст данных** (`DataType`, `DataSchema`, `DataContext`), `format` / `optional` у bind, контракт шаблона с данными; `FrameField` заменён биндом по пути; CLI `runTemplate` (`TemplateMain`) с `--data`; фиксы таблицы спецификации (отступ ячейки учитывается при переносе, длинные слова рвутся по символам, заголовок группы связан с первой строкой данных, единица только у материалов) |
| `c4d8893` | **Таблица потока в YAML**: блок `type: flow` + `table:` (колонки, `stick`, шапка, `groupTitle`, ячейки строки, `fill: blank`, `keep.titleChain`, `styles`); `FlowTables` в `report-ir` собирает `IrTable`; `IrGroupTitle`, `IrTable.fillBlank`, `IrTableHeader.repeat`; `IrColumn.header` удалён (дубль); строки таблицы в `TemplateMain` из корня `item:` файла `--data`; parity-тест со старым кодом |
| `03ecc29` | **Правила данных таблицы в YAML**: `groupBy` по enum-полю (`order`, `titles`, `skipEmpty`, `omit`), структурные предикаты `where`, устойчивый `sortBy` с натуральной русской сортировкой, `computed.sequence` (`scope: table\|group`), `cases`, `format` (`pattern`, `locale`, `rounding`); `Specification.kt` только поставляет `DataContext`; пример `purchased-list.yaml` |
| `29a0f2a` | Фикс: `report-server` снова компилируется (параметр `customerRepresentative` в `Routing.kt`) |
| `c8a8cda` | Тесты слоя раскладки обновлены под границы-`Line` (три теста были красными) |
| `ff7cbc5` | **Арифметика и итоги**: `computed` с `multiply` / `add` / `subtract` / `divide` (точный `BigDecimal`, явные `scale` + `rounding`), `totals` (`sum` / `count` / `min` / `max` / `avg`, `scope: group\|table`, `where`, `skipEmpty`, `format`); `IrTotalRow`, `IrGroup.footer`, `IrTable.footer`, цепочка `keepWithNext` (итог не остаётся один вверху страницы), стиль `totalText` |
| `314c16c` | Практикум `template-guide.md` (шаги 1-12) и проверенные запуском шаблоны `report-cli/.../templates/tutorial/`, сломанные шаблоны `tutorial/errors/` |
| `aeec4c7` | README модуля `report-template` перенесён в вики и удалён (документация живёт только в `docs/wiki`) |
| `f910e64` | Фиксы: id слотов (`stamp`, `leftMargin`, ...) привязаны к `FrameSpec` только на пути спецификации (`PageSetup.bindStaticSlots`); поворот текста, который не умеет Layout IR (не 0 и не 90), отвергается при валидации с YAML-путём; типизированные ошибки `--data` (`data file not found`, имя строки данных в ошибке пропущенного значения, смешанные типы в поле) |
| `1234ffe` | **Нумерация физических строк**: bind `${line.number}` (новый корень данных `line`) и раздел `lines: {start, scope: table\|page, fill}`; номер ставит раскладка, `IrTable.lineNumbers` |
| `998529a` | **Параметризованные стили** `{base, size, bold, italic, underline}` для псевдонимов `styles` и **жёсткий перенос `\n`** в ячейках данных |
| `e4f45bd` | **Многоуровневая шапка** потока: `header.rows` с `span` и `rowSpan` (`IrHeaderGrid`, `IrHeaderCell`); однорядная форма не изменилась |
| `81fa21d` | **`remainder: stretch\|gap`** для `fill: blank`; нижняя граница последней строки рисуется всегда |

Тесты ветки: golden-дампы Layout IR (`reports/specification/src/test/resources/golden/*.txt`) для спецификации, итогов,
нумерации строк, стилей и переносов, шапки, `remainder`; parity-тесты `SpecificationTableParityTest`,
`SpecificationQuantityFormatTest`, `FrameSpecsTemplateParityTest`; смоук `TemplateMainTest` на шаблонах практикума. Итого 368
тестов на 13 модулей.

## Как мигрировать

Ломающие изменения и что делать. Затронуты код, использующий DSL `report-ir`, и YAML-шаблоны, написанные по промежуточным
состояниям ветки.

### Код (Kotlin)

| Было | Стало | Что делать |
|---|---|---|
| `item.position` и `item.quantityText` в данных строки, `formattedQuantity()` в `report-data` | запись `item` несёт сырые поля (`designation`, `name`, `kind`, `quantity` Decimal, `unit`) | Номер позиции: `computed: {position: {sequence: {scope: table}}}`, текст количества: ячейка с `cases` + `format` (пример в `gost-spec.yaml`). Эталон старого форматтера остался в тестах как `legacyFormattedQuantity` |
| `FlowGroup`, `buildRows`, список групп `GROUPS` в `Specification.kt` | `FlowTables.build(spec, schema, rows, path)` принимает плоский список записей | Группировку задаёт `groupBy` в YAML, код передаёт записи |
| `pageSetup(frameBindings = FrameBindings(...))` | `pageSetup(dataContext = ...)`; `FrameBindings(designation, name)` остался как готовый `DataContext` | Переименовать параметр; для своих полей собрать `DataContext` билдером `dataContext { doc { ... } }` |
| `FrameCell.Dynamic(..., field = FrameField.X)`, enum `FrameField` | `FrameCell.Dynamic(..., path = "doc.designation")`, плюс `format` / `optional`; номера листов это `page.number` / `page.total` | Заменить enum на путь бинда; `SHEET_NUMBER` -> `page.number`, `SHEETS_TOTAL` -> `page.total` |
| `IrColumn.header` | поле удалено, текст шапки только в `IrTableHeader.cells` | Читать/писать текст шапки через `IrTableHeader` |
| жёстко заданные `FrameSpecs` (константы), `frameOrigin()` и аналоги в `LayoutEngine.kt` | `FrameSpecs` строятся из `gost-spec.yaml`, расстановку считает `TemplateResolver` | Размеры и состав ячеек править в YAML (парность со старыми константами держит `FrameSpecsTemplateParityTest`) |

### YAML-шаблоны

| Ситуация | Правило |
|---|---|
| `groupTitle` без `groupBy` | теперь ошибка; задайте `groupBy` или уберите `groupTitle` |
| `${...}` в обычном `text:` | `text:` всегда литерал, подстановки нет (раньше неизвестный bind мог остаться текстом `${...}`; теперь неизвестный bind это ошибка контракта `путь: сообщение` с подсказкой ближайшего имени). Значения подставляйте через `bind` |
| `format` для Decimal | локаль не подразумевается: без `locale` разделитель точка. Запятую задавайте явно `locale: ru` (так сделано для количества материалов) |
| `rotate` таблицы + `rotate` ячейки | итоговый поворот текста может быть только 0 или 90; 270 у `text` и итог (блок + ячейка) не 0 и не 90 дают ошибку валидации с путём, а не падение в раскладке. Читаемая таблица `rotate: 270` требует `rotate: 90` у каждой ячейки |
| значение bind отсутствует | ошибка, если у ячейки нет `optional: true` (для строк потока: `source row N {...}: no value for bind ...`) |
| enum-значение в данных вне `groupBy.order` / `omit` | ошибка контракта, строка не теряется молча: добавьте значение в `order` или `omit` |
| блок с id `stamp`, `leftMargin`, ... в своём шаблоне | с `bindStaticSlots = false` (`runTemplate`) это обычный блок с собственным размером и `reserves`; слоты привязываются только в боевом движке |
| поле `--data` с разными типами в строках (`5` и `ok`) | ошибка вывода схемы; тегируйте значения (`!str 5`); `1` и `1.5` сливаются в Decimal |
| `remainder` без `fill: blank` | ошибка `'remainder' needs 'fill: blank'` |

### Поведение раскладки (меняет эталоны)

- **`remainder` по умолчанию `stretch`** (прежнее поведение: остаток высоты в последней пустой строке), но
  **нижняя граница последней пустой строки теперь рисуется всегда**. Раньше при наличии рамки её убирали. В 8 golden-файлах
  спецификации (`spec-single-page`, `spec-single-page-no-pz`, `spec-multi-page`, `spec-multi-page-no-pz`, `spec-a3-landscape`,
  `spec-custom-blocks`, `spec-group-boundary`, `spec-all-kinds-long-designation`) добавилось 70 строк `Line` (по 7 тонких
  горизонталей на каждую страницу с дозаполнением), ничего не убрано и не сдвинуто. Если у вас свои golden поверх спецификации,
  сравните `git diff` и убедитесь, что разница именно такая, прежде чем пересоздавать эталон (правила: [running-the-project.md](running-the-project.md#golden-эталоны-как-они-работают-и-как-с-ними-обращаться)).
  Нужны одинаковые высоты строк и зазор над штампом: `remainder: gap`.
- Единица измерения в колонке «Примечание» выводится только у материалов (`MATERIAL`); у остальных видов `unit` игнорируется.
- Длинные слова без пробелов в ячейках таблицы рвутся по символам, ширина переноса учитывает отступ ячейки 1 мм с обеих сторон.
- Страницы без `lines`, `totals`, `header.rows`, `\n`: раскладка байт-в-байт как раньше (кроме нижней границы выше).
