# Архитектура инженерного генератора документов v0.2

> Версия 0.2. Предыдущая версия: [architecture-0.1.md](architecture-0.1.md)
> (сохранена как есть, не редактируется задним числом).
>
> **Что изменилось относительно 0.1:**
> - §6 — в Semantic IR добавлено понятие `FrameSpec`/`StampSpec` (рамка и
>   основная надпись как обычный IR-элемент, а не как непрозрачный layout
>   primitive).
> - §16 — Page Templates перестали быть только декларацией намерения: описан
>   конкретный механизм (`first`/`continuation` — два независимых пресета,
>   Layout Engine выбирает по номеру страницы вместо `if (pageNumber == 1)`
>   внутри layout-кода).
> - §17 — переписан: рамка/штамп теперь не «абстрактный reusable component»,
>   а конкретно `FrameSpec` с разделением constant/dynamic ячеек.
> - Новый §34 — модель рамки и штампа целиком: почему в IR, почему две
>   независимые пресеты, почему Kotlin-код, а не внешние данные.
>
> Мотивация: до 0.2 первая/последующие страницы различались веткой
> `isFirstPage()` внутри `LayoutEngine.renderPages()`
> (`report-layout/LayoutEngine.kt`) — ровно тот антипаттерн, который §16
> запрещал, просто не в рендерере, а в layout engine. Плюс `TitleBlockSpec`
> уже смешивал constant (подписи граф) и dynamic (`designation`, `name`,
> `sheetsTotal`) данные, но подписи графов были захардкожены в
> `drawTitleBlock()`, не в IR — то есть вне архитектурной границы §7.
>
> Всё остальное содержание ниже идентично 0.1 за исключением помеченных
> секций.

## 1. Назначение системы

Система предназначена для генерации инженерной документации из данных PDM с жёсткими требованиями к физическому представлению документа:

- форматы A4/A3 и другие;
- рамки и основная надпись;
- различные шаблоны первой и последующих страниц;
- таблицы;
- группировка строк;
- переносы таблиц между страницами;
- повторение заголовков;
- переносы текста;
- точное позиционирование линий и текста;
- последующий вывод в PDF и другие форматы.

Основная архитектурная идея:

> **Система не является универсальным конструктором отчётов. Это специализированный layout engine для инженерных документов, использующий промежуточное представление (IR).**

Главная граница системы — **IR**.

---

# 2. Главный pipeline

```text
                         PDM
                          │
                          │ REST API
                          ▼
                  ┌─────────────────┐
                  │   Report Data   │
                  └────────┬────────┘
                           │
                           ▼
                  ┌─────────────────┐
                  │ Report Builder  │
                  │    Kotlin DSL   │
                  └────────┬────────┘
                           │
                           │ produces
                           ▼
              ┌───────────────────────────┐
              │       Semantic IR         │
              │                           │
              │ Document                  │
              │ Section / Table           │
              │ Group / Row / Cell        │
              │ Text / Image              │
              │ Frame / Stamp (§34)       │
              │ Styles / Constraints      │
              └─────────────┬─────────────┘
                            │
                            │ consumes
                            ▼
              ┌───────────────────────────┐
              │       Layout Engine       │
              │                           │
              │ Measure                   │
              │ Flow                      │
              │ Table Layout              │
              │ Pagination                │
              │ Page Templates            │
              └─────────────┬─────────────┘
                            │
                            │ produces
                            ▼
              ┌───────────────────────────┐
              │         Layout IR         │
              │                           │
              │ Page                      │
              │ PositionedText            │
              │ Line / Rectangle / Image  │
              └─────────────┬─────────────┘
                            │
                  ┌─────────┼─────────┐
                  ▼         ▼         ▼
                 PDF       SVG       XLSX
```

---

# 3. Ключевой принцип: IR — архитектурный boundary

В системе существуют два основных промежуточных представления.

## 3.1 Semantic IR

Создаётся `Report Builder`.

Он описывает **смысл и структуру документа**, но не его физическое расположение.

Например:

```text
Document
│
├── Title
│
└── Table
    │
    ├── Columns
    │
    ├── Group "Сборочные единицы"
    │   ├── Row
    │   ├── Row
    │   └── ...
    │
    ├── Group "Детали"
    │   ├── Row
    │   └── ...
    │
    └── Group "Стандартные изделия"
        └── ...
```

Semantic IR знает:

- что это таблица;
- какие в ней колонки;
- какие группы;
- какие строки;
- какой текст;
- какие стили;
- какие semantic layout constraints.

Но не знает:

- на какой странице находится строка;
- её `x/y`;
- сколько пикселей/миллиметров она занимает;
- где произошёл page break.

---

# 4. Report Builder

`Report Builder` — это **не модель и не слой хранения**.

Это механизм преобразования:

```text
Report Data → Semantic IR
```

Его задача — выразить правила конкретного отчёта.

Например:

```kotlin
fun specification(data: SpecificationData): IrDocument =
    document {

        title("Спецификация")

        table {
            columns {
                position(width = 10.mm)
                designation(width = 35.mm)
                name(width = 80.mm)
                quantity(width = 15.mm)
            }

            group("Сборочные единицы") {
                data.items
                    .filter { it.kind == ItemKind.ASSEMBLY }
                    .forEach { row(it) }
            }

            group("Детали") {
                data.items
                    .filter { it.kind == ItemKind.PART }
                    .forEach { row(it) }
            }

            group("Стандартные изделия") {
                data.items
                    .filter { it.kind == ItemKind.STANDARD }
                    .forEach { row(it) }
            }
        }
    }
```

Builder отвечает на вопрос:

> **«Что должно находиться в документе?»**

Он не отвечает на вопрос:

> **«Где это физически окажется на странице?»**

---

# 5. Почему Builder и IR разделены

Это принципиально.

Builder является **кодом**.

IR является **данными**.

Например:

```text
Report Builder
      │
      │ execution
      ▼
Semantic IR
```

После выполнения Builder можно получить обычный объектный граф:

```kotlin
val ir: IrDocument = specification(data)
```

И дальше Builder больше не нужен.

Это позволяет:

- тестировать IR отдельно;
- сериализовать IR при необходимости;
- инспектировать IR;
- визуализировать IR;
- запускать разные layout algorithms;
- использовать разные источники IR;
- в будущем иметь визуальный редактор;
- в будущем генерировать IR другим способом.

Например, потенциально:

```text
Kotlin DSL ────────┐
                   │
Visual Designer ───┼──→ Semantic IR
                   │
JSON Definition ───┤
                   │
Other Builder ─────┘
```

Layout Engine при этом остаётся неизменным.

---

# 6. Semantic IR

*(изменено в 0.2 — добавлены `FrameSpec`/`StampSpec`, см. §34)*

Semantic IR является главным внутренним контрактом системы.

Пример концептуальной структуры:

```text
IrDocument
│
├── PageDefinition
├── Frame (FrameSpec, §34) ── constant + dynamic cells
├── Header
├── Footer
├── Content
│
└── Elements
     │
     ├── Text
     ├── Image
     ├── Table
     │    ├── Columns
     │    ├── Header
     │    ├── Group
     │    │    ├── Header
     │    │    └── Rows
     │    └── ...
     │
     └── ...
```

На Kotlin это может выглядеть примерно так:

```kotlin
data class IrDocument(
    val page: PageDefinition,
    val elements: List<IrElement>
)

sealed interface IrElement

data class IrText(
    val text: String,
    val style: TextStyle
) : IrElement

data class IrTable(
    val columns: List<IrColumn>,
    val header: IrTableHeader?,
    val content: List<IrTableElement>,
    val style: TableStyle
) : IrElement

data class IrGroup(
    val title: String,
    val rows: List<IrRow>
) : IrTableElement

data class IrRow(
    val cells: List<IrCell>,
    val constraints: LayoutConstraints = LayoutConstraints.Default
) : IrTableElement
```

Рамка и штамп — тоже обычный элемент Semantic IR, не отдельная подсистема.
Разбор модели — §34.

Это пока **не финальная модель**. Конкретный состав IR будет уточняться на реальном примере инженерной спецификации.

---

# 7. Что категорически не должно попадать в Semantic IR

Semantic IR не должен превращаться в набор координат.

Плохо:

```kotlin
IrText(
    text = "Корпус",
    x = 37.4.mm,
    y = 142.8.mm,
    width = 80.mm,
    height = 5.mm
)
```

Такой объект уже относится к Layout IR.

Правильно:

```kotlin
IrText(
    text = "Корпус",
    style = Styles.tableText
)
```

Layout Engine сам определит:

```text
x
y
width
height
page
line breaks
baseline
```

Это правило прямо применимо к рамке/штампу (§34): `FrameSpec` описывает
СОСТАВ ячеек и их содержимое (constant/dynamic), но не их `x/y` — координаты
каждой ячейки внутри рамки считает Layout Engine, как и для обычной таблицы.

---

# 8. Layout Engine

Layout Engine преобразует:

```text
Semantic IR → Layout IR
```

Его задача:

> **превратить семантическое описание документа в физически размещённые страницы.**

Основные функции:

1. измерение текста;
2. вычисление размеров элементов;
3. вычисление ширины колонок;
4. вычисление высоты строк;
5. wrapping текста;
6. построение таблиц;
7. определение доступного пространства;
8. pagination;
9. page breaks;
10. повторение заголовков;
11. обработка first/continuation/last page;
12. размещение рамок и основной надписи (по `FrameSpec`, выбранному текущим
    `PageTemplate` — §16, §34);
13. применение layout constraints.

---

# 9. Layout и Pagination

Концептуально:

```text
Layout
Pagination
```

— разные задачи.

Но практически они образуют один тесно связанный subsystem.

Например:

```text
Нужно определить высоту строки
        ↓
Текст переносится на 2 строки
        ↓
Высота строки увеличивается
        ↓
Группа больше не помещается
        ↓
Происходит page break
        ↓
Меняется доступная высота
        ↓
Нужно пересчитать размещение
```

Поэтому архитектурно:

```text
Layout Engine
├── Measurement
├── Flow/Layout
├── Table Layout
└── Pagination
```

а не четыре независимых сервиса.

---

# 10. Внутренний pipeline Layout Engine

Логически можно представить:

```text
Semantic IR
    │
    ▼
Normalize
    │
    ▼
Measure
    │
    ▼
Layout
    │
    ▼
Pagination
    │
    ▼
Positioned/Layout IR
```

Но это **не обязан быть строго линейный pipeline**.

В реальности возможны итерации:

```text
measure
   ↓
layout
   ↓
page break?
   ↓
re-layout
```

Поэтому не следует проектировать систему так, будто каждый этап может работать полностью независимо.

---

# 11. Layout IR

Результатом Layout Engine является физическое представление документа.

Например:

```kotlin
data class LaidOutDocument(
    val pages: List<Page>
)

data class Page(
    val number: Int,
    val format: PageFormat,
    val elements: List<PageElement>
)
```

Элементы:

```kotlin
sealed interface PageElement

data class PositionedText(
    val text: String,
    val rect: Rect,
    val style: TextStyle
) : PageElement

data class Line(
    val from: Point,
    val to: Point,
    val style: LineStyle
) : PageElement

data class Rectangle(
    val rect: Rect,
    val style: BorderStyle
) : PageElement

data class PositionedImage(
    val rect: Rect,
    val image: ImageData
) : PageElement
```

Здесь координаты уже **разрешены и необходимы**.

Layout IR отвечает:

> **«Что именно и где физически нарисовать?»**

---

# 12. Renderers

Renderer получает только Layout IR:

```text
Layout IR
    │
    ├── PDF Renderer
    ├── SVG Renderer
    └── XLSX Renderer
```

Renderer не должен знать:

- что такое PDM;
- что такое спецификация;
- что такое «деталь»;
- почему существует группа;
- откуда взялся текст;
- какая бизнес-логика его сформировала;
- что рамка первой страницы отличается от рамки последующих — для рендерера
  это просто ещё один набор `Rectangle`/`PositionedText` на странице.

Например PDF renderer видит:

```text
Page 1
  Text at (20, 30)
  Line from (...) to (...)
  Rectangle (...)
```

и превращает это в PDF.

---

# 13. PDF

PDF Renderer не должен реализовывать собственный PDF format writer без необходимости.

Архитектурно:

```text
Layout IR
    ↓
PDF Renderer
    ↓
PDF library
    ↓
PDF
```

Мы владеем:

- layout;
- координатами;
- геометрией;
- правилами;
- структурой документа.

PDF library отвечает за сериализацию PDF.

---

# 14. SVG

SVG рекомендуется реализовать раньше PDF.

Причина — чрезвычайно простая диагностика:

```text
Layout IR
    ↓
SVG
```

Можно открыть результат в браузере и увидеть:

- реальные координаты;
- границы;
- линии;
- размеры;
- wrapping;
- page break;
- расположение элементов.

SVG может стать фактически **debug renderer**.

---

# 15. XLSX

Excel принципиально отличается от PDF.

PDF:

```text
page
 ├── x/y
 ├── lines
 └── text
```

Excel:

```text
sheet
 ├── rows
 ├── columns
 ├── cells
 ├── merges
 └── styles
```

Поэтому не стоит требовать:

> «Один Layout IR должен идеально одинаково выглядеть в PDF и Excel».

Для PDF/SVG можно использовать один Layout IR.

Для XLSX разумнее иметь отдельную projection:

```text
Semantic IR
      │
      ├────────→ Layout Engine → Layout IR → PDF/SVG
      │
      └────────→ XLSX Projection → XLSX
```

Таким образом Excel остаётся **семантическим табличным представлением**, а не попыткой симулировать PDF внутри Excel.

---

# 16. Page Templates

*(изменено в 0.2 — конкретизирован механизм выбора шаблона, первая и
продолжающая рамки — независимые пресеты `FrameSpec`, §34)*

Первая и последующие страницы — часть layout semantics.

Не следует делать:

```kotlin
if (pageNumber == 1) {
    ...
}
```

в PDF renderer — и, что уточнено в 0.2, **в Layout Engine тоже**. До 0.2
именно так и было сделано: `LayoutEngine.renderPages()` выбирал, рисовать ли
титульный блок, веткой `isFirstPage()` внутри функции `drawChrome()`. Само
условие «первая страница особая» — легитимная часть доменной логики, но
место для него — явный `PageTemplate`, а не inline-проверка внутри общей
функции рисования.

Вместо этого:

```text
Page Template
├── First          → FrameSpec (firstPageFrame)
├── Continuation   → FrameSpec (continuationFrame)
└── Last
```

Например:

```kotlin
document {
    pageTemplate {
        first {
            frame(FrameSpecs.firstPageA4)
        }

        continuation {
            frame(FrameSpecs.continuationA4)
        }
    }
}
```

`first` и `continuation` — это **два независимых `FrameSpec`**, не один
объект с флагами видимости на ячейках (решение и обоснование — §34). Layout
Engine определяет, какая страница является первой/промежуточной/последней, и
выбирает соответствующий `FrameSpec` из `PageTemplate` для рисования — сам
факт "если первая страница" существует ровно один раз, в точке выбора
пресета, не размазан по layout-коду.

Renderer просто рисует результат — Layout IR для первой и для второй
страницы для него неотличимы по структуре, отличается только содержимое
`Rectangle`/`PositionedText`.

---

# 17. Engineering frame и title block

*(переписано в 0.2 — конкретная модель вместо общего пожелания
"reusable component"; полная версия — §34)*

Рамка и основная надпись — не непрозрачная функция, рисующая пиксели, а
`FrameSpec`: обычный элемент Semantic IR со списком ячеек, часть которых
constant (подписи граф — «Обозначение», «Наименование», «Масса», «Масштаб» и
т.п.), часть dynamic (значения, привязанные к полям документа/данных —
шифр изделия, наименование, масса, номер листа).

```kotlin
eskdFrame(FrameSpecs.firstPageA4)

titleBlock(
    format = A4
)
```

Они не должны быть частью PDF Renderer — это данные IR, которые проходят
через тот же Layout Engine, что и таблица:

```text
Semantic IR (FrameSpec)
              ↓
        Layout Engine
              ↓
         Layout IR (Rectangle + PositionedText)
```

Готовые наборы (первая страница A4, последующая A4, первая A3, ...) хранятся
как Kotlin-пресеты — конкретика и обоснование в §34.

---

# 18. Tables

Таблица является одним из центральных элементов Semantic IR.

```text
Table
│
├── Columns
│
├── Header
│
└── Content
    │
    ├── Group
    │   ├── Group Header
    │   └── Rows
    │
    ├── Group
    │   ├── Group Header
    │   └── Rows
    │
    └── ...
```

Semantic IR знает:

```text
Group("Детали")
```

Layout Engine решает:

```text
Group Header → Page 2
Rows 1..18   → Page 2
Rows 19..34  → Page 3
```

---

# 19. Layout Constraints

IR должен иметь не только содержание, но и **ограничения размещения**.

Например:

```kotlin
LayoutConstraints(
    keepTogether = true,
    keepWithNext = true
)
```

Возможные правила:

- `KeepTogether`
- `KeepWithNext`
- `AvoidBreakBefore`
- `AvoidBreakAfter`
- `AllowBreak`

Например заголовок группы:

```text
Group Header
     +
First Row
```

может требовать:

```text
keepWithNext = true
```

чтобы не получить:

```text
Page 1:
  Детали

Page 2:
  1 | Вал
  2 | Корпус
```

---

# 20. Styles

Стили являются частью Semantic IR.

Например:

```kotlin
object Styles {

    val mainText = TextStyle(...)

    val tableText = TextStyle(...)

    val heading = TextStyle(...)

    val designation = TextStyle(...)

    val tableBorder = BorderStyle(...)
}
```

Report Definition должен говорить:

```kotlin
text(
    value = item.name,
    style = Styles.tableText
)
```

а не постоянно задавать:

```kotlin
font = ...
fontSize = ...
lineHeight = ...
```

---

# 21. Единицы измерения и геометрия

Layout Engine должен работать в физических единицах.

Например:

```kotlin
30.mm
5.pt
```

а не:

```kotlin
30.px
```

Базовые типы:

```kotlin
@JvmInline
value class Length(val raw: Long) // 1/100 мм, fixed-point
```

и:

```kotlin
data class Point(
    val x: Length,
    val y: Length
)

data class Size(
    val width: Length,
    val height: Length
)

data class Rect(
    val x: Length,
    val y: Length,
    val width: Length,
    val height: Length
)

data class Insets(
    val top: Length,
    val right: Length,
    val bottom: Length,
    val left: Length
)
```

**Решение:** `Length` — fixed-point `Long`, шаг 1/100 мм (10 мкм). Обоснование:

- детерминизм (§28) не зависит от JVM/платформы, в отличие от `Double`;
- точности достаточно для инженерного черчения (ЕСКД допуски крупнее 10 мкм);
- арифметика (`+`, `-`, `*`, `/`, round) реализуется один раз в `Length` и переиспользуется везде.

`Length`, `Point`, `Size`, `Rect`, `Insets` образуют отдельный модуль `report-geometry` без зависимостей — на него опираются и `report-ir` (Semantic IR), и `report-layout-ir` (Layout IR), не зависящие друг от друга напрямую (см. §23).

---

# 22. Text Measurement

Изолированный компонент:

```kotlin
interface TextMeasurer {

    fun measure(
        text: String,
        style: TextStyle,
        maxWidth: Length
    ): TextMeasurement
}
```

Результат должен содержать как минимум:

```text
width
height
line count
```

В дальнейшем могут понадобиться:

- baseline;
- ascent/descent;
- line metrics;
- glyph positions;
- информация о переносах.

Это один из наиболее технически сложных компонентов системы.

**Решение:** реализация `TextMeasurer` — поверх PDFBox font metrics (`PDFont.getStringWidth`, embedded TTF). Причина: единственный источник истины для ширины/переноса текста должен совпадать между Layout Engine и PDF Renderer — иначе wrapping, посчитанный на layout-стадии, разойдётся с фактическим рендером в PDF.

Отсюда — общий **font registry**: шрифты (embedded TTF, `PDFont` инстансы) загружаются один раз в `report-layout`, каждому шрифту присваивается id. Layout IR (`TextStyle`/`PositionedText`) несёт ссылку на этот id, а `report-render-pdf` резолвит тот же `PDFont` по id вместо повторной загрузки. Это гарантирует, что PDF renderer рисует именно то, что измерил layout engine.

`report-layout` в связи с этим зависит от PDFBox уже на стадии measurement, не только в рендере (см. §23, §24).

---

# 23. Архитектура модулей

Для первого варианта достаточно modular monolith.

```text
report-engine/
│
├── report-api/
│
├── report-data/
│
├── report-geometry/
│
├── report-ir/
│   └── frames/      ← FrameSpec-пресеты, §34 (Kotlin, не отдельный модуль)
│
├── report-layout-ir/
│
├── report-layout/
│
├── report-render-pdf/
├── report-render-svg/
├── report-render-xlsx/
│
└── reports/
    ├── specification/
    ├── statement/
    └── ...
```

## `report-api`

Контракт взаимодействия с PDM:

```text
PDM REST API
      ↓
report-api
```

Здесь:

- REST DTO;
- API client;
- API versions.

---

## `report-data`

Преобразует API DTO в удобные для отчётов данные:

```text
API DTO
   ↓
Report Data
```

Здесь могут находиться:

- mapping;
- aggregation;
- normalization;
- подготовка данных.

---

## `report-geometry`

Базовый модуль без зависимостей.

Содержит:

- `Length` (fixed-point `Long`, 1/100 мм);
- `Point` / `Size` / `Rect` / `Insets`;
- геометрическую арифметику.

На него опираются `report-ir` и `report-layout-ir` — независимо друг от друга.

---

## `report-ir`

Главный модуль смысловой части архитектуры.

Содержит:

- Semantic IR;
- styles;
- constraints;
- `FrameSpec`/`StampSpec` и готовые пресеты рамок (`frames/`, §34) — не
  отдельный модуль, обычный Kotlin-пакет внутри `report-ir`, т.к. пресеты —
  это данные Semantic IR, а не layout-логика;
- DSL infrastructure.

Зависит только от `report-geometry`. Зависимостей на PDF/Excel/Layout IR здесь быть не должно.

---

## `report-layout-ir`

Физическое представление документа — результат Layout Engine.

Содержит:

- `Page`, `PositionedText`, `Line`, `Rectangle`, `PositionedImage`;
- font registry references (id шрифта → `PDFont`, см. §22).

Зависит только от `report-geometry`. **Не зависит от `report-ir`** — так рендеры (§12) остаются полностью изолированы от семантики документа, как и требует §12.

---

## `report-layout`

Содержит:

- measurement (`TextMeasurer` на PDFBox, font registry — см. §22);
- layout;
- table layout;
- pagination;
- page templates (выбор `FrameSpec` по типу страницы — §16, §34);
- flow;
- placement;
- overflow diagnostics (см. §33).

```text
report-layout
      ↓
  report-ir
      ↓
  report-layout-ir
      ↓
  PDFBox (measurement)
```

Это единственный модуль, которому нужны сразу оба IR.

---

## `report-render-pdf`

```text
Layout IR → PDF
```

Зависит от:

```text
report-layout-ir
PDF library (PDFBox)
```

Резолвит шрифты по id из font registry, заполненного в `report-layout` (§22) — не грузит шрифты заново.

---

## `report-render-svg`

```text
Layout IR → SVG
```

Зависит только от `report-layout-ir`.

---

## `report-render-xlsx`

Отдельная реализация:

```text
Semantic IR → XLSX
```

или, если понадобится, через специализированный intermediate representation для Excel.

---

# 24. Зависимости

Главное правило:

```text
PDM
 ↓
API
 ↓
Report Builder
 ↓
Semantic IR
 ↓
Layout
 ↓
Layout IR
 ↓
Renderer
```

Но зависимости не должны идти обратно.

Например:

```text
report-ir
```

не должен зависеть от:

```text
report-render-pdf
report-render-xlsx
report-layout-ir
PDM
Ktor
Exposed
```

Аналогично:

```text
report-layout-ir
```

не должен зависеть от:

```text
report-ir
```

Оба модуля опираются только на `report-geometry` (§23), но не друг на друга — это и есть архитектурный boundary между «смыслом» и «физическим расположением» на уровне сборки, а не только на уровне типов.

---

# 25. DSL

DSL должен быть декларативным.

Хорошо:

```kotlin
document {
    pageFormat = A4

    eskdFrame()

    specification {
        columns {
            position()
            designation()
            name()
            quantity()
        }

        group("Детали") {
            rows(data.parts)
        }
    }
}
```

Плохо:

```kotlin
text(
    x = 37.mm,
    y = 142.mm,
    width = 80.mm
)
```

DSL описывает **структуру документа**.

Координаты вычисляет Layout Engine.

---

# 26. Аналогия с компилятором

Архитектуру удобно воспринимать как специализированный compiler pipeline:

| Генератор документов | Компилятор |
|---|---|
| Report DSL | Source code |
| Report Builder | Frontend |
| Semantic IR | Semantic AST / IR |
| Layout Engine | Backend / code generation |
| Layout IR | Machine-oriented IR |
| PDF/SVG | Target |
| XLSX | Другой target |

Главная идея этой аналогии:

> **IR позволяет разделить смысл программы и её физическое представление.**

Именно это нам нужно для документов.

---

# 27. Тестирование

## 27.1 Builder tests

Проверяем:

```text
Report Data → Semantic IR
```

Например:

```text
3 items
 ↓
3 rows
 ↓
2 groups
```

---

## 27.2 Layout tests

Проверяем:

```text
Semantic IR → Layout IR
```

Например:

- количество страниц;
- начало группы;
- высота строк;
- перенос текста;
- page breaks;
- повторение заголовков;
- первая/последующая страница;
- положение title block.

---

## 27.3 SVG visual tests

Можно получать:

```text
Semantic IR
    ↓
Layout
    ↓
SVG
    ↓
snapshot
```

Это один из самых полезных тестов для layout engine.

---

## 27.4 PDF visual tests

Для PDF:

```text
PDF
 ↓
Rasterize
 ↓
PNG
 ↓
Pixel comparison
```

Так можно ловить:

- разрывы линий;
- смещения;
- неправильные границы;
- изменение размеров;
- проблемы с wrapping.

---

# 28. Determinism

Генерация должна быть детерминированной:

```text
same data
+
same report definition
+
same engine version
=
same Layout IR
```

Следовательно, Layout IR желательно уметь сравнивать структурно.

Например:

```text
Document #123
Engine 0.1.0
Page 1
  Text(...)
  Line(...)
  Rectangle(...)
```

Это сильно упростит regression testing.

---

# 29. MVP

Первый MVP не должен пытаться заменить FastReport целиком.

### Semantic IR

- Document
- Text
- Table
- Column
- Group
- Row
- Cell
- Styles
- Constraints

### Layout

- A4
- margins
- frame
- title block
- text wrapping
- table layout
- row height
- pagination
- repeated headers
- first/continuation page

### Output

1. SVG
2. PDF
3. XLSX — после стабилизации PDF/layout

---

# 30. Что сознательно не входит в v0.1 / v0.2

Не делать:

- visual designer;
- web designer;
- drag-and-drop;
- универсальный report builder;
- собственный scripting language;
- Word renderer;
- сложные charts;
- CSS-like layout system;
- универсальный form engine;
- пользовательское редактирование шаблонов;
- микросервисную архитектуру;
- SQL integration с PDM;
- хранение `FrameSpec`-пресетов во внешних данных (JSON/БД) — сознательно
  оставлено Kotlin-коду, см. §34.

Особенно важно:

> **Не пытаться сделать новый FastReport.**

Система должна быть существенно уже и специализированнее.

---

# 31. Итоговая архитектурная формула

Главная архитектурная цепочка:

```text
PDM
 │
 │ REST
 ▼
Report Data
 │
 ▼
Report Builder
 │
 │ produces
 ▼
┌─────────────────────┐
│    Semantic IR      │
│                     │
│ смысл документа     │
│ структура           │
│ стили               │
│ ограничения         │
└──────────┬──────────┘
           │
           │ consumes
           ▼
┌─────────────────────┐
│    Layout Engine    │
│                     │
│ measurement         │
│ layout              │
│ pagination          │
│ page templates      │
└──────────┬──────────┘
           │
           │ produces
           ▼
┌─────────────────────┐
│      Layout IR      │
│                     │
│ страницы            │
│ координаты          │
│ размеры             │
│ линии               │
│ текст                │
└──────────┬──────────┘
           │
      ┌────┼────┐
      ▼    ▼    ▼
     PDF  SVG  XLSX
```

При этом:

**Report Builder** — это код, который строит IR.

**Semantic IR** — стабильный контракт между описанием отчёта и layout.

**Layout Engine** — превращает смысловую структуру в физическое расположение.

**Layout IR** — стабильный контракт между layout и конкретным форматом вывода.

**Renderer** — только материализует Layout IR в конкретный формат.

---

# 32. Главный архитектурный принцип

Система должна разделять три разных вопроса:

### 1. Что должно быть в документе?

`Report Builder`

### 2. Как это должно физически разместиться?

`Layout Engine`

### 3. Как записать уже размещённый результат в конкретный формат?

`Renderer`

И два ключевых IR:

```text
Semantic IR
    ↓
Layout IR
```

являются границами между этими задачами.

Именно это позволяет избежать главной проблемы исходной системы, где SQL, Pascal/VBA/C# scripts, FastReport Designer и формат вывода постепенно смешали **данные, смысл документа, layout и rendering в один ком**.

---

# 33. Overflow policy

Возможна ситуация, когда контент физически не помещается в отведённое пространство:

- ячейка/слово шире доступной ширины колонки;
- блок с `keepTogether = true` (§19) больше высоты страницы;
- группа с `keepWithNext` не может получить даже заголовок + одну строку.

**Решение:** hard error, а не молчаливое искажение документа.

Layout Engine на таком элементе кидает structured exception, а не обрезает/сжимает/игнорирует constraint. Диагностика обязана содержать:

```text
путь до элемента (Document → Table → Group "Детали" → Row 7 → Cell "Наименование")
какой constraint/размер нарушен
номер страницы, на которой обнаружено
```

Обоснование:

- инженерная документация не терпит тихих искажений (обрезанный текст в ЕСКД-документе — это дефект, а не деградация);
- explicit fail-fast сразу указывает на проблему в Report Builder / данных / стилях, вместо непредсказуемого визуального артефакта, замеченного постфактум;
- совместимо с §28 (determinism) — ошибка тоже часть детерминированного поведения: одни и те же данные either дают один и тот же Layout IR, either одну и ту же ошибку.

Best-effort режим (авто-сжатие шрифта, принудительный перенос через дефис не по правилам языка и т.п.) сознательно не входит в v0.1 — при необходимости может быть добавлен позже как явно включаемая опция, а не поведение по умолчанию.

---

# 34. Рамка и штамп: `FrameSpec` / `StampSpec`

*(новое в 0.2)*

## 34.1 Проблема

До 0.2 рамка/штамп существовали в двух не связанных архитектурно местах:

- `PageSetup.frameStyle` (`report-ir/PageSetup.kt`) — просто `BorderStyle`,
  ничего не знает про ячейки;
- `TitleBlockSpec(designation, name, sheetsTotal)` — несёт только dynamic
  значения; подписи граф («Обозначение», «Наименование», «Масса», ...) и
  геометрия ячеек штампа зашиты как константы прямо в
  `LayoutEngine.drawTitleBlock()` (`report-layout`).

Это нарушает §7: Layout Engine знает не только «где», но и «что написано» —
constant-текст рамки живёт в слое, которому положено оперировать только
координатами. Плюс переиспользовать рамку для другого отчёта (`statement`,
не только `specification`) означало копировать layout-код, не подключать
готовый набор.

## 34.2 Модель

`FrameSpec` — элемент Semantic IR (`report-ir`), не layout-примитив:

```kotlin
data class FrameSpec(
    val cells: List<FrameCell>
)

sealed interface FrameCell {
    val rect: FrameCellRect   // положение ВНУТРИ рамки, не абсолютные x/y страницы
    val style: TextStyle

    data class Constant(
        override val rect: FrameCellRect,
        override val style: TextStyle,
        val text: String                 // "Обозначение", "Масса", "Масштаб", ...
    ) : FrameCell

    data class Dynamic(
        override val rect: FrameCellRect,
        override val style: TextStyle,
        val field: FrameField             // ссылка на поле документа/данных
    ) : FrameCell
}

enum class FrameField { DESIGNATION, NAME, MASS, SCALE, SHEET_NUMBER, SHEETS_TOTAL, /* ... */ }
```

`FrameCellRect` — размер и положение ячейки относительно угла рамки в
физических единицах (`Length`, §21), не абсолютные координаты страницы: как
и обычная `IrCell` в таблице, `FrameSpec` не должен знать номер страницы —
это разрешает Layout Engine, ровно по правилу §7.

Constant/Dynamic — та же граница, что и во всём остальном документе:
constant — то, что определяет Report Builder (шаблон рамки), dynamic — то,
что приходит из `Report Data` конкретного документа.

## 34.3 Решение: первая и последующая страницы — независимые пресеты

`firstPageFrame` и `continuationFrame` — два отдельных `FrameSpec`, не один
объект с флагами видимости на ячейках.

**Почему:** у первой и последующей страницы штампа ЕСКД реально разный набор
граф (первая несёт полный штамп с массой/масштабом/подписями, последующая —
только сокращённый штамп с обозначением и номером листа). Флаги видимости на
общей модели("`showOn = FIRST_ONLY`") давали бы меньше дублирования
разметки, но заводили бы в `report-ir` условную логику про то, какая
страница что показывает — а Semantic IR по всей архитектуре (§7, §16)
специально НЕ знает про номера страниц и разрывы. Два независимых пресета
держат это правило без исключений: каждый `FrameSpec` самодостаточен,
Layout Engine просто берёт нужный по типу страницы (§16), как и для любого
другого `PageTemplate`.

Плата — дублирование одинаковых ячеек (например, графа "Обозначение" есть в
обоих пресетах) между двумя пресетами. Это осознанный trade-off: явное
дублирование данных дешевле, чем скрытая условная логика внутри модели,
которую §7 прямо запрещает.

## 34.4 Решение: пресеты — Kotlin-код, не внешние данные

Готовые наборы (`firstPageA4`, `continuationA4`, `firstPageA3`, ...) —
обычные Kotlin `val`/`object` в `report-ir` (пакет `frames/`, см. §23), не
JSON/YAML/БД.

**Почему:**

- движок сейчас нигде не парсит внешние декларативные форматы (ни для
  отчётов, ни для PDM-контракта — см. known-gaps.md, `report-api` без
  реализации HTTP-клиента) — заводить serialization слой только ради рамок
  добавляет инфраструктуру без потребителя;
- типобезопасность: `FrameField.DESIGNATION` — enum, опечатка не
  скомпилируется; строковый ключ во внешних данных упал бы в рантайме или
  тихо (см. известный trade-off с `fontFamily`, design-decisions.md);
- пресетов мало и меняются они редко (набор форматов ЕСКД конечен) — как
  раз случай, где "код как данные" даёт IDE-навигацию и compile-time
  проверку без реальной потери гибкости;
- согласуется с §5: Builder — код, IR — данные; сам пресет — это код,
  который ПОРОЖДАЕТ `FrameSpec`-данные (тот же паттерн, что и весь Report
  Builder).

Если в будущем понадобится runtime-редактирование рамок без пересборки —
это отдельное решение, требующее serialization слоя, сознательно не
делается сейчас (см. §30).

## 34.5 Как это стыкуется с §16

`PageTemplate.first`/`PageTemplate.continuation` теперь конкретно означают
«какой `FrameSpec` использовать» — не абстрактную декларацию. Layout Engine
выбирает пресет по номеру страницы один раз, в точке построения
`PageTemplate`, вместо `isFirstPage()`-ветки внутри `renderPages()`.
Renderer, как и раньше (§12), про это ничего не знает — видит только
результат: `Rectangle` + `PositionedText` в Layout IR.
