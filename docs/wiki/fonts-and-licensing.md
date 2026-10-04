# Шрифты и лицензии

## Что настоящее, что заглушка

| Роль | Файл | Что это | Лицензия | Константа |
|---|---|---|---|---|
| GOST Type A (тело таблицы, `Styles.tableText`/`mainText`/`designation`) | `PT_Sans-Regular.ttf` | **Заглушка.** Настоящего ГОСТ 2.304 Type A нет — PT Sans выбран из-за поддержки кириллицы и открытой лицензии | OFL (ParaType) — свободно | `FontFamilies.GOST_TYPE_A` |
| GOST Type A наклонный | `GOST-Type-A-Italic.ttf` | **Настоящий.** ГОСТ 2.304 Type A с наклоном (поставлен пользователем) | ASCON — подтверждено | `FontFamilies.GOST_TYPE_A_ITALIC` |
| GOST Type B (заголовок таблицы, `Styles.tableHeader`) | `GOST-Type-B.ttf` | **Настоящий.** `gosttypeb.ttf`, поставлен пользователем | Copyright © 1996-97 ASCON Ltd, All Rights Reserved — права на использование подтверждены пользователем явно | `FontFamilies.GOST_TYPE_B` |
| GOST Type B наклонный | `GOST-Type-B-Italic.ttf` | **Настоящий.** ГОСТ 2.304 Type B с наклоном (поставлен пользователем) | ASCON — подтверждено | `FontFamilies.GOST_TYPE_B_ITALIC` |
| GOST Type AU | `GOST-Type-AU.ttf` | **Настоящий.** ГОСТ Type AU вариант (узкий, поставлен пользователем) | ASCON — подтверждено | `FontFamilies.GOST_TYPE_AU` |
| GOST Type BU | `GOST-Type-BU.ttf` | **Настоящий.** ГОСТ Type BU вариант (узкий, поставлен пользователем) | ASCON — подтверждено | `FontFamilies.GOST_TYPE_BU` |

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

## Как использовать шрифты в YAML-шаблонах

Шрифты применяются через **предопределённые стили** (более просто) или через **прямой fontFamily** (если нужна специфика).

### Опция 1: Встроенные стили (рекомендуется)

`Styles` в `report-ir/Styles.kt` определяет именованные стили с шрифтами. Используй в YAML через ключ `style:`:

```yaml
header:
  rows:
    - height: 5
      cells:
        - { text: "Заголовок", style: tableHeader }  # GOST Type B
        - { text: "Данные", style: tableText }        # GOST Type A
```

Доступные встроенные стили (см. `Styles.named`):
- `mainText` → GOST Type A, 3.5mm
- `tableText` → GOST Type A, 3.5mm  
- `heading` → GOST Type A, 5.0mm, bold
- `designation` → GOST Type A, 2.5mm
- `tableHeader` → GOST Type B, 3.5mm
- `groupHeader` → GOST Type B, 3.5mm, italic, underline
- `totalText` → GOST Type B, 3.5mm, bold
- `frameText` → GOST Type B, 3.5mm
- `frameTextLarge` → GOST Type B, 7.0mm

### Опция 2: Кастомный TextStyle с fontFamily

Если встроенного стиля не хватает, используй `fontFamily` в коде Kotlin, не в YAML (YAML не поддерживает определение новых стилей).

Возможные значения `fontFamily`:
- `FontFamilies.GOST_TYPE_A` — основной шрифт (заменитель PT Sans)
- `FontFamilies.GOST_TYPE_A_ITALIC` — GOST Type A с наклоном
- `FontFamilies.GOST_TYPE_B` — заголовки, нумерация
- `FontFamilies.GOST_TYPE_B_ITALIC` — GOST Type B с наклоном  
- `FontFamilies.GOST_TYPE_AU` — узкий GOST Type A
- `FontFamilies.GOST_TYPE_BU` — узкий GOST Type B

Пример (Kotlin):
```kotlin
val customStyle = TextStyle(
    fontFamily = FontFamilies.GOST_TYPE_A_ITALIC,
    fontSizeMm = 4.0,
    italic = true  // флаг стиля (синтетический наклон поверх шрифта)
)
```

### Примечания

1. **Наклон и bold**: стили `italic` и `bold` — флаги, не требуют отдельного .ttf файла. Но если шрифт сам наклонный (Type A Italic / Type B Italic), флаг `italic` добавит синтетический наклон сверху.

2. **Опечатка в fontFamily**: если fontFamily не совпадает ни с одной из констант, резолвер молча даст GOST Type A (заглушка). Всегда проверь константу в `FontFamilies`.

3. **Где эти стили резолвятся**: `DefaultFontRegistry.resolve(style)` в `report-layout` маппит fontFamily на реальный `.ttf` файл. Не меняй это без обновления регистрации шрифтов.
