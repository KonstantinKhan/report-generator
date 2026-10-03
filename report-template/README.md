# report-template

Declarative page template: YAML -> model -> validation -> absolute geometry.
Stage 2/3: the whole specification sheet is described in `report-ir/src/main/resources/templates/gost-spec.yaml`:
the static blocks (stamps, margin tables; `FrameSpecs` content, `LayoutEngine` placement, page visibility,
content reservation) and the main (flow) table, a `type: flow` block with a `table:` section. The section says
WHAT is drawn (columns, header, group title and spacers, row cells as `bind: ${item.x}`, styles, fill, keep);
the layout algorithm (measure, wrap, pagination, blank fill) stays Kotlin in `report-layout`. Stage 3 moved the data
rules into the same section, as a closed set of structured operations (no string expressions, no scripting): `where`,
`sortBy`, `groupBy` (order, titles, skipEmpty, omit), `computed` (`sequence`, scope table|group), and per cell
`format` + `cases` (first matching predicate wins, the cell's own content is the default). Predicates are
`{field, eq|ne|in|isNull|notNull}` plus `and`/`or`/`not`. Stage 4 adds arithmetic `computed` fields
(`multiply|add|subtract|divide: [fields or numbers]`, exact BigDecimal / Long, explicit `scale` + `rounding`) and `totals`
(sum / count / min / max / avg per group or per table, drawn as bordered footer rows, see below). The code only supplies typed `item` records (raw values). `FlowTableSpec` (model) is turned into an `IrTable`
by `report-ir` `FlowTables`. Units in YAML: mm. Internally `Length` (1/100 mm).

Flow table validation (`TemplateValidator`): unknown keys, column ids and widths, header / row cells must cover
every column, group title column exists, binds are `${item.x}`, style names (aliases from `styles:` or the
consumer's names, pass `styleNames` to `TemplateLoader.load`), and the column widths must sum to the flow region
width = the template sheet's content width, exactly at 0.01 mm (no tolerance). `TemplateContract` (`FlowContract`)
checks the row binds, predicates, `sortBy`, `groupBy`, `cases` and `format` against the `item` root of the data
schema, with YAML paths: fields exist and are scalars, literals fit the type (enum values are schema members),
`groupBy` reads an Enum field and every enum value is in `order` or `omit` (nothing is dropped silently),
`computed` names do not clash with record fields (a sequence is Integer, arithmetic gets an inferred type:
Integer op Integer = Integer, anything with a Decimal = Decimal, divide = Decimal; cycles are errors) and are visible to
cells / `cases` / `totals`, arithmetic ones also to `sortBy`, never to `where` / `groupBy`. `totals` entries
(`{id, scope: group|table, agg, field, label, labelColumn, valueColumn, format, style, where, skipEmpty, scale, rounding}`)
read numeric fields and are checked for ids, columns, `format` against the result type, `scope: group` needing `groupBy`.
Order of shaping: where -> arithmetic computed -> sortBy (stable, strings natural + Russian collation, nulls last by
default) -> groupBy -> sequences -> totals. `FlowShaper.shapeTable` / `FlowCellRenderer` (`FlowData.kt`, `FlowArithmetic.kt`,
`FlowTotals.kt`) run it; `FlowTables.build(spec, schema, rows)` in `report-ir` makes the `IrTable` (totals become
`IrGroup.footer` / `IrTable.footer`, kept with their last data row by the layout). Reference: `docs/wiki/template-yaml.md`.
Not covered: nested and running totals, expressions beyond the closed arithmetic ops, totals over Date / String,
sorting by a sequence, nested or non-enum grouping.

```kotlin
val template = TemplateLoader.load(yamlText)            // parse + validate, throws TemplateException
val first = TemplateResolver.resolve(template, PageKind.FIRST)
first.blocks        // visible blocks, dependency order, absolute Rect + anchors + cells
first.flowRegion    // content area minus `reserves` blocks
```

Dependencies: `report-geometry` only (`Length`, `Rect`, `Point`, `PageFormat`, `Corner`, `placeOrigin`,
`resolveAnchor`). Layout depends on this module (via `report-ir`), never the other way round.
Placement math is geometry's `placeOrigin`; `resolveAnchor` (corner based, "inward" offset) is the same
formula, tests compare the two.

## YAML schema

```yaml
name: string
sheet:
  format: A4|A3|A2|A1|A0          # or width + height (mm); default A4
  orientation: portrait|landscape # portrait = short side is width, also for explicit sizes
  margins: {top, right, bottom, left}   # default 0; defines the content area
  anchors: {name: {x, y}}         # custom points from sheet top-left
root: {self, to, offset}          # where blocks without `attach` go; default topLeft -> sheet.topLeft
blocksets: {name: {params, ports, blocks}}
blocks: [block...]
```

Sheet anchors: 9 standard + `contentTopLeft|TopRight|BottomLeft|BottomRight` (corners of the
margin-reduced area) + custom.

### Block (common fields)

| field | meaning |
|---|---|
| `id` | required, `[A-Za-z0-9_-]`, unique per scope, not `sheet`/`self` |
| `type` | `frame` `rect` `table` `text` `flow`; omitted when `use` is given |
| `anchors` | custom points `{name: {x, y}}`, relative to the block's top-left |
| `attach` | single or per-axis form, see below; `self` defaults to `topLeft` |
| `when` | `first` `rest` `all` (default) |
| `reserves` | `true` = subtracted from the flow region (default false) |

`attach` places the block so its `self` anchor lands on `to` + `offset`. Offset is in sheet axes
(x right, y down), unlike `resolveAnchor` where positive means "inward" relative to the block corner
(conversion: flip the sign on RIGHT/BOTTOM block corners). Using the same corner nests the block into
the target; the opposite corner hangs it outside.

Per-axis form: x and y are placed independently, each with its own `self`, `to` and scalar `offset`
(only that axis' coordinate of the anchors counts; the axes may target different blocks):

```yaml
attach:
  x: {self: topLeft, to: a.topRight, offset: 3}
  y: {self: bottomLeft, to: c.bottomLeft, offset: -1}
```

The single form `{self, to, offset: {x, y}}` is sugar for both axes with the same `self`/`to`.
A block may only attach to a block visible on at least the same pages (`when`).
Attach graph must be acyclic (error lists the cycle).

Standard anchors on every block: `topLeft topCenter topRight middleLeft center middleRight
bottomLeft bottomCenter bottomRight`.

### Types

- `frame` / `rect`: `size: {width, height}`, `thickness`: mm, or `thin` (0.25) / `thick` (0.71);
  default `thick` for frame, `thin` for rect. thick/thin equal the engine's `BorderWeight` widths at
  1/100 mm resolution (`Styles.tableBorder` 2pt = 0.7056mm -> 0.71, `tableBorderThin` 0.7pt -> 0.25).
- `text`: `size`, one of `text` / `bind` (with optional `format`, `optional`), `align` (left|center|right), `rotate` (0|90|270, glyphs), `fontSize` (pt).
- `flow`: data-driven table region, paginated by the engine later. No `size` = fills the flow
  region (cannot be attached or referenced, cannot `reserves`). With `size` it is a normal box.
- `table`: `columns: [widths]`, `rows: [...]`, optional `rotate`, `borders`; size is derived.
  - fixed row: `{height, cells: [...]}`
  - generated: `{repeat: {count: N, row: {height, cells}}}` (expanded now) or
    `{repeat: {from: "doc.items", row: ...}}` (data-driven; modeled, contributes no rows yet)
  - cell: scalar (= text), `~` (empty) or `{text | bind, format, optional, span, rowSpan, rotate, align, fontSize, style,
    borders}`. `span` = columns, `rowSpan` = rows: the cell covers the rows below, whose lists then
    omit those columns. Every row must cover all columns exactly once (own spans + columns taken from
    above). Cells are resolved row-major.
  - `borders` (table default and per cell): `none|thin|thick` for all sides, or `{top, right, bottom,
    left}` (listed sides only). A missing side inherits cell -> table -> `thick`. Resolved per sheet side.
  - `style`: opaque key for the consumer's text style (report-ir maps `frameText`, `frameTextLarge`).
  - `rotate` (cell): text rotation counterclockwise, 90 = reads bottom to top.
  - `rotate` (table, 0|90|270, counterclockwise like the cell one): the table is defined unrotated (its own
    w x h grid); the block's bounding box is the rotated one (h x w for 90/270) and its top-left is the
    placement point. Mapping of an unrotated point: 90 -> (y, w-x), 270 -> (h-y, x). Standard anchors
    (block and `cell[r,c].*`) are recomputed from the rotated rects (`topLeft` = top-left in sheet axes);
    `col[i].*`, `row[j].*` and custom anchors are points that travel with the block (`col[0].left` of a
    table rotated 90 sits at the bottom of the block's left edge). Cell borders move with their sides.
    Resolved cell `rotate` = block rotate + cell rotate (mod 360, so 180 is possible). Only tables rotate
    (a text block's `rotate` is glyph rotation).
  - auto anchors (0-based): 9 standard, `col[i].left|right` (on the table top edge),
    `row[j].top|bottom` (on the table left edge), `cell[r,c].<9 standard>` (c = first column
    of the cell). Example: `stamp.cell[2,3].topLeft`, `stamp.col[1].left`.
- blockset instance: `{id, use: name, args: {param: value}}` (no `type`). Its anchors: 9
  standard (of the children's bounding box) + the definition's ports.

### Blockset definition

```yaml
blocksets:
  name:
    params: {w: 7, label: null}     # default; null = required
    ports: {tail: box.bottomLeft}   # exposed anchors: "childId.anchor"; names must not be standard
    blocks: [...]                   # attach to siblings or `self.topLeft` (instance origin)
```

`${param.x}` is substituted in numeric fields, `text` (block and cell) and instance `args` (not in `bind`: the path is static).
Definitions may nest; recursion is an error. Resolved children appear flat as `instance/child`
(nested: `a/b/c`); an instance's `when` is ANDed with the child's.

### Bind

`bind: "${doc.designation}"` - a single path with root `doc`, `page` or `item` (`${page.number}`,
`${page.total}`, `${item.name}`). No expressions; `text:` is always literal. Kept as written in the
resolved model (`ResolvedCell.bind`, `ResolvedBlock.bind`) together with `format` / `optional`.

Values come from a typed `DataContext` (`DataModel.kt`, `DataContext.kt`):

- `DataType`: `Str` (String), `Integer`, `Decimal` (BigDecimal), `Date` (LocalDate), `Bool`, `Enum(values)`,
  `ListOf(Record)`, `Record(fields)`. Only scalars can be bound; List/Record are there for later stages.
- `DataSchema`: tree of named typed fields under the roots `doc`, `page`, `item`; `lookup(path)`.
  `page.number` / `page.total` (Integer) are always declared; the layout engine overlays their values
  per page (`DataContext.withPage(number, total)`).
- `DataValue`: typed values. `DataContext`: `schema` + `get(path): DataValue?`; `MapDataContext`
  (nested records), `overlay(...)`. Builder DSL for adapters:
  `dataContext { doc { string("designation", "AB.001"); decimal("mass", BigDecimal("1.5")) } }`
  (`dataSchema { }` for the schema alone; a null value declares the field only).
- `DataYaml.parse/load`: plain nested YAML map -> context with an inferred schema (`3` Integer, `1.5`
  Decimal, `2026-01-31` Date, `true` Boolean, else String; tags `!str !int !decimal !date !bool` force a
  type; `~` = String field without value).

Default text of a value (`ValueFormatter.render`): String as is, Integer plain, Decimal
`toPlainString()` (no locale), Date ISO, Boolean `true|false`, Enum its name.

`format` (optional, only with `bind`): Decimal `{pattern: "0.##", locale: ru, rounding: HALF_UP}`
(`java.text.DecimalFormat` pattern, `pattern` required; locale `ru|en|de`, default none = dot; rounding
default HALF_UP, also HALF_EVEN HALF_DOWN UP DOWN FLOOR CEILING), Date `{pattern: "dd.MM.yyyy", locale}`
(`DateTimeFormatter`). Any other type: error. `optional: true` (only with `bind`): a missing value renders
empty; otherwise a missing value at layout time is an error (`Binding.render` -> IllegalStateException).

Contract: `TemplateContract.check(template, schema): List<TemplateError>` (`require` throws) collects every
bind (cells, text blocks, repeat rows, blockset definitions at `blocksets.<name>.blocks[...]`) and reports,
with YAML paths: unknown root/field (with a nearest-name suggestion), stepping through a scalar, non-scalar
leaf, `format` not fitting the type or with an invalid pattern. Unknown path is an error, never left as
`${...}` text. Values are checked at layout time (see `optional`).

Adapter example: `reports/specification/.../SpecificationDataContext.kt`
(`SpecificationData.toDataContext()`); the engine takes the context via `PageSetup.dataContext`
(`FrameBindings(designation, name)` is a ready-made one for the classic stamp).

### Flow region

`TemplateResolver.flowRegion(...)`: sheet content area minus blocks with `reserves: true` visible
on the page kind. Same rule as the layout engine's `contentBottom()`: only blocks that overlap the
content column horizontally and lie at or below the content top count; the nearest top edge wins
(union, not sum); only the bottom of the area is cut. `flowRegionFor(content, rects)` is the same on
bare rects (the engine passes `[contentTop, frame bottom)` there).

TODO (possible follow-up): a top case (blocks hanging from the top edge cutting the region from above).
Not implemented on purpose: the engine has none, and a reserving block at the very top currently
collapses the region (its top edge is the nearest one).

## Layout engine binding (report-ir / report-layout)

`PageSetup.staticTemplate` (default: `gost-spec.yaml`) + `StaticSlot` bind PageSetup fields to block ids
(`frame` = `stamp`, `continuationFrame` = `continuationStamp`, `leftMarginFrame` = `leftMargin`,
`specLeftTable` = `specLeft`, `mainTitleRightTable` = `mainTitleRight`, `belowFrame` = `belowFrame`).
`FrameSpecs.*` are the template blocks converted to `FrameSpec` (cells local to the block). At layout time
the engine resolves the template against the real sheet (format + margins of `PageSetup`) with every active
slot block sized by its `FrameSpec`, so anchoring, `when` and `reserves` come from the YAML for default and
custom FrameSpecs alike. A null slot removes its block. The frame itself is `sheet.content*` anchors.

## Errors

`TemplateException.errors`: list of `path: message`, e.g.
`blocks[2].attach.to: unknown anchor 'stamp.cell[9,9].topLeft'`,
`blocks[0].attach.to: attach cycle: a -> c -> b -> a`.

Full example: `src/test/resources/templates/mini-spec.yaml`.

## CLI: render a template without data

`report-cli` `TemplateMain` renders any template to SVG + PDF (no specification data): FIRST page kind for
page 1, REST for the others (`--pages N`). Binds come from `--data file.yaml` (schema inferred from the file,
see `DataYaml`) or built-in demo data (`doc.designation`, `doc.name`, `doc.mass`, `doc.issued`,
`doc.approvedBy`), plus `page.number` / `page.total`. `TemplateContract` runs before layout; errors are printed
as `path: message` with exit code 1 (an unknown bind is an error, not `${...}` text). FRAME/RECT/TABLE/TEXT are
drawn, FLOW is skipped.

```
./gradlew :report-cli:runTemplate                                   # bundled sheet-frame.yaml -> report-cli/output/
./gradlew :report-cli:runTemplate -Pargs="my.yaml out --pages 2 --data data.yaml"    # paths relative to report-cli/
```

Examples: `report-cli/src/main/resources/templates/{sheet-frame,table-demo}.yaml`. Text rotation: 0|90 only.
