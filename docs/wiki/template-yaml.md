# YAML-шаблон страницы (`report-template`)

Декларативное описание листа и статических блоков (рамка, штампы, таблицы полей) в YAML.
Конвейер: YAML → модель → валидация → резолвер → абсолютная геометрия.
Единицы в YAML: мм. Оси: x вправо, y вниз, начало — левый верхний угол листа.

Полная справочная схема — `report-template/README.md`. Эта страница объясняет, как читать
и писать шаблоны. Рабочие примеры:

- `report-ir/src/main/resources/templates/gost-spec.yaml` — статические блоки спецификации ГОСТ.
  Править осторожно: golden-тест сравнивает вывод побайтно.
- `report-cli/src/main/resources/templates/sheet-frame.yaml` — минимум: лист и рамка.
- `report-cli/src/main/resources/templates/table-demo.yaml` — таблица, поворот, текст.
- `report-template/src/test/resources/templates/mini-spec.yaml` — все возможности модели.

## Как запустить свой шаблон

```
./gradlew :report-cli:runTemplate -Pargs="path/to/your.yaml output --pages 2"
```

Без аргументов берётся `sheet-frame.yaml`. Точка входа — `report-cli/.../TemplateMain.kt`.

- Файлы называются по полю `name:` из YAML: `<name>-page-N.svg` и `<name>.pdf`.
- Папка вывода (`output` по умолчанию) считается от рабочей папки процесса. Через Gradle
  это `report-cli/`, при запуске `main` из IDE — корень репозитория. Абсолютные пути
  печатаются строками `wrote ...`.
- `bind` берётся из типизированных данных: `--data data.yaml` (схема выводится из файла) или
  встроенные демо-данные (`doc.designation`, `doc.name`, `doc.mass`, `doc.issued`, `doc.approvedBy`)
  плюс `page.number` и `page.total`. Перед раскладкой проверяется контракт шаблона с данными:
  неизвестный путь, не скаляр или неподходящий `format` дают ошибки `путь: сообщение` и код выхода 1.
  Неизвестный bind больше не остаётся текстом `${...}`, это ошибка. См. раздел Bind.
- Страница 1 строится как `first`, остальные как `rest`.

## Структура файла

```yaml
name: my-template
sheet:      # формат, ориентация, поля, свои точки
blocksets:  # определения наборов (необязательно)
blocks:     # размещаемые блоки и вызовы наборов
```

### Три уровня

| Уровень | Что это | Рисуется |
|---|---|---|
| **Блок** (`blocks:`, `type: ...`) | единица, которая есть на листе | да |
| **Набор** (`blocksets:`) | именованная группа связанных блоков, определение для повторного использования | нет, пока не вызван |
| **Вызов набора** (`use: имя`) | экземпляр набора, ведёт себя как обычный блок | да |

Вызов набора пишется вместо `type:`. Эти два поля взаимоисключающие.

```yaml
- {id: head, use: headerStrip}      # вызов набора
- id: designation                   # обычный блок
  type: table
  columns: [120]
```

Запись в строку `{...}` и в столбик — один и тот же YAML, выбор только про читаемость.

Определения `blocksets:` пишутся только на верхнем уровне файла. Наборы можно вкладывать
друг в друга, рекурсия запрещена. Блоки набора ссылаются друг на друга по `id`, но не
на блоки снаружи. Снаружи у набора видны якоря его охватывающего прямоугольника и
`ports`.

## Типы блоков

| `type` | Назначение | Основные поля |
|---|---|---|
| `frame` | рамка, линия по умолчанию толстая (0.71 мм) | `size`, `thickness` |
| `rect` | прямоугольник, линия по умолчанию тонкая (0.25 мм) | `size`, `thickness` |
| `table` | сетка колонок и рядов с ячейками | `columns`, `rows`, `rotate`, `borders` |
| `text` | одна надпись | `size`, `text` или `bind` (+ `format`, `optional`), `align`, `rotate`, `fontSize` |
| `flow` | область под основную таблицу с данными | без `size` занимает область потока |

`frame` и `rect` рисуются одинаково, отличаются только толщиной по умолчанию.
Только контур: заливки и текста нет. Надпись внутри делается блоком `text`,
привязанным к `box.center`.

`thickness`: `thin`, `thick` или число в мм.

### Общие поля любого блока

| Поле | Значение |
|---|---|
| `id` | обязательно, `[A-Za-z0-9_-]`, уникально в своей области, не `sheet` и не `self` |
| `size: {width, height}` | размер, мм (у таблицы выводится из колонок и рядов) |
| `attach` | привязка, см. ниже |
| `when` | `first`, `rest`, `all` (по умолчанию) |
| `reserves` | `true`: блок вычитается из области под основную таблицу |
| `anchors` | свои точки `{имя: {x, y}}` от левого верхнего угла блока |

### Пример `rect`

```yaml
blocks:
  - id: box1                     # минимум: размер и привязка
    type: rect
    size: {width: 60, height: 30}
    attach: {self: topLeft, to: frame.topLeft, offset: {x: 10, y: 10}}

  - id: box2                     # толстая линия
    type: rect
    thickness: thick
    size: {width: 60, height: 30}
    attach: {self: topLeft, to: box1.topRight, offset: {x: 10, y: 0}}

  - id: box3                     # толщина числом, мм
    type: rect
    thickness: 1.5
    size: {width: 60, height: 30}
    attach: {self: topLeft, to: box1.bottomLeft, offset: {x: 0, y: 10}}

  - id: box4                     # только на страницах 2+
    type: rect
    when: rest
    size: {width: 60, height: 30}
    attach: {self: topLeft, to: box3.topRight, offset: {x: 10, y: 0}}
```

### Таблица

```yaml
- id: grid
  type: table
  columns: [7, 10, 23, 15, 10]          # ширины, мм
  rows:
    - {height: 5, cells: [~, ~, ~, ~, ~]}
    - height: 5
      cells:
        - {text: "Изм", align: center}
        - "Лист"                          # строка = {text: "Лист"}
        - {bind: "${doc.designation}"}
        - {text: "Разраб.", span: 2}      # 2 колонки
        - {rowSpan: 3}                    # 3 ряда: в нижних рядах эти колонки пропускаются
```

- Ячейка: скаляр (= текст), `~` (пустая) или словарь с полями
  `text` или `bind`, `format`, `optional`, `span`, `rowSpan`, `rotate`, `align`, `fontSize`, `style`, `borders`.
- Каждый ряд должен покрыть все колонки ровно один раз (свои `span` плюс колонки,
  занятые `rowSpan` сверху).
- `borders`: `none`, `thin`, `thick` для всех сторон или `{top, right, bottom, left}`
  для перечисленных. Пропущенная сторона наследуется: ячейка, затем таблица, затем `thick`.
- `rotate` ячейки: поворот текста против часовой, 90 читается снизу вверх.
- `rotate` таблицы (0, 90, 270): таблица описывается без поворота, блок занимает
  повёрнутый охватывающий прямоугольник. Якоря ячеек пересчитываются.
- `repeat: {count: N, row: {...}}` порождает N одинаковых рядов.
  `repeat.from: ...` разбирается, но строк пока не даёт.
- `style` — непрозрачный ключ; `report-ir` понимает `frameText` и `frameTextLarge`.

### Bind

`bind: "${doc.designation}"`. Один путь с корнем `doc`, `page` или `item` (`${page.number}`,
`${page.total}`, `${item.name}`). Составные значения вида `${a}/${b}` в одной ячейке не
поддерживаются. Текст в `text:` всегда литерал: `${...}` в нём не подставляется.

Значения берутся из типизированного контекста данных (`DataContext`, модуль `report-template`).
Шаблон и данные связывает контракт: каждый bind обязан указывать на скалярное поле схемы данных.

**Типы** (`DataType`): `String`, `Integer`, `Decimal` (`BigDecimal`), `Date` (`LocalDate`), `Boolean`,
`Enum(values)`, а также `List` (записей) и `Record` (поля). Последние два в bind недопустимы
(на них будут строиться коллекции и `repeat.from`, пока не резолвятся).

**Корни**: `doc` (данные документа), `item` (строка коллекции, пока только в схеме) и `page`.
`page.number` и `page.total` (`Integer`) объявлены в каждой схеме всегда, значения добавляет движок
при раскладке каждой страницы (они известны только после пагинации).

**Вывод значения** по умолчанию: `String` как есть, `Integer` без форматирования, `Decimal` через
`BigDecimal.toPlainString()` (без локали), `Date` в ISO (`2026-01-31`), `Boolean` как `true`/`false`,
`Enum` его именем.

**`format`** (необязательный, только вместе с `bind`, замкнутый набор ключей):

```yaml
- {bind: "${doc.mass}", format: {pattern: "0.##", locale: ru, rounding: HALF_UP}}   # Decimal
- {bind: "${doc.issued}", format: {pattern: "dd.MM.yyyy"}}                          # Date
```

- `Decimal`: `pattern` обязателен (`java.text.DecimalFormat`), `locale` (`ru|en|de`, по умолчанию без
  локали: точка), `rounding` (`HALF_UP` по умолчанию, `HALF_EVEN`, `HALF_DOWN`, `UP`, `DOWN`,
  `FLOOR`, `CEILING`). Группировка в `ru` идёт неразрывным пробелом.
- `Date`: `pattern` обязателен (`java.time.format.DateTimeFormatter`), `locale` для названий месяцев.
  `rounding` для даты ошибка.
- Для остальных типов `format` ошибка.

**`optional: true`** (только вместе с `bind`): если значения нет, выводится пустая строка. Без него
отсутствие значения при раскладке это ошибка (`IllegalStateException`: путь есть в схеме, а данных нет).

**Контракт** (`TemplateContract.check(template, schema): List<TemplateError>`, `require` бросает
`TemplateException`). Собирает все ошибки с YAML-путями: `blocks[3].rows[1].cells[2].bind`,
`blocks[4].format`. Проверяет, что корень и путь есть в схеме (для неизвестного поля подсказка
`did you mean 'doc.designation'?`), что значение скалярное, что `format` подходит типу и его
шаблон корректен. Bind-ы внутри блок-наборов проверяются один раз по пути определения
(`blocksets.sig.blocks[1].bind`): `${param.x}` в bind недопустим, поэтому у всех экземпляров
bind-ы одинаковы.

**Как написать адаптер** (данные предметной области -> `DataContext`): схема и значения
объявляются одним билдером, пример в `reports/specification/.../SpecificationDataContext.kt`:

```kotlin
fun SpecificationData.toDataContext(): DataContext = dataContext {
    doc {
        string("designation", documentDesignation)
        string("name", documentName)
        // decimal("mass", BigDecimal), date("issued", LocalDate), bool, enum(name, values, value),
        // record("customer") { ... }, list("items") { row { ... } }; значение null = поле только в схеме
    }
}
```

Схему без данных даёт `dataSchema { ... }` (для проверки шаблона без документа). Нужна ленивая
выдача значений: реализуйте интерфейс `DataContext` (`schema`, `get(path)`) напрямую. В движок
контекст передаётся через `PageSetup.dataContext` (или `pageSetup(dataContext = ...)`);
`FrameBindings(designation, name)` готовый контекст для классической рамки.

**`--data file.yaml`** для `TemplateMain`: вложенная карта с корнями `doc` / `item`, схема выводится
из файла. Тип по виду скаляра: `3` Integer, `1.5` Decimal, `2026-01-31` Date, `true|false` Boolean,
иное String. Тег принудительно задаёт тип: `!str "007"`, `!int`, `!decimal`, `!date`, `!bool`.
`~` объявляет String-поле без значения (годится для `optional: true`). Карта это `Record`,
последовательность карт это `List` (списки скаляров пока не поддержаны).

```yaml
doc:
  designation: AB.001
  mass: 7.255
  issued: 2026-03-09
```

## Привязка (`attach`)

Читается так: точка `self` этого блока ставится в точку `to`, плюс `offset`.

```yaml
attach: {self: topLeft, to: sheet.contentTopLeft}
attach: {self: bottomRight, to: sheet.contentBottomRight, offset: {x: 0, y: -40}}
```

`self` по умолчанию `topLeft`. `offset` в осях листа: x вправо, y вниз, миллиметры.
Один и тот же угол у `self` и у цели вкладывает блок внутрь цели, противоположный
выносит наружу.

Раздельно по осям:

```yaml
attach:
  x: {self: topLeft,    to: a.topRight,   offset: 3}
  y: {self: bottomLeft, to: c.bottomLeft, offset: -1}
```

Из якоря берётся только нужная координата. Оси могут ссылаться на разные блоки.

### Якоря

| Где | Что доступно |
|---|---|
| `self` (любой блок) | `topLeft topCenter topRight middleLeft center middleRight bottomLeft bottomCenter bottomRight` |
| `sheet.<9 стандартных>` | точки полного листа, без учёта полей |
| `sheet.contentTopLeft`, `contentTopRight`, `contentBottomLeft`, `contentBottomRight` | углы области внутри полей (лист минус `margins`) |
| `sheet.<имя>` | свои точки из `sheet.anchors`, мм от левого верхнего угла листа |
| `<id>.<9 стандартных>` | якоря другого блока |
| `<таблица>.col[i].left`, `.right` | границы колонок, лежат на верхней грани таблицы |
| `<таблица>.row[j].top`, `.bottom` | границы рядов, лежат на левой грани |
| `<таблица>.cell[r,c].<9 стандартных>` | якоря ячейки, индексы с 0 |
| `<вызов набора>.<порт>` | порты набора плюс 9 стандартных по охватывающему прямоугольнику |

Середин области контента (центр и середины сторон) нет: есть только четыре угла.

Ссылку на ячейку `cell[r,c]` внутри `{...}` надо брать в кавычки, иначе запятая ломает
разбор YAML (`expected ',' or '}', but got [`):

```yaml
attach: {self: topLeft, to: "t3.cell[0,1].topLeft"}   # в кавычках
attach:                                               # или в столбик, без кавычек
  self: topLeft
  to: t3.cell[0,1].topLeft
```

### Как «разбить» ячейку

Деления ячейки как операции нет: таблица всегда прямоугольная сетка. Три способа:

1. **На колонки.** Сделайте сетку мельче: вместо одной колонки 30 мм три по 10 мм.
   Остальные ряды закрывают их через `span: 3`.
2. **На ряды.** Вместо одного ряда три по 6 мм. Соседние ячейки занимают их через `rowSpan: 3`
   (в нижних рядах эти колонки пропускаются).
3. **Вложенная таблица.** Отдельный блок `table` со своими колонками, привязанный к
   `внешняя.cell[r,c].topLeft`. Внешнюю ячейку оставьте пустой (`~`), иначе её текст
   нарисуется под вложенной таблицей. Подходит, когда дробление не вписывается в общую сетку.

Правила:

- Блок без `attach` на верхнем уровне встаёт в `sheet.topLeft` (поле `root`).
  Внутри набора встаёт в начало набора. Так набор остаётся переиспользуемым:
  его положение на листе задаёт только `attach` на вызове.
- Блок можно привязать только к блоку, видимому как минимум на тех же страницах
  (`when`).
- Граф привязок должен быть без циклов, ошибка перечисляет цикл.
- `size` рамки не вычисляется из `margins` и формата, его надо считать руками
  (A3 альбомная с полями 20/5/5/5: 395 × 287).
- Блоки набора видны в разрешённой модели плоско как `экземпляр/ребёнок`.

## Наборы

```yaml
blocksets:
  sideLabel:
    params: {w: 7, h: 30, label: null}     # null = обязательный
    ports: {tail: box.bottomLeft}          # якоря наружу
    blocks:
      - {id: box, type: rect, size: {width: "${param.w}", height: "${param.h}"}}
      - id: caption
        type: text
        text: "${param.label}"
        rotate: 90
        size: {width: "${param.w}", height: "${param.h}"}
        attach: {self: center, to: box.center}

blocks:
  - id: s1
    use: sideLabel
    args: {label: "Инв. №"}
    attach: {self: topLeft, to: sheet.contentTopLeft}
```

- `${param.x}` подставляется в числовые поля, `text` (блока и ячейки) и `args` (в `bind` нельзя: путь статичен).
- `when` вызова объединяется по И с `when` детей.
- Имена портов не должны совпадать со стандартными якорями.

## Как читается `headerStrip` в `gost-spec.yaml`

1. В определении `headerStrip` блок `grid` без `attach`: встаёт в начало набора, 65×15 мм.
2. В `firstStamp`: `head` (вызов `headerStrip`) тоже без `attach`, начало набора.
   `designation` привязан к `head.topRight`, `signatures` к `head.bottomLeft`.
   Охватывающий прямоугольник: 185×40.
3. В корневом `blocks:`: `stamp` (вызов `firstStamp`) с
   `attach: {self: bottomRight, to: sheet.contentBottomRight}`. Только здесь набор
   получает место на листе.

Движок считает набор в локальных координатах и один раз сдвигает целиком.
`headerStrip` ничего не знает о листе, поэтому годится и для `firstStamp`, и для `restStamp`.

## Область потока

`flowRegion` — область контента минус блоки с `reserves: true`, видимые на странице.
Правило как у `contentBottom()` движка: считаются только блоки, перекрывающие колонку
контента по горизонтали; побеждает ближайшая верхняя грань (объединение, не сумма);
срезается только низ области. Верхний случай (блок у верхнего края) сознательно не
реализован.

## Слоты `gost-spec.yaml`

Блоки `stamp`, `continuationStamp`, `leftMargin`, `belowFrame`, `specLeft`, `mainTitleRight`
жёстко связаны с кодом: `report-ir/.../StaticSlot.kt` сопоставляет им поля `PageSetup`.
В своём YAML id любые. Привязка к слотам нужна только основному движку спецификации.
Таблица потока (тело спецификации) в YAML не описана и остаётся кодом движка.

## Известные ограничения

- Поворот текста в `TemplateMain` только 0 и 90 (в Layout IR нет других ориентаций).
  YAML принимает 270, адаптер на нём падает с понятной ошибкой.
- `flow` в `TemplateMain` ничего не рисует.
- Один шрифт (GOST Type B), поле `style` в `TemplateMain` игнорируется.
- `repeat.from` не резолвится.
- Блоки шаблона не из слотов участвуют в расстановке основного движка, но не рисуются.
- Bind только скалярный: коллекции, `repeat.from`, группировка и сортировка (следующие этапы) не резолвятся. Выражений нет.
- Лист нулевой или отрицательной ширины и высоты даёт ошибку валидации.

## Тесты

- `report-template`: загрузка, валидация, привязки, поворот, наборы, область потока; `DataTest` (типы, схема,
  форматы, `--data`), `TemplateContractTest` (контракт, `format`, `optional`, пути ошибок).
- `report-cli`: `TemplateMainTest` (смоук `--data`, ошибка контракта до раскладки).
- `report-geometry`: `AnchorTest` для `placeOrigin` и `resolveAnchor`.
- `report-ir`: `FrameSpecsTemplateParityTest` сверяет `FrameSpecs` из YAML со старыми
  константами (`LegacyFrameSpecs.kt`, только для этой сверки).
- `reports/specification`: `StaticBlocksGoldenTest` с эталонами в `src/test/resources/golden`.
  Каталог `reports/` попадает под правило `.gitignore` строки `/reports/`, новые файлы
  добавляются через `git add -f`.
