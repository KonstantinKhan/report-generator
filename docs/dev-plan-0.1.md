# План разработки v0.1

Основан на [architecture-0.1.md](./architecture-0.1.md). Решения, зафиксированные в сессии проектирования (2026-09-15):

- Semantic IR и Layout IR — раздельные модули (`report-ir` / `report-layout-ir`), оба опираются на `report-geometry`, друг от друга не зависят.
- `Length` — fixed-point `Long`, 1/100 мм (§21).
- `TextMeasurer` — поверх PDFBox font metrics, единый font registry между `report-layout` и `report-render-pdf` (§22).
- Overflow — hard error со structured diagnostics, без best-effort деградации (§33).

---

## Модульное дерево (порядок появления в разработке)

```text
report-geometry     Length, Point, Size, Rect, Insets
report-ir           Semantic IR, DSL, Styles, Constraints
report-layout-ir    Page, PositionedText, Line, Rectangle, PositionedImage
report-layout       Measure(PDFBox)/Flow/Table/Pagination/PageTemplates
report-render-svg   Layout IR → SVG
report-render-pdf   Layout IR → PDF
report-render-xlsx  Semantic IR → XLSX (после стабилизации PDF/layout)
report-api          PDM REST DTO/client
report-data         API DTO → Report Data
reports/specification  первый реальный отчёт (спецификация)
```

---

## Фаза 1 — Foundation

- `report-geometry`: `Length` (Long, 1/100мм) + арифметика, `Point`/`Size`/`Rect`/`Insets`.
- `report-ir`: skeleton Semantic IR (`IrDocument`, `IrText`, `IrTable`, `IrColumn`, `IrGroup`, `IrRow`, `IrCell`), `Styles`, `LayoutConstraints`.
- `report-layout-ir`: skeleton Layout IR (`LaidOutDocument`, `Page`, `PositionedText`, `Line`, `Rectangle`, `PositionedImage`).
- Минимальный builder DSL (`document { }`, `table { }`, `group { }`, `row { }`).
- Builder tests (§27.1): Report Data → Semantic IR, проверка структуры (N items → N rows → M groups).

**Выход:** можно построить Semantic IR и Layout IR как объектные графы, без layout-логики.

---

## Фаза 2 — Text measurement

- `report-layout`: font registry (загрузка embedded TTF, id → `PDFont`).
- `TextMeasurer` на PDFBox (`measure(text, style, maxWidth) → width/height/lineCount`).
- Unit-тесты измерения: однострочный текст, перенос по ширине, пустая строка.

**Выход:** есть единственный источник истины для ширины/переноса текста, который позже переиспользует PDF renderer.

---

## Фаза 3 — Layout Engine MVP

- Page format A4, margins, frame (`eskdFrame`), title block.
- Text wrapping на основе `TextMeasurer`.
- Table layout: ширина колонок, высота строк, группы.
- Pagination: page breaks, repeated headers, first/continuation page (§16).
- Layout constraints: `keepTogether`, `keepWithNext`, `avoidBreakBefore/After` (§19).
- Overflow diagnostics (§33): structured exception с путём до элемента, нарушенным constraint, номером страницы.
- Layout tests (§27.2): количество страниц, начало группы, высота строк, перенос текста, page breaks, repeated headers, first/continuation, положение title block.

**Выход:** Semantic IR → Layout IR работает end-to-end на синтетических данных.

---

## Фаза 4 — SVG renderer (debug-first)

- `report-render-svg`: Layout IR → SVG.
- SVG snapshot tests (§27.3): Semantic IR → Layout → SVG → snapshot.

**Выход:** визуальная проверка layout в браузере без PDF-стадии — основной инструмент отладки Layout Engine на фазах 3/5.

---

## Фаза 5 — PDF renderer

- `report-render-pdf`: Layout IR → PDF через PDFBox.
- Резолв шрифтов по id из font registry (фаза 2) — без повторной загрузки.
- PDF visual tests (§27.4): rasterize → PNG → pixel comparison.

**Выход:** первый реальный выходной формат.

---

## Фаза 6 — End-to-end на реальном отчёте

- `report-api`: REST DTO/client к PDM.
- `report-data`: mapping/aggregation API DTO → Report Data.
- `reports/specification`: builder для реальной инженерной спецификации (группы «Сборочные единицы» / «Детали» / «Стандартные изделия»).
- Прогон на реальных PDM-данных, сверка результата с эталоном.

**Выход:** MVP закрывает §29 полностью для одного типа документа.

---

## Фаза 7 — XLSX projection

- `report-render-xlsx`: Semantic IR → XLSX напрямую (без Layout IR, §15).
- Отдельные тесты: группы → Excel outline/grouping, стили → cell styles.

Стартует только после стабилизации фаз 3–6.

---

## Сквозное — Determinism infra

Ведётся параллельно с фазами 3–6, не отдельная фаза:

- структурное сравнение Layout IR (§28) для regression tests;
- фиксация engine version в артефактах тестов.

---

## Вне плана v0.1

Всё из §30 architecture-0.1.md (visual designer, drag-and-drop, универсальный report builder, собственный scripting language, Word renderer, сложные charts, CSS-like layout, микросервисы, SQL-интеграция с PDM) — сознательно не делается.
