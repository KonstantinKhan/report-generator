# Форматирование таблицы спецификации (ЕСКД §1)

Таблица спецификации состоит из 5 блоков (в порядке ГОСТ Р 2.106-2019 §1, как в коде
`Specification.kt`):
1. **Сборочные единицы** (СЕ, `ItemKind.ASSEMBLY`)
2. **Детали** (`PART`)
3. **Стандартные изделия** (`STANDARD`)
4. **Прочие изделия** (`OTHER`)
5. **Материалы** (`MATERIAL`)

Нумерация позиций ("Поз.") **сквозная через все 5 блоков**: 1, 2, 3 ... без сброса на границе
блока. Пустой блок (нет элементов своего вида) в документе не появляется — ни заголовка,
ни спейсеров.

**Единица измерения** (колонка "Примечание") выводится **только для материалов**
(`ItemKind.MATERIAL`, напр. "кг", "м"). У остальных видов `ItemDto.unit` игнорируется —
штучный учёт без единицы. В "Кол." единицу не пишем: колонка 10 мм, значение + единица
не помещаются в одну строку.

Каждый блок:
- Начинается с заголовка (курсив, 3.5мм, подчёркнут)
- Предваряется 2 пустыми строками (спэйсер *before*)
- Следуется 1 пустой строкой (спэйсер *after*)
- Содержит строки данных с фиксированной высотой 8мм

Все строки (заголовок, данные, пустые) имеют **тонкие границы** на каждой ячейке
(ГОСТ 2.303: 0.7pt ≈ 0.247mm, ~S/3 от основной линии).

## Архитектура реализации

### IR уровень (`report-ir`)

**`TextStyle`** — добавлено:
```kotlin
data class TextStyle(
    val fontFamily: String,
    val fontSizeMm: Double,
    val bold: Boolean = false,
    val italic: Boolean = false,    // ← new
    val underline: Boolean = false  // ← new
)
```

Оба поля `italic`/`underline` — семантические метки (не гарантируют наличие
шрифта в конкретной гарнитуре). Рендер будет синтетическим.

**`IrTable`** — добавлено:
```kotlin
data class IrTable(
    val columns: List<IrColumn>,
    val header: IrTableHeader? = null,
    val content: List<IrGroup>,
    val rowHeight: Length? = null,           // ← new
    val groupTitleColumn: String? = null,    // ← new
    // ... остальное
)
```

- `rowHeight = null` → автовысота (старое поведение, обратная совместимость)
- `rowHeight = 8.mm` → **фиксированная** высота, word-wrap в физические строки
- `groupTitleColumn = "name"` → заголовок группы помещается **в одну колонку**
  (не full-width спан), остальные колонки остаются пусты

**Стили**:
```kotlin
object Styles {
    val groupHeader = TextStyle(
        fontFamily = FontFamilies.GOST_TYPE_B,
        fontSizeMm = 3.5,
        italic = true,      // синтетический наклон
        underline = true    // геометрический подчёркивающий Line элемент
    )
    val tableBorderThin = BorderStyle(widthPt = 0.7)
}
```

### Layout уровень (`report-layout`)

#### Разделение на физические строки

**`splitRowIntoPhysicalRows(row, columns, offsets, textMeasurer, rowHeight)`**:
1. Для каждой ячейки → `textMeasurer.measure(cell.text, style, cellTextWidth(columnWidth), breakLongWords = true)`
   (word-wrap по словам внутри `PdfBoxTextMeasurer`). Доступная ширина
   `cellTextWidth` = ширина колонки минус `FRAME_CELL_PADDING` (1 мм) **с обеих сторон**,
   для любого выравнивания: LEFT-текст рисуется с отступом 1 мм от левой границы, и без
   правого отступа строка упиралась бы в правую границу. Пустые "слова" от двойных
   пробелов пропускаются. Слово шире доступной ширины (длинное обозначение без пробелов)
   **рвётся по символам** (без дефиса), строка никогда не выходит за границу ячейки.
   `breakLongWords` включён только для тела таблицы; ячейки рамки/штампа не ломают слова
   (напр. "Изм" в узкой ячейке штампа)
2. `physicalRowCount = max(1, cells.maxOf { lines.size })`
3. Итого `physicalRowCount` объектов `MeasuredRow`, каждый высотой ровно `rowHeight`
4. На физической строке `k`:
   - Для колонок, где `lines.size > k`: строка `lines[k]`
   - Для колонок, где `lines.size ≤ k`: пустая строка `""`
   - Сбоку-адаптивные столбцы типа "Поз." (один символ) имеют `lines.size = 1`,
     поэтому на `k ≥ 1` автоматически становятся пусты

#### Границы ячеек

**`drawBorderedRow(block: BorderedRowBlock, y, ...)`** — новая функция рендера:
1. **Для каждой колонки** отдельный `Rectangle(rect, styleBorderThin)`
2. **Text с паддингом**: `TextAlign.LEFT` → левый паддинг 1 мм (иначе текст спадает
   на границу), ширина переноса `cellTextWidth` (минус 1 мм справа).
   `TextAlign.CENTER` → текст центрируется по ширине колонки, перенос по той же `cellTextWidth`.
3. Вертикальное расположение текста в строке: baseline на 80% высоты
   (как в горизонтальном тексте таблицы)

**`underline = true`** — добавляется геометрический `Line` элемент под текстом:
```kotlin
Line(from = (x, y + lineHeight), to = (x + lineWidth, y + lineHeight), 
     style = LineStyle(width = 0.7pt))
```

Это НЕ трогает рендер (§12 архитектуры: рендер не решает "как"), а только
Layout IR: добавляет в `PageElement` список дополнительный `Line` рядом с
`PositionedText`.

#### Блоки и спейсеры

При `table.rowHeight != null` (в `buildBlocks`):
```
группа:
  2x blank (спэйсер before) [всегда keepWithNext = true]
    ↓
  GROUP_HEADER [всегда keepWithNext = true]
    ↓
  1x blank (спэйсер after) [keepWithNext = true, если у группы есть строки данных]
    ↓
  data rows (split via splitRowIntoPhysicalRows) [keepWithNext = element.constraints.keepTogether]
```

Цепочка спейсеры + заголовок + пустая строка после **unconditionally** связана с
**первой физической строкой данных**: заголовок группы не остаётся "сиротой" внизу
страницы (раньше цепочка обрывалась на пустой строке после заголовка). Остальные строки
группы привязываются друг к другу только при `keepTogether` — длинная группа не
склеивается в один неделимый блок. Группа без строк данных не привязывается к
следующей группе (в спецификации такие группы не создаются). Высота цепочки при
одностроковом заголовке: 4 строки + 1 строка данных = 5 x 8 мм = 40 мм, что много меньше
высоты любой страницы.

#### Дозаполнение страницы

В `renderPages`:
1. Отслеживаем `pageFinalY: List<Length>` — финальная Y-координата закрытой
   каждой страницы
2. После основного цикла выкладки, если `table.rowHeight != null`:
   ```kotlin
   for (page in pages) {
       val remaining = contentBottom(page) - pageFinalY[page]
       val blankRowCount = (remaining / rowHeight).toInt()
       for (i in 0..blankRowCount) {
           add measureBlankRow() + drawBorderedRow()
       }
   }
   ```
3. Если контента ноль вообще (пустой документ или все блоки пусты) —
   `pageFinalY[0] = contentTop`, дозаполняется вся страница

### Renderer уровень

#### PDF (`report-render-pdf`)

**Синтетический italic** (no italic GOST Type B font loaded — см.
[fonts-and-licensing.md](fonts-and-licensing.md)):

```kotlin
private const val ITALIC_SHEAR = 0.2f  // ~11° slant, tan ≈ 0.2

fun italicMatrix(x: Float, y: Float, italic: Boolean): Matrix =
    if (italic) Matrix(1f, 0f, ITALIC_SHEAR, 1f, x, y)
    else Matrix.getTranslateInstance(x, y)
```

Применяется в `renderHorizontalText` через `stream.setTextMatrix(italicMatrix(...))`.
Горизонтальный сдвиг в матрице текста `[a b c d e f]` компонент `c` — это
косой сдвиг: `(px, py) → (px + c*py + e, d*py + f)`. При `c = 0.2`, текст
выше baseline наклоняется влево, ниже — вправо (стандартный наклон 11°).

#### SVG (`report-render-svg`)

```kotlin
val fontStyle = if (text.style.italic) """ font-style="italic"""" else ""
return """<text ... $fontStyle>${text.text}</text>"""
```

SVG имеет встроенную поддержку `font-style="italic"`, браузер сам применяет
синтетический наклон если italic-вариант не найден.

### Report Builder (`reports/specification`)

```kotlin
table(rowHeight = 8.mm, groupTitleColumn = "name") {
    val items = data.items
    
    // 1. Сборочные единицы  2. Детали  3. Стандартные изделия  4. Прочие изделия  5. Материалы
    // — каждый через groupIfNotEmpty(title, items.filter { it.kind == ... }),
    // позиция nextPosition += 1 сквозная
}
```

**Выравнивание по колонкам**:
```kotlin
row(sequenceNumber, designation, name, qty, note) {
    cell(designation, align = TextAlign.LEFT)      // Обозначение
    cell(name, align = TextAlign.LEFT)              // Наименование
    cell(sequenceNumber, align = TextAlign.CENTER)  // Поз.
    cell(qty, align = TextAlign.CENTER)             // Кол.
    cell(note, align = TextAlign.CENTER)            // Примечание
    // Формат / Зона: TextAlign.CENTER (дефолт)
}
```

## Тестовое покрытие

### `SpecificationBuilderTest`
- 5 блоков в правильном порядке (ГОСТ соответствие)
- Сквозная нумерация позиций через все 5 блоков
- Пропуск пустого блока (если материалов нет, блок "Материалы" не появляется)
- Единица в "Примечании" только у материалов, у остальных видов пусто даже если `unit` задан

### `TableMeasurementTest` / `LayoutEngineTest`
- `splitRowIntoPhysicalRows`: переполнение → N физических строк, каждая
  высотой ровно `rowHeight`
- Границы: каждая строка → `Rectangle` на каждую колонку (thin border)
- Спейсеры: 2 пустые до заголовка + 1 после (проверка через count `BorderedRowBlock`)
- Дозаполнение: маленький формат → нижняя часть всё равно заполнена бордерными
  пустыми строками до `contentBottom`
- Перенос: ширина = колонка минус 1 мм с обеих сторон (LEFT и CENTER); слово шире ширины
  рвётся по символам; двойные пробелы не дают пустых строк
- Сирота: заголовок группы + спейсеры переезжают на следующую страницу вместе с первой
  строкой данных, если она не помещается
- Выравнивание: `TextAlign.LEFT` паддинг, `TextAlign.CENTER` центрирование,
  координаты меняются в зависимости от ширины

### Golden snapshots
- `StaticBlocksGoldenTest` (`reports/specification/src/test/resources/golden/*.txt`) —
  текстовые дампы раскладки; фикстуры `spec-group-boundary` (граница группы у низа страницы)
  и `spec-all-kinds-long-designation` (5 блоков, длинное обозначение, единицы) покрывают
  поведение переноса/сироты/единиц
- `specification.svg` — полный документ с 5 блоками, все визуальные детали
  (границы, заголовки, подчёркивание, выравнивание)
- `simple-table.svg` / `.png` — синтетический italic на горизонтальном и
  вертикальном тексте

## Multi-row item layout

Когда элемент (название, обозначение) переносится на несколько физических строк (word-wrap), служебные колонки размещаются избирательно:

- **Первая строка**: формат, зона, позиция (служебная информация в начале)
- **Последняя строка**: количество, примечания (итоговая информация в конце)
- **Все строки**: основной текст (обозначение, название)

Реализация через флаги в `IrColumn`:
```kotlin
data class IrColumn(
    val id: String,
    val width: Length,
    val header: String? = null,
    val stickToFirstRow: Boolean = false,  // формат, зона, позиция
    val stickToLastRow: Boolean = false    // количество, примечания
)
```

В `splitRowIntoPhysicalRows()` для каждой физической строки проверяется флаг и подставляется текст либо на первой строке, либо на последней, либо на соответствующей линии основного контента.

## Continuation-страницы

Полностью реализованы (§34.3 ЕСКД):
- На странице 2+ рисуются: frame rectangle, left margin frame, continuation stamp
- Continuation stamp (185×15mm): 3 строки header (2 пустые + 1 с "Изм"/"Лист"/№ докум./Подп./Дата + номер листа справа)
- Последняя полная фiller-строка на каждой странице рисуется БЕЗ нижней границы → визуально слита со штампом
- Оставшееся место (не кратное 8mm) добавляется к последней фiller-строке → нет пустого зазора перед штампом

## Представитель заказчика (ПЗ)

Два варианта спецификации:
- **С ПЗ** (default): включает таблицу представителя заказчика (mainTitleRightTable)
- **Без ПЗ**: исключает таблицу представителя заказчика

### Таблицы для ПЗ

#### specLeftTable (левый верхний угол, вертикальная)
- **Размер**: 12×120mm
- **Структура**: два столбца (5mm + 7mm), две строки (60mm + 60mm)
- **Содержимое**: 
  - Первая строка (y=0..60mm): "Справ. №" (первый столбец)
  - Вторая строка (y=60..120mm): (пусто)
- **Ориентация**: текст вертикальный (VERTICAL_BOTTOM_TO_TOP)
- **Привязка**: к левой границе рамки, внизу (BOTTOM_LEFT, BOTTOM_RIGHT)

#### mainTitleRightTable (правый верхний угол, горизонтальная)
- **Размер**: 120×22mm
- **Структура**: три колонки (14mm + 53mm + 53mm) в первой строке, одна колонка (120mm) во второй строке
- **Высоты строк**: 14mm (первая), 8mm (вторая)
- **Содержимое**: пусто (для заполнения ПЗ вручную)
- **Привязка**: к правой границе рамки, над основной надписью (BOTTOM_RIGHT с offset 40mm)
- **Границы**: толстые (BorderWeight.THICK)
- **Регистрация**: зарегистрирована в union-фильтре contentBottom() для правильного резервирования места

### API

```kotlin
// С представителем заказчика (default)
specification(data)
// или явно
specification(data, customerRepresentative = true)

// Без представителя заказчика
specification(data, customerRepresentative = false)
```

## Что НЕ входит (известные ограничения)

- **Italic-шрифт** — собственного italic-начертания GOST Type B нет
  (лицензионно не получилось). Вместо этого синтетический наклон (shear
  matrix в PDF, `font-style` в SVG).
- **Strikethrough** — не реализован (не требуется спецификацией, но может
  потребоваться позже по аналогии с underline).
- **Индивидуальные border-стили** на ячейке — все границы тонкие (0.7pt),
  нет per-cell customization.
