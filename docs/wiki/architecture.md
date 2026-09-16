# Архитектура: pipeline и границы

См. также: [architecture-0.2.md](../architecture-0.2.md) (полная актуальная
версия — рамка/штамп как `FrameSpec`, §34), [architecture-0.1.md](../architecture-0.1.md)
(исходная версия), [modules.md](modules.md) (модули по отдельности).

## Главный pipeline

```text
PDM (REST API)
      │
      ▼
report-api        — DTO + контракт клиента (без реальной HTTP-реализации,
      │              см. known-gaps.md)
      ▼
report-data       — mapping DTO → Report Data (домен отчёта)
      │
      ▼
reports/specification  — Report Builder: Report Data → Semantic IR
      │                   (DSL из report-ir)
      ▼
Semantic IR (report-ir)
      │
      ▼
report-layout      — Layout Engine: Semantic IR → Layout IR
      │               (measurement, table layout, pagination)
      ▼
Layout IR (report-layout-ir)
      │
      ├──────────────┬──────────────┐
      ▼              ▼              ▼
report-render-svg  report-render-pdf   (XLSX — не начато, фаза 7)
```

`report-cli` — точка сборки всего пайплайна в одну команду (демо/раннер, не
часть архитектурного pipeline самого по себе).

## Два IR — главная граница

**Semantic IR** (`report-ir`) знает смысл: что это таблица, какие колонки,
какие группы, какой текст, какие стили. Не знает `x/y`, номер страницы,
где случился разрыв.

**Layout IR** (`report-layout-ir`) знает физику: страницы, координаты,
линии, прямоугольники, уже перенесённый текст. Не знает, что такое
"спецификация" или "деталь".

## Правило зависимостей модулей

```text
report-geometry  ← report-ir
report-geometry  ← report-layout-ir      (НЕ зависит от report-ir!)
report-ir + report-layout-ir  ← report-layout
report-layout-ir  ← report-render-svg    (main-код, только это)
report-layout-ir + report-layout(FontRegistry)  ← report-render-pdf
```

Ключевая инвариант, которую держали через все фазы и проверяли grep'ом после
каждого изменения:

```bash
grep -rn "import dev.reportgenerator.ir" report-layout-ir/src/main/     # должно быть пусто
grep -rn "import dev.reportgenerator.ir\." report-render-svg/src/main/ report-render-pdf/src/main/  # должно быть пусто
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
