# Шрифты и лицензии

## Что настоящее, что заглушка

| Роль | Файл | Что это | Лицензия |
|---|---|---|---|
| GOST Type A (тело таблицы, `Styles.tableText`/`mainText`/`designation`) | `PT_Sans-Regular.ttf` | **Заглушка.** Настоящего ГОСТ 2.304 Type A нет — PT Sans выбран из-за поддержки кириллицы и открытой лицензии | OFL (ParaType) — свободно |
| GOST Type B (заголовок таблицы, `Styles.tableHeader`) | `GOST-Type-B.ttf` | **Настоящий.** `gosttypeb.ttf`, поставлен пользователем | Copyright © 1996-97 ASCON Ltd, All Rights Reserved — права на использование подтверждены пользователем явно |

Файл `report-layout/src/main/resources/fonts/GOST-Type-B-COPYRIGHT.txt`
хранит оригинальный copyright-текст рядом со шрифтом — для provenance.

## Почему это важно и не мелочь

Копирайт на GOST Type B — "All Rights Reserved", без явного лицензионного
файла, разрешающего распространение. Перед тем как коммитить `.ttf` в
репозиторий и встраивать (embed) в генерируемые PDF (что само по себе —
распространение шрифта каждому получателю PDF), у пользователя был
запрошен явный вопрос через `AskUserQuestion`: есть ли у него/компании
права на такое использование. Получено явное подтверждение — только после
этого файл закоммичен.

**Правило на будущее:** если появится третий шрифт с неясной лицензией —
тот же вопрос, не предполагать "наверное можно".

## Где физически лежат файлы шрифтов

Тестовые фикстуры (копия `PT_Sans-Regular.ttf` + `OFL.txt`) продублированы
руками в `src/test/resources/fonts/` у каждого модуля, которому нужен
реальный шрифт для тестов:

- `report-layout/src/test/resources/fonts/`
- `report-render-svg/src/test/resources/fonts/`
- `report-render-pdf/src/test/resources/fonts/`
- `reports/specification/src/test/resources/fonts/`

Продовый (не тестовый) шрифт живёт только в одном месте —
`report-layout/src/main/resources/fonts/` (`PT_Sans-Regular.ttf`, `GOST-Type-B.ttf` и copyright-файл), его загружает
`DefaultFontRegistry.load()`; им пользуются `report-cli` (`Main`, `TemplateMain`) и `report-server`.

## Как работает резолвинг шрифта по имени семейства

`TextStyle.fontFamily` — обычная строка (не sealed-тип, поле определено в
`report-ir/Styles.kt`). Сопоставление строки с зарегистрированным в `FontRegistry` шрифтом делает
`DefaultFontRegistry.resolve(style)` (`report-layout`):

```kotlin
fun resolve(style: TextStyle): FontRef =
    if (style.fontFamily == FontFamilies.GOST_TYPE_B) gostBRef else regularRef
```

Регистрация: id `gost-type-a` (PT Sans Regular) и `gost-type-b`. Любой потребитель вызывает `DefaultFontRegistry.load()` и
передаёт `fonts::resolve` в `layOut()`/`PdfBoxTextMeasurer`, `fonts.registry` в `renderToPdf()`.

`FontFamilies.GOST_TYPE_A` / `GOST_TYPE_B` — общие константы в
`report-ir/Styles.kt`, чтобы строка не дублировалась как несвязанный
литерал в двух местах (раньше было именно так — реальный найденный
хардкод, см. [design-decisions.md](design-decisions.md)).

Ограничения: шрифт всего один на гарнитуру (нет bold/italic файлов: `bold` это флаг стиля, `italic` рисуется синтетическим
наклоном, `underline` — линией), а опечатка в имени семейства тихо даёт PT Sans, потому что сравнивается строка. См.
[known-gaps.md](known-gaps.md).
