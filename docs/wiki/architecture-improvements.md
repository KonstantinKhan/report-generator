# Architecture Improvements

Потенциальные архитектурные рефакторинги и улучшения. Идеи, которые стоят обсуждения и валидации перед реализацией.

Статус разделов на 2026-10-03: «Упразднить report-api» остаётся идеей (условие реализации наступило, решение не принято);
«Универсальный механизм привязки статических блоков» и рецепт ручной проверки блоков **УСТАРЕЛИ** (реализовано в
`report-template`, см. [template-yaml.md](template-yaml.md), хронология в [changelog-engine.md](changelog-engine.md)).

## Упразднить report-api модуль, объединить с report-data

> **Статус (2026-10-03).** Условие реализации («появится реальный HTTP-клиент к PDM») наступило: `report-loodsman`
> реализует `PdmClient` из `report-api`, `report-server` собирает его с рендером. Модуль `report-api` сохранён как контракт
> между клиентом и `report-data`; решение об упразднении не принималось, раздел остаётся идеей.

**Текущее состояние:**
- `report-api`: DTO контракт с PDM (`SpecificationDto`, `ItemDto`) + интерфейс `PdmClient`
- `report-data`: маппинг DTO → доменная модель (`SpecificationData`, `SpecificationItem`)
- Поток: `PdmClient.fetchSpecification()` → `SpecificationDto` → `mapToSpecificationData()` → `SpecificationData`

**Предложение:**
- Упразднить `report-api` модуль
- `PdmClient` переместить в `report-data`
- `PdmClient.fetchSpecification()` возвращает напрямую `SpecificationData` (без промежуточного DTO)
- Маппинг остаётся внутри `PdmClient`, не публичная функция

**Плюсы:**
- Меньше кода, один промежуточный тип вместо двух
- Одна граница между внешним миром и доменой логикой (вместо двух)
- Проще понять поток данных: PDM → SpecificationData

**Минусы:**
- `SpecificationData` становится привязана к PDM контракту (если PDM меняется, меняется доменная модель)
- Если когда-то будут другие потребители PDM контракта, нужен будет рефакторинг

**Условие реализации:**
- Когда появится реальный HTTP-клиент к PDM
- Обсудить, действительно ли нужна отдельная граница контракта
- Проверить, будут ли другие потребители PDM API

**Статус:** Идея, требует валидации при реальной интеграции с PDM

## Универсальный механизм привязки статических блоков к рамке

**Терминология (согласована 2026-09-18):**
- **Формат листа** — размер листа (А-форматы + кратные по ГОСТ 2.301)
- **Рамка** — основной прямоугольник, отталкивается от формата листа + margins
- **Статический блок** — широкий термин: любой фикс-элемент, привязанный к рамке по углу + offset (штамп, левое поле, below-frame полоса и т.п.)
- **Штамп** — узкий термин: статический блок конкретно основной надписи по ГОСТ 2.104 (Изм/Лист/№ докум./Подп./Дата)
- **Область контента** — остаток листа после вычитания рамки и всех статических блоков
- **Динамический контент** — заполнение области контента по правилам (пагинация, sticky-колонки)

> **УСТАРЕЛО (2026-10-03).** Описание ниже относится к состоянию до модуля `report-template`.
> Статические блоки теперь заданы в `report-ir/src/main/resources/templates/gost-spec.yaml`,
> привязка и резерв места считает `TemplateResolver`, а `Corner`/`resolveAnchor` перенесены
> в `report-geometry`. Функции `frameOrigin()`, `leftMarginFrameOrigin()`, `belowFrameOrigin()`
> и `firstPageBlockRects`/`continuationPageBlockRects` удалены. Актуальное описание:
> [template-yaml.md](template-yaml.md). Раздел сохранён как история решения.

**Текущее состояние (на момент ADR, устарело):**
- Статические блоки описаны как `FrameSpec`/`FrameCell` (`report-ir/.../ir/Frame.kt`, конкретные инстансы в `frames/FrameSpecs.kt`): `firstPageStamp`, `leftMarginTable`, `belowFrameNotes`, `continuationPageStamp`. Ячейки — плоский список с абсолютными `rect(x,y,w,h)` в мм, без DSL.
- Привязка к рамке — 3 отдельные функции в `LayoutEngine.kt` с зашитой формулой каждая: `frameOrigin()` (bottom-right), `leftMarginFrameOrigin()` (left), `belowFrameOrigin()` (bottom-right, другой вариант). Нет enum якорей и общей `resolve(corner, offset, spec) -> Point`.
- Стыковка блоков по нижней линии — структурная (обе origin-функции используют общий `margins.bottom` из `PageLayoutMetrics`), но размеры самих блоков (185×40, 12×135, 120×5 мм) — хардкод без формулы к высоте страницы. Инвариант «сумма высот = высота страницы − margins» нигде не проверяется.
- **Известный пробел:** `contentBottom()` резервирует место под контентом только под штамп (40мм), НЕ под `leftMarginTable` (135мм). Комментарий в коде (`FrameSpecs.kt:96-101`) прямо признаёт: резервирование под leftMargin «isn't done yet». Если контент таблицы спецификации станет длиннее, может залезть под левое поле — сейчас ничто это не предотвращает.
- Отдельно от статических блоков существует `IrTable`/`IrColumn`/`IrCell` (`ir/Ir.kt`) — декларативная модель таблицы спецификации, геометрию считает layout engine, это НЕ хардкод-координаты и трогать не требуется.

**Предложение:**
- Ввести единый anchor-механизм: `resolve(corner: Corner, offset, spec, metrics) -> Point` вместо 3 отдельных функций. Новый статический блок = конфиг (угол + offset), а не новая функция.
- Область контента считать как лист минус рамка минус **union** всех зарегистрированных статических блоков (а не хардкод-вычет одного штампа). Тогда добавление нового блока в привязку автоматически ужимает область контента — без ручных правок `contentBottom()`.
- Параметризовать набор статических блоков по типу страницы (страница 1: frame+firstPageStamp+leftMargin+belowFrame; страницы 2+: continuationFrame+continuationPageStamp+leftMargin) — не отдельные пайплайны, а конфиг на тип.

**Плюсы:**
- Закрывает найденный баг (недорезервированная область под leftMarginTable) структурно, а не точечным патчем
- Меньше дублирования кода origin-функций
- Явная, проверяемая модель вместо разрозненных хардкод-констант

**Минусы:**
- Переделка контракта `PageSetup`/`LayoutEngine` — затрагивает рендеринг всех типов страниц
- Тестов на инварианты (сумма высот блоков ⊂ высота страницы) сейчас нет — нужно писать вместе с рефакторингом, иначе риск тихой регрессии

**Условие реализации:**
- Anchor-рефактор (3 функции → 1) можно делать отдельно, низкий риск
- Union-based `contentArea` — второй шаг, обязательно с тестом-инвариантом; делать одновременно с этим рефакторингом, а не откладывать

**Статус:** ADR принят 2026-09-18, реализация в ветке `feature/static-block-anchoring`

## Как вручную протестировать механизм создания блоков

> **УСТАРЕЛО (2026-10-03).** Рецепт ниже требует правки `LayoutEngine.kt` (`firstPageBlockRects`,
> `staticBlockRect`, ручные вызовы `resolveAnchor`). Этих мест больше нет: блоки добавляются
> в YAML. Чтобы попробовать новый блок, возьмите свой YAML и запустите
> `./gradlew :report-cli:runTemplate -Pargs="your.yaml output"`, см. [template-yaml.md](template-yaml.md).

Публичного API для регистрации произвольного блока пока нет — 4 именованных слота `PageSetup` (`frame`/`continuationFrame`/`leftMarginFrame`/`belowFrame`) жёстко привязаны к своим углам внутри `LayoutEngine.kt` (`firstPageBlockRects`/`continuationPageBlockRects`). Добавление блока в НОВЫЙ угол — временная правка этого файла. Ниже — проверенный на практике рецепт (все шаги реально прогнаны при подготовке этого раздела, включая найденный по ходу баг, см. конец).

### Вариант A — быстро, без сборки PDF (только математика)

```
./gradlew :report-layout:test --tests "dev.reportgenerator.layout.LayoutEngineTest.contentBottom*"
```

Гоняет тест-инварианты union-логики `contentBottom()` на синтетических `Rect`, без рендера.

### Вариант B — визуально, через реальный PDF/SVG

Цель: добавить тестовый блок «ТЕСТ» 40×10мм в угол `TOP_LEFT` листа — угол, которым сегодня не пользуется ни один прод-блок (все три реальных блока анкорены снизу).

1. Открыть `report-layout/src/main/kotlin/dev/reportgenerator/layout/LayoutEngine.kt`.

2. Добавить импорты (если их ещё нет):
   ```kotlin
   import dev.reportgenerator.geometry.Size
   import dev.reportgenerator.geometry.mm
   ```

3. Перед `firstPageBlockRects()` добавить тестовый спек и зарегистрировать его в списке блоков (нужен и для отрисовки, и для резервирования места под контент):
   ```kotlin
   private val testSpec = FrameSpec(
       size = Size(40.mm, 10.mm),
       cells = listOf(FrameCell.Constant(Rect(0.mm, 0.mm, 40.mm, 10.mm), "ТЕСТ", align = TextAlign.CENTER))
   )

   private fun firstPageBlockRects(setup: PageSetup, frameRect: Rect, pageRect: Rect): List<Rect> = buildList {
       setup.frame?.let { add(staticBlockRect(it, frameRect, Corner.BOTTOM_RIGHT)) }
       setup.leftMarginFrame?.let { add(staticBlockRect(it, frameRect, Corner.BOTTOM_LEFT, Corner.BOTTOM_RIGHT)) }
       setup.belowFrame?.let { add(staticBlockRect(it, pageRect, Corner.BOTTOM_RIGHT)) }
       add(staticBlockRect(testSpec, pageRect, Corner.TOP_LEFT))  // временная строка
   }
   ```

4. В `renderPages()`, в ветке `if (pageNumber == 1) { ... }` (рядом с существующими `setup.frame?.let { ... }`), добавить отрисовку:
   ```kotlin
   chrome += drawFrame(testSpec, resolveAnchor(metrics.pageRect, Corner.TOP_LEFT, size = testSpec.size), emptyMap(), textMeasurer, fontResolver)
   ```

5. Собрать и сгенерировать отчёт:
   ```
   ./gradlew :report-cli:run
   ```
   Файлы появятся в `report-cli/output/`: `specification-page-1.svg`, `specification-page-2.svg`, `specification.pdf`.

6. Открыть `specification-page-1.svg` (браузером) или `.pdf` — блок «ТЕСТ» появится в левом верхнем углу листа (координаты (0,0)-(40,10)мм — можно проверить через `grep "ТЕСТ" report-cli/output/specification-page-1.svg`, там будет `<text ...>ТЕСТ</text>` рядом с `x="0.0" y="-0.35"..."x=40.35"` линиями рамки блока).

7. Откатить правки — это одноразовый эксперимент, не коммитить:
   ```
   git checkout -- report-layout/src/main/kotlin/dev/reportgenerator/layout/LayoutEngine.kt
   ```

### Что нашли, прогоняя этот рецепт (2026-09-18)

До фикса количество страниц в примере молча менялось 2 → 3: `contentBottom()` фильтровал блоки только по пересечению с content-колонкой по X, не проверяя, что блок лежит НИЖЕ `contentTop`. Тестовый блок в TOP_LEFT (y=0..10мм) пересекался по X → его `top=0мм` попадал в `reservedTops` → `contentBottom` схлопывался почти в 0 → контент первой страницы почти весь уезжал на вторую.

**Исправлено** (уже в коде, не нужно повторять при тесте): фильтр `contentBottom()` теперь дополнительно требует `it.top >= contentTop`. Регрессионный тест — `LayoutEngineTest.kt`: `a block anchored above contentTop does not corrupt contentBottom`.

**Явное ограничение, которое это вскрыло:** union-механизм сегодня резервирует только НИЖНЮЮ границу контента (`contentBottom`). `contentTop` — по-прежнему хардкод `margins.top + headerHeight`, блоки НЕ могут отодвинуть верх контента вниз. Если понадобится настоящий блок сверху (например, шапка над таблицей) — нужен симметричный `contentTop()`-union; это не сделано, отдельная задача.

<details>
<summary>ADR: единый anchor-механизм (2026-09-18)</summary>

**Решение.** Заменить 3 хардкод-функции (`frameOrigin`, `leftMarginFrameOrigin`, `belowFrameOrigin`) одной:

```kotlin
enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

fun resolveAnchor(
    base: Rect,            // frame rect или page rect
    baseCorner: Corner,    // угол base, к которому крепимся
    blockCorner: Corner = baseCorner,  // угол блока, который совпадает с anchor-точкой
    size: Size,
    offset: Point = Point.ZERO
): Point
```

Anchor-точка = угол `base` (`baseCorner`). Origin блока = anchor-точка минус смещение до `blockCorner` блока, плюс `offset`. Один блок-угол = блок «растёт» внутрь base; противоположный по оси — блок «растёт» наружу.

**Проверка на всех 3 текущих случаях (формулы сошлись 1:1):**
- `frameOrigin` = `resolveAnchor(frameRect, BOTTOM_RIGHT, BOTTOM_RIGHT, spec.size)` → внутрь рамки, у правого нижнего угла (штамп)
- `leftMarginFrameOrigin` = `resolveAnchor(frameRect, BOTTOM_LEFT, BOTTOM_RIGHT, spec.size)` → наружу рамки влево, низ вровень с рамкой (leftMarginTable)
- `belowFrameOrigin` = `resolveAnchor(pageRect, BOTTOM_RIGHT, BOTTOM_RIGHT, spec.size)` → в правый нижний угол листа, за рамкой (belowFrameNotes)

Новый статический блок = запись `(spec, base, baseCorner, blockCorner, offset)`, не новая функция.

**Union-based contentArea.** `contentBottom()` вместо хардкод-вычета одного `frameHeight` берёт минимум верхней границы среди всех resolved-block-rect'ов, которые горизонтально пересекают content-диапазон (`[margins.left, format.width - margins.right]`). Блоки, целиком лежащие в гутере (leftMarginTable, belowFrameNotes — их `base=FRAME`/`PAGE`, но `blockCorner` направлен НАРУЖУ рамки), в content-диапазон не попадают и на contentBottom не влияют — это подтверждено геометрией (x < margins.left), а не хардкодом. Реально резервирующий блок — только stamp/continuationStamp снизу. Добавляется тест-инвариант: сумма зарезервированных высот ⊂ высота страницы, для обоих типов страниц.

**Не входит в этот рефакторинг:** `IrTable`/`IrColumn`/`IrCell` — геометрию считает layout engine, это не хардкод-координаты.

**Риск:** `frameOrigin`/`leftMarginFrameOrigin`/`belowFrameOrigin` — приватные функции `LayoutEngine.kt`, вызываются только внутри него (4 call site на файл) → рефактор локализован, не трогает публичный контракт `PageSetup`/`FrameSpec`.

</details>

<details>
<summary>Саммари обсуждения (2026-09-17 — 2026-09-18)</summary>

Отправная точка: вопрос «как реализована привязка блоков» по поводу continuation-frame/pagination фичи (commits 95c1c11, a391dea, ebf21b0, 5a97b91, 4ab29cd).

Ход обсуждения:
1. Разобрали, что привязка (`frameOrigin`, `leftMarginFrameOrigin`, `belowFrameOrigin`) — хардкод, 3 похожие функции без общего anchor-enum.
2. Разобрали, как формируются сами блоки: `FrameSpec`/`FrameCell` (Constant/Dynamic) — плоский список ячеек с абсолютными rect-координатами, без DSL. Отдельно — `IrTable`/`IrColumn`/`IrCell` для самой таблицы спецификации (не хардкод, считает layout engine).
3. Проверили стыковку блоков: она структурная по Y (общий `margins.bottom`), но неполная — `contentBottom()` не резервирует место под `leftMarginTable`, это признано в комментарии кода как незавершённое.
4. Пользователь предложил разбить формирование отчёта на этапы (формат листа → рамка → штампы с биндингами → область динамического контента), по аналогии с ручным формированием таких документов.
5. Уточнили: то, что пользователь называл «штампом», по факту — весь класс статических блоков. Договорились разделить термины: «статический блок» (широкий) и «штамп» (узкий, только ГОСТ-таблица).
6. Итоговое предложение: единый anchor-механизм + union-based область контента, чтобы закрыть пробел из п.3 структурно.

Если продолжать в новой сессии: следующий шаг — оформить ADR и решить порядок (anchor-рефактор отдельно от union-contentArea, см. «Условие реализации» выше).
</details>
