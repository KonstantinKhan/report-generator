# Архитектура: pipeline и границы

См. также: [architecture-0.2.md](../architecture-0.2.md) (полная актуальная
версия — рамка/штамп как `FrameSpec`, §34), [architecture-0.1.md](../architecture-0.1.md)
(исходная версия), [modules.md](modules.md) (модули по отдельности),
[template-yaml.md](template-yaml.md) (YAML-шаблоны, данные, таблица потока).

> **Обновление (2026-10-03, ветка `engine`).** Добавлен модуль `report-template` и путь «YAML-шаблон + типизированные
> данные». Статические блоки листа и описание таблицы спецификации теперь в YAML, из кода ушли `FrameField`,
> `frameBindings`, захардкоженные `FrameSpecs`, список групп `GROUPS` в `Specification.kt`, `item.position` /
> `item.quantityText`, `IrColumn.header`. Хронология: [changelog-engine.md](changelog-engine.md).

## Главный pipeline

Путь спецификации (боевой движок):

```text
Loodsman REST API v4
      │
      ▼
loodsman-client   — LoodsmanApi: группы эндпоинтов (auth, objectInfo, metaData, metaDataCustom), DTO, сессия (повтор при 401/419).
      │             Ничего о доменной модели не знает
      ▼  LoodsmanApi (DTO)
report-loodsman   — DTO → домен: LoodsmanProductStructureClient (PdmNodeStructureClient) — фасад;
      │             обход дерева делят NodeLoader / LinkLoader / LinkAttributeLoader / TreeAssembler;
      │             конфиг маппинга (YAML), KindMapper / RelationMapper
      ▼  ProductNode / Usage (модель структуры)
report-domain     — модель структуры изделия: ProductNode, Usage, Field<T>, PdmNodeKind, Relation,
      │             AttributeKey, AttributeValue, StructureQuery; порт PdmNodeStructureClient (единственный путь к PDM);
      │             (модель отчёта спецификации перенесена в reports/specification, см. known-gaps)
      ▼  ProductNode.toSpecificationData()
reports/specification  — specificationQuery (запрос структуры), toSpecificationData() (правила отчёта,
      │                   SpecificationDataException при плохих данных), specification() = Report Builder:
      │                   адаптер данных SpecificationData → DataContext
      │                   (схема + значения doc.*, типизированные записи item.*)
      │                   + document { pageSetup, table(FlowTables.build(...)) }
      ▼
report-template   — YAML (gost-spec.yaml): статические блоки, якоря, шаблон таблицы потока
      │             (FlowTableSpec); DataContext/DataSchema, контракт, FlowShaper
      ▼
Semantic IR (report-ir) — FlowTables: описание таблицы + записи → IrTable
      │                   (группы, нумерация, форматы, итоги, шапка-сетка)
      ▼
report-layout      — Layout Engine: Semantic IR → Layout IR
      │               (измерение, перенос, пагинация, дозаполнение, нумерация строк;
      │                статические блоки из шаблона)
      ▼
Layout IR (report-layout-ir)
      │
      ├──────────────┬──────────────┐
      ▼              ▼              ▼
report-render-svg  report-render-pdf   (XLSX — не начато)
```

Путь своего шаблона (`report-cli` `TemplateMain`, `runTemplate`): минуя Report Builder.

```text
template.yaml ──► TemplateLoader (разбор + валидация)
--data file.yaml ► DataYaml (DataContext: схема выводится из файла; item: строки таблицы)
                        │
                        ▼
              TemplateContract.require(template, schema)   — ошибка до раскладки
                        │
        есть flow + table:                    нет flow
                        │                         │
   FlowTables.build → IrTable                     │
   layOut(bindStaticSlots = false)                │
   (пагинация, резерв места блоков)               │
                        │                         │
        + layOutTemplate(TemplateResolver.resolve) на каждую страницу
                        ▼
                  Layout IR ──► report-render-svg / report-render-pdf
```

В обоих путях слои одинаковы: YAML описывает ЧТО (структура, стиль, правила данных), алгоритм (измерение, перенос,
пагинация, дозаполнение) остаётся Kotlin-кодом `report-layout`. Почему так — [design-decisions.md](design-decisions.md).

`report-cli` — точка сборки пайплайна в команду (демо `Main.kt` на зашитой фикстуре и `TemplateMain.kt` для своих
шаблонов), не часть архитектурного pipeline самого по себе.

## Данные шаблона: DataContext

`report-template` владеет типизированной моделью данных: `DataType` (`Str`, `Integer`, `Decimal`, `Date`, `Bool`,
`Enum`, `ListOf`, `Record`), `DataSchema` (дерево полей под корнями `doc`, `item`, `page`, `line`), `DataValue`,
`DataContext` (`schema` + `get(path)`). Каждый bind шаблона (`${doc.designation}`, `${item.name}`, `${page.number}`,
`${line.number}`) проверяется по схеме при загрузке (`TemplateContract`), поэтому опечатка в пути или неподходящий формат
это ошибка до раскладки, а не пустая ячейка. Адаптер данных конкретного отчёта (для спецификации
`SpecificationData.toDataContext()`) объявляет схему и значения одним билдером. `page.*` добавляет движок при раскладке
каждой страницы (число страниц известно только после пагинации), `line.number` ставит раскладка (перенос известен только
ей). Подробности — [template-yaml.md](template-yaml.md#bind).

## Путь «шаблон → IrTable»

`FlowTableSpec` (модель блока `flow` + `table:`) и записи `item` → `FlowShaper` (`where`, арифметика `computed`,
`sortBy`, `groupBy`, `sequence`, `totals`) → `FlowTables.build` в `report-ir` собирает `IrTable` (`IrColumn`,
`IrTableHeader` с `IrHeaderGrid`, `IrGroup`/`IrRow`/`IrCell`, `IrGroupTitle`, `IrTotalRow`, `IrLineNumbers`,
`IrFillRemainder`). Семантический IR по-прежнему не знает координат: `report-layout` измеряет текст, переносит слова,
режет страницы, дозаполняет страницу пустыми строками и нумерует физические строки.

## Два IR — главная граница

**Semantic IR** (`report-ir`) знает смысл: что это таблица, какие колонки,
какие группы, какой текст, какие стили. Не знает `x/y`, номер страницы,
где случился разрыв.

**Layout IR** (`report-layout-ir`) знает физику: страницы, координаты,
линии, прямоугольники, уже перенесённый текст. Не знает, что такое
"спецификация" или "деталь".

## Правило зависимостей модулей

```text
report-geometry  ← report-template       (+ kaml; НЕ зависит от report-ir / layout)
report-geometry + report-template  ← report-ir
report-geometry  ← report-layout-ir      (НЕ зависит от report-ir и report-template!)
report-ir + report-layout-ir  ← report-layout     (+ PDFBox)
report-layout-ir  ← report-render-svg    (main-код, только это)
report-layout-ir + report-layout(FontRegistry)  ← report-render-pdf
loodsman-client  (нет зависимостей от доменных модулей; api в report-loodsman)
loodsman-client ← report-loodsman (api)
report-domain ← report-loodsman
report-domain ← reports/specification (+ report-ir)
reports/specification, report-layout, report-template, рендереры ← report-cli
report-loodsman, reports/specification, report-layout, report-render-pdf ← report-server
```

Граница `loodsman-client` проверяется grep'ом (должно быть пусто):

```bash
grep -rn "import dev.reportgenerator" loodsman-client/src/main/     # библиотека не знает о доменных модулях
```

Границы ответственности слоёв Loodsman: `loodsman-client` — HTTP, DTO, сессия; `report-loodsman` — маппинг
Loodsman → домен, без правил отчёта; `reports/specification` — правила спецификации (материалы, ошибки данных);
`report-server` — HTTP-статусы и жизненный цикл сессии. Подробности и правила — [loodsman-integration.md](loodsman-integration.md),
почему так — [design-decisions.md](design-decisions.md#решения-ветки-refactorloodsman-api).

`report-template` это самый нижний слой после геометрии: он знает про YAML, данные и геометрию блоков, но ничего про
`IrTable`, раскладку и рендер. `report-ir` зависит от него (api), потому что `FlowTables` и `FrameSpecs` читают шаблоны, а
`PageSetup` несёт `Template` и `DataContext`. Обратной зависимости (`report-template` → `report-ir`) нет, она бы замкнула цикл.

Ключевая инвариант, которую держали через все фазы и проверяли grep'ом после
каждого изменения:

```bash
grep -rn "import dev.reportgenerator.ir" report-layout-ir/src/main/     # должно быть пусто
grep -rn "import dev.reportgenerator.ir\." report-render-svg/src/main/ report-render-pdf/src/main/  # должно быть пусто
grep -rn "import dev.reportgenerator.template" report-layout-ir/src/main/ report-render-svg/src/main/ report-render-pdf/src/main/  # должно быть пусто
grep -rn "import dev.reportgenerator.\(ir\|layout\)" report-template/src/main/            # должно быть пусто
```

Ренде­реры видят **только** Layout IR. Это то, что позволяет когда-нибудь
добавить третий рендерер (XLSX или что угодно), не трогая семантику.

## Почему TextAlign и TextOrientation живут по-разному

Хороший пример границы в действии — фича с ЕСКД-заголовком
([подробности](eskd-specification-header.md)):

- **`TextAlign`** (LEFT/CENTER) существует только в `report-ir`. Он влияет
  только на то, КАКОЙ `x` посчитает `report-layout` при постройке
  `PositionedText` — сам Layout IR ничего про alignment не знает и не должен:
  рендереру всё равно, почему текст оказался в такой-то точке.
- **`TextOrientation`** (HORIZONTAL/VERTICAL_BOTTOM_TO_TOP) существует **в
  обоих** слоях — отдельные enum в `report-ir` (семантическое намерение
  "текст должен читаться вертикально") и в `report-layout-ir` (физический
  факт "эти глифы нужно повернуть при рисовании"). Рендереру ЭТО знать
  обязательно — он должен реально повернуть глиф на странице.

Правило: если это влияет только на ЧИСЛА (координаты) — остаётся в
report-ir/report-layout. Если это влияет на ТО, КАК рендерер рисует —
должно быть смоделировано и в Layout IR.
