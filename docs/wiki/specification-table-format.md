# Форматирование таблицы спецификации (ЕСКД §1)

Таблица спецификации состоит из 4 блоков (в порядке ГОСТ Р 2.106-2019 §1):
1. **Сборочные единицы** (СЕ)
2. **Детали**
3. **Стандартные изделия**
4. **Материалы**

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
1. Для каждой ячейки → `textMeasurer.wrapIntoLines(cell.text, columnWidth)` 
   (word-wrap по словам уже встроен в `PdfBoxTextMeasurer`)
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
2. **Text с паддингом**: `TextAlign.LEFT` → левый паддинг 1mm (иначе текст спадает
   на границу). `TextAlign.CENTER` → текст центрируется по ширине колонки.
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
  1x blank (спэйсер after) [keepWithNext = element.constraints.keepTogether]
    ↓
  data rows (split via splitRowIntoPhysicalRows) [keepWithNext = ...]
```

Спейсеры **unconditionally** связаны с заголовком (не переносятся отдельно);
пустая строка после и сами данные — подчиняются `keepTogether` группы.

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
    
    // 1. Сборочные единицы
    val assemblyUnits = items.filter { it.kind == ItemKind.ASSEMBLY }
    if (assemblyUnits.isNotEmpty()) {
        group("Сборочные единицы") { /* ... */ }
    }
    
    // 2. Детали
    val parts = items.filter { it.kind == ItemKind.PART }
    if (parts.isNotEmpty()) {
        group("Детали") { /* ... */ }
    }
    
    // 3. Стандартные изделия
    val standards = items.filter { it.kind == ItemKind.STANDARD }
    if (standards.isNotEmpty()) {
        group("Стандартные изделия") { /* ... */ }
    }
    
    // 4. Материалы
    val materials = items.filter { it.kind == ItemKind.MATERIAL }
    if (materials.isNotEmpty()) {
        group("Материалы") { /* ... */ }
    }
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
- 4 блока в правильном порядке (ГОСТ соответствие)
- Сквозная нумерация позиций через все 4 блока
- Пропуск пустого блока (если материалов нет, блок "Материалы" не появляется)

### `TableMeasurementTest` / `LayoutEngineTest`
- `splitRowIntoPhysicalRows`: переполнение → N физических строк, каждая
  высотой ровно `rowHeight`
- Границы: каждая строка → `Rectangle` на каждую колонку (thin border)
- Спейсеры: 2 пустые до заголовка + 1 после (проверка через count `BorderedRowBlock`)
- Дозаполнение: маленький формат → нижняя часть всё равно заполнена бордерными
  пустыми строками до `contentBottom`
- Выравнивание: `TextAlign.LEFT` паддинг, `TextAlign.CENTER` центрирование,
  координаты меняются в зависимости от ширины

### Golden snapshots
- `specification.svg` — полный документ с 4 блоками, все визуальные детали
  (границы, заголовки, подчёркивание, выравнивание)
- `simple-table.svg` / `.png` — синтетический italic на горизонтальном и
  вертикальном тексте

## Что НЕ входит (известные ограничения)

- **Continuation-страницы** (§34.3 ЕСКД) — штамп/рамка на 2+ страницах не
  рисуются (на них просто пусто). Спецификация дозаполняется бордерными
  строками, но рамка остаётся одноразовой (стр. 1 только).
- **Italic-шрифт** — собственного italic-начертания GOST Type B нет
  (лицензионно не получилось). Вместо этого синтетический наклон (shear
  matrix в PDF, `font-style` в SVG).
- **Strikethrough** — не реализован (не требуется спецификацией, но может
  потребоваться позже по аналогии с underline).
- **Индивидуальные border-стили** на ячейке — все границы тонкие (0.7pt),
  нет per-cell customization.
