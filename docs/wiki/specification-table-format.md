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

**Где живут константы.** Структура и стиль таблицы (ширины колонок, `stick`, тексты и стили шапки, высота
строки 8 мм, число пустых строк до/после заголовка, колонка заголовка группы, заполнение страницы, цепочка
заголовка, `bind` каждой ячейки строки) и ПРАВИЛА данных (заголовки и порядок групп `groupBy`, сквозная нумерация
позиций `computed.position`, формат количества `format` + `cases`: материалы `0.##` с запятой, остальные целое HALF_UP,
правило «единица только у материалов») описаны в блоке `body` (`type: flow`, секция `table:`) файла
`report-ir/src/main/resources/templates/gost-spec.yaml`. Кодом остаются только данные: `Specification.kt` собирает
`DataContext` и вызывает `FlowTables.build`, `SpecificationDataContext.kt` отдаёт сырые поля записи. Алгоритм (измерение, перенос,
пагинация, дозаполнение) остаётся кодом `report-layout`. Справка по YAML: `template-yaml.md`, раздел
«Таблица потока».

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

**`IrTable`**:
```kotlin
data class IrTable(
    val columns: List<IrColumn>,
    val header: IrTableHeader?,
    val content: List<IrTableElement>,
    val style: TableStyle = ...,
    val rowHeight: Length? = null,
    val groupTitle: IrGroupTitle = IrGroupTitle(),   // заменил groupTitleColumn
    val fillBlank: Boolean = true                    // только при rowHeight != null
)
```

- `rowHeight = null` → автовысота (старое поведение, обратная совместимость)
- `rowHeight = 8.mm` → **фиксированная** высота, word-wrap в физические строки
- `groupTitle.column = "name"` → заголовок группы помещается **в одну колонку**
  (не full-width спан, это `column = null`), остальные колонки остаются пусты;
  `spacerBefore` / `spacerAfter` (по умолчанию 2 / 1), `style`, `align`, `keepWithRows` (цепочка заголовка)
- `fillBlank` — дозаполнять страницу пустыми строками (`fill: blank` в YAML)
- `IrTableHeader.repeat` — шапка на каждой странице (по умолчанию да); `false`: только на первой,
  остальные страницы начинаются у верхнего поля
- Текст шапки хранится только в `IrTableHeader.cells`; поле `IrColumn.header` удалено (дубль)
- Эту структуру собирает `FlowTables.build(spec, schema, rows)` из YAML-описания и записей `item` (правила в YAML)

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

При `table.rowHeight != null` (в `buildBlocks`; числа 2 и 1 это `groupTitle.spacerBefore` / `spacerAfter`):
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
2. После основного цикла выкладки, если `table.rowHeight != null` и `table.fillBlank`:
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
// Specification.kt: только данные, правила таблицы в YAML
val dataContext = data.toDataContext()
table(FlowTables.build(GostSpecTemplate.flowTable, dataContext.schema, data.itemRecords(), GostSpecTemplate.flowTablePath))
```

Запись строки `item` (схема объявлена адаптером `toDataContext()`) несёт сырые типизированные поля: `designation` String
(нет значения = пусто), `name` String, `kind` Enum, `quantity` Decimal, `unit` String (нет значения = пусто). Позицию,
группы и тексты считает YAML (`groupBy`, `computed`, `cases`, `format`), ячейки с отсутствующим значением помечены
`optional: true`.

Паритет со старым кодом проверяет `SpecificationTableParityTest`: `IrTable` из YAML равен таблице, которую строил
прежний код (`LegacySpecificationTable`, только в тестах; кроме `IrColumn.header`, поля больше нет).
