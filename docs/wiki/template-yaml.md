# YAML-шаблон страницы (`report-template`)

Пошаговый практикум с запускаемыми примерами: [template-guide.md](template-guide.md).

Декларативное описание листа и статических блоков (рамка, штампы, таблицы полей) в YAML.
Конвейер: YAML → модель → валидация → резолвер → абсолютная геометрия.
Единицы в YAML: мм. Оси: x вправо, y вниз, начало — левый верхний угол листа.

Полная справочная схема — `report-template/README.md`. Эта страница объясняет, как читать
и писать шаблоны. Рабочие примеры:

- `report-ir/src/main/resources/templates/gost-spec.yaml` — лист спецификации ГОСТ целиком: статические блоки
  и таблица потока (блок `body`). Править осторожно: golden-тест сравнивает вывод побайтно.
- `report-cli/src/main/resources/templates/sheet-frame.yaml` — минимум: лист и рамка.
- `report-cli/src/main/resources/templates/table-demo.yaml` — таблица, поворот, текст.
- `report-cli/src/main/resources/templates/purchased-list.yaml` (+ `purchased-list-data.yaml`) — таблица потока с `groupBy`, `sortBy`,
  `computed` (нумерация в группе), `cases`, `format`; запуск `--data purchased-list-data.yaml`.
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
| `flow` | область под основную таблицу с данными | `table` (см. «Таблица потока»), без `size` занимает область потока |

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
последовательность карт это `List` (списки скаляров пока не поддержаны). Корень `item:` может быть
последовательностью карт: это строки таблицы потока (`DataYaml.parseFile`, `DataFile.items`), схема корня `item`
выводится как объединение полей строк.

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

## Таблица потока (`flow` + `table`)

Основная таблица листа (тело спецификации). YAML описывает ЧТО рисовать (структура, стиль, параметры) и КАК данные
попадают в таблицу (фильтр, сортировка, группы, нумерация, формат значения, условный вариант ячейки). Алгоритм
раскладки (измерение, перенос слов, пагинация, дозаполнение страницы, цепочки keep, два прохода) остаётся кодом
`report-layout`. Код поставляет только типизированные записи `item` (сырые значения): строка спецификации не содержит
правил таблицы. Все операции закрытый набор структурных ключей, строкового языка выражений и скриптов нет.

Блок `flow` с `table:` занимает область потока: `size`, `attach`, `reserves` и `when` кроме `all` не допускаются,
внутри набора (`blocksets`) такой блок недопустим, на шаблон приходится одна таблица потока. `flow` без `table`
и без `size` остаётся просто областью потока (не якорь, не резервирует место).

| Ключ `table:` | Значение |
|---|---|
| `rowHeight` | высота строки, мм (> 0), обязательно |
| `columns` | список `{id, width, stick, align}`; `width` мм; `stick`: `first` / `last` / `none` (по умолчанию) = `stickToFirstRow` / `stickToLastRow` (одностроковое значение садится на первую / последнюю физическую строку строки, растянутой другой колонкой); `align`: `left` (по умолчанию) / `center`, умолчание для ячейки строки |
| `header` | `{height, repeat, cells}`: `height` мм; `repeat: true` (по умолчанию) шапка на каждой странице, `false` только на первой (остальные страницы начинаются у верхнего поля); `cells` по id колонки, покрывают все колонки, `~` пустая |
| `header.cells.<id>` | `{text, lines, rotate, align, style}` или скаляр (= `text`). `text` обязателен; `lines` ручной перенос (без авто-переноса, не вместе с `rotate`); `rotate`: 0 / 90 (270 ошибка); `align`: `center` по умолчанию |
| `groupTitle` | `{column, style, align, spacerBefore, spacerAfter}` (только вместе с `groupBy`): заголовок группы кладётся в колонку `column` (остальные ячейки строки пустые); `spacerBefore` / `spacerAfter` пустые строки с границами до и после (по умолчанию 0, в спецификации 2 и 1); `align` `center` по умолчанию. Без секции заголовок идёт одной ячейкой на всю ширину без спейсеров |
| `row` | `{cells}`: ячейки по id колонки, покрывают все колонки |
| `row.cells.<id>` | `{text, bind, format, optional, align, style, cases}`, скаляр (= `text`) или `~` (пустая). `bind` только вида `${item.поле}`; `align` по умолчанию из колонки; `cases` условные варианты содержимого |
| `fill` | `blank`: дозаполнить страницу пустыми строками с границами до рамки; `none` (по умолчанию) нет |
| `keep` | `{titleChain: true}` (по умолчанию): пустые строки до, заголовок группы, пустые после и первая строка данных не разрываются границей страницы (заголовок-сирота внизу страницы невозможен); `false` цепочки нет |
| `styles` | псевдонимы `{имя: стиль}`; ячейка ссылается на псевдоним или прямо на имя стиля |
| `where` | предикат фильтра строк (см. «Данные таблицы»); нет = все строки |
| `sortBy` | список `{field, order: asc\|desc, nulls: first\|last}`; нет = порядок источника |
| `groupBy` | `{field, order, titles, skipEmpty, omit}`: разбиение по enum-полю на группы; нет = плоская таблица |
| `computed` | вычисляемые поля: `{имя: {sequence: {scope, start, step}}}` или арифметика `{имя: {multiply\|add\|subtract\|divide: [операнды], scale, rounding}}` |
| `totals` | итоги и агрегаты: список `{id, scope, agg, field, label, labelColumn, valueColumn, format, style, where, skipEmpty, scale, rounding}`; строки подвала группы и таблицы (см. «Итоги») |

**Стили.** Имена стилей закрытый набор `Styles.named` из `report-ir`: `mainText`, `tableText`, `heading`,
`designation`, `tableHeader`, `groupHeader`, `totalText`, `frameText`, `frameTextLarge`. Умолчания: ячейка строки
`tableText`, шапка `tableHeader`, заголовок группы `groupHeader`, строка итога `totalText` (GOST Type B, флаг bold:
жирного начертания в проекте пока нет, как и у `heading`, поэтому итог отличается гарнитурой, а не весом). Неизвестное имя это ошибка с путём
(`blocks[5].table.row.cells.name.style`), если загрузчику переданы известные имена
(`TemplateLoader.load(yaml, styleNames)`; `GostSpecTemplate` и CLI передают `Styles.named.keys`), иначе ошибка
возникает при сборке `IrTable`.

**Ширина.** Сумма `width` колонок должна равняться ширине области потока, то есть ширине области контента листа
шаблона (`sheet`: ширина минус левое и правое поле), с точностью до 0.01 мм, без допуска. Ошибка с путём
`blocks[i].table.columns`. Проверка идёт по `sheet` самого шаблона: при раскладке документа на другом формате
(`PageSetup`, например A3 альбомная) колонки сохраняют свою ширину, как и раньше.

**Контракт.** Корень `item` в схеме данных это запись одной строки, поля объявляет адаптер (для спецификации
`SpecificationData.toDataContext()`): `designation` String, `name` String, `kind` Enum(ASSEMBLY|PART|STANDARD|OTHER|MATERIAL),
`quantity` Decimal, `unit` String. Поле без значения в записи (нет обозначения, нет единицы) требует `optional: true`
(в ячейке или в варианте `cases`), иначе ошибка раскладки. К полям записи добавляются `computed`: `sequence` это Integer, тип арифметики выводится (см. «Арифметика»).
Контракт проверяет по схеме (пути `blocks[5].table...`): bind-ы ячеек и вариантов, поля и литералы в `where` / `sortBy` /
`groupBy` / `cases[].where`, `format` под тип, пересечение имён `computed` с полями. Неизвестное поле это ошибка
загрузки (с подсказкой ближайшего имени), а не пустой столбец.

### Данные таблицы: where, sortBy, groupBy, computed, cases, format

Порядок: `where` (фильтр) -> арифметика `computed` -> `sortBy` (устойчивая сортировка) -> `groupBy` (разбиение) ->
`sequence` (числа по итоговому порядку) -> `totals`. `where` и `groupBy` читают только собственные поля записи; `sortBy`
ещё и арифметические `computed` (они считаются сразу после `where`); `sequence` зависит от порядка строк, поэтому
виден только ячейкам, `cases` и `totals`. Имена `computed` видны ячейкам, `cases`, `totals`.

**Предикат** (закрытый набор): `{field: kind, eq: MATERIAL}`, `ne`, `in: [A, B]`, `{field: unit, isNull: true}`,
`notNull: true`; комбинаторы `{and: [..]}`, `{or: [..]}`, `{not: p}` (каждый единственный ключ предиката). Литерал
типизируется по полю: Enum (значение должно быть членом схемы), Integer, Decimal (сравнение по числу: 1 = 1.00), Boolean,
Date (ISO), String. Нет значения у поля: `eq` и `in` ложны, `ne` истинно.

**`sortBy`**: ключи по порядку, `order` по умолчанию `asc`, `nulls` по умолчанию `last` (пустые идут в конец при любом
`order`, `first` в начало). Нет `sortBy` = порядок источника. Сортировка устойчивая. Порядок значений: числа и Date по
значению, Boolean `false < true`, Enum по порядку значений в схеме, String натурально (числовые куски по числу:
`Вал 2` раньше `Вал 10`; остальное по русскому алфавиту, регистр не учитывается, `java.text.Collator` ru, PRIMARY).

**`groupBy`** (`field` обязательно enum-поле):

```yaml
groupBy:
  field: kind
  order: [ASSEMBLY, PART, STANDARD, OTHER, MATERIAL]   # порядок групп
  titles: {ASSEMBLY: "Сборочные единицы", ...}          # заголовок каждого значения из order
  skipEmpty: true     # по умолчанию: значение без строк группы не даёт (нет заголовка и спейсеров); false оставляет пустую
  omit: []            # значения, которые сознательно отбрасываются
```

Каждое значение enum из схемы должно быть в `order` или `omit` (иначе ошибка контракта в `groupBy.order`): строка не
пропадает молча. Значение не из схемы в `order` / `omit` / `titles` ошибка. Данные вне схемы невозможны: адаптер строит
значения enum из той же схемы; если строка всё же несёт значение вне `order` и `omit`, при сборке бросается
`IllegalStateException`. Внутри группы строки идут в порядке после `where` / `sortBy`. `titles` лишь для значений
`order`. Без `groupBy` таблица плоская (`groupTitle` тогда ошибка).

**`computed`, нумерация**: `name: {sequence: {scope: table|group, start: 1, step: 1}}` даёт Integer `start + step * k`. `scope: table`
сквозная нумерация по всем группам в порядке таблицы (спецификация), `group` счёт заново в каждой группе. Имя не
должно совпадать с полем записи (ошибка `computed.<имя>`), `step` не 0. Использование: `${item.position}`.

#### Арифметика `computed`

Закрытый набор операций над числовыми полями (Integer, Decimal) и числовыми литералами, без языка выражений:

```yaml
computed:
  cost: {multiply: [qty, price], scale: 2, rounding: HALF_UP}   # стоимость = количество * цена
  net:  {subtract: [cost, 1.5]}                                 # операнд: поле записи, другое арифметическое поле или литерал
  each: {divide: [cost, qty], scale: 4, rounding: HALF_UP}
  pcs:  {add: [qty, spare, 10]}
```

| Операция | Операндов | Тип результата |
|---|---|---|
| `multiply`, `add` | 2 и больше | Integer, если все операнды Integer; иначе Decimal |
| `subtract` | ровно 2 (`a - b`) | так же |
| `divide` | ровно 2 (`a / b`) | всегда Decimal; `scale` и `rounding` обязательны |

Операнд это скаляр: число (`10`, `1.5`, `-2`; без точки Integer, с точкой Decimal) или имя поля (имя не начинается с цифры
или знака, поэтому путаницы нет). Расчёт точный: Decimal через `BigDecimal` (`1.005` остаётся `1.005`), Integer через `Long` с
проверкой переполнения. `scale` + `rounding` (значения как в `format.rounding`) округляют Decimal-результат; для
`multiply` / `add` / `subtract` их можно не задавать (результат точный), задаются только вместе; у Integer-результата
`scale` и `rounding` ошибка. Нет значения у операнда (необязательное поле без значения) значит нет значения у результата,
ячейка с `optional: true` пустая, в итогах такая строка не участвует. Деление на ноль это ошибка с номером исходной строки и
её полями (`computed 'each' (divide): division by zero, source row 2 {name=Болт, qty=0, ...}`, `IllegalStateException`).
Арифметика считается для строк, прошедших `where`, до `sortBy` и `groupBy` (строка, значение которой ушло в `omit`,
всё равно считается).

Результат виден ячейкам, `cases`, `sortBy`, `totals` и другим арифметическим полям; НЕ виден `where` и `groupBy` (ошибка
контракта `not available here`). `sequence` не может быть операндом (зависит от порядка, а порядок может зависеть от
арифметики). Цикл зависимостей (`a` читает `b`, `b` читает `a`) это ошибка `computed.<имя>: cyclic computed dependency: a -> b -> a`.
Неизвестное и нечисловое поле операнда ошибка с путём `computed.<имя>.multiply[0]`.

#### Итоги: `totals`

Каждая запись одна строка итога: `label` в колонке `labelColumn`, результат в колонке `valueColumn`, остальные ячейки
строки пусты (с границами, высота `rowHeight`, как у любой строки). Подвал группы стоит после строк данных группы, подвал
таблицы после последней группы и до пустого дозаполнения (`fill: blank`). Несколько итогов одного `scope` идут отдельными
строками в порядке списка.

```yaml
totals:
  - {id: groupCost, scope: group, agg: sum, field: cost, label: "Итого по группе", labelColumn: name, valueColumn: cost,
     format: {pattern: "0.00", locale: ru, rounding: HALF_UP}}
  - {id: totalCost, scope: table, agg: sum, field: cost, label: "Всего", labelColumn: name, valueColumn: cost,
     format: {pattern: "0.00", locale: ru, rounding: HALF_UP}}
  - {id: positions, scope: table, agg: count, label: "Позиций", labelColumn: name, valueColumn: qty}
  - {id: avgPrice, scope: table, agg: avg, field: price, label: "Средняя цена", labelColumn: name, valueColumn: price,
     scale: 2, rounding: HALF_UP, where: {field: price, notNull: true}, format: {pattern: "0.00", locale: ru}}
```

| Ключ | Значение |
|---|---|
| `id` | уникальный в таблице (буквы, цифры, `_`, `-`) |
| `scope` | `group`: по строкам каждой группы (нужен `groupBy`), `table`: по всем строкам таблицы (в группах, как нарисовано: значения из `omit` не входят) |
| `agg` | `sum`, `count`, `min`, `max`, `avg` |
| `field` | поле записи или `computed` (Integer / Decimal); обязателен для `sum` / `min` / `max` / `avg`, у `count` запрещён (считает строки) |
| `label` | текст строки (литерал), не пустой |
| `labelColumn`, `valueColumn` | id колонок, существуют и различны; длинный текст переносится на следующие физические строки колонки, значит выбирайте широкую колонку для подписи |
| `format` | как у bind: `{pattern, locale, rounding}`; проверяется по типу результата (Decimal: `pattern` обязателен; Integer: формата нет, как у bind). Умолчание: Decimal как есть (`toPlainString`), Integer целым |
| `style` | имя стиля или псевдоним `styles`; умолчание `totalText` |
| `where` | предикат (как везде): в агрегат идут только подходящие строки; читает поля записи и `computed`; строки остаются в таблице |
| `skipEmpty` | по умолчанию `true`: нет строк в области (группа пуста / в таблице нет строк) нет и строки итога; `false` итог выводится (sum 0, count 0). Область считается до `where` итога: пустой результат `where` даёт sum 0 / count 0, а не пропуск |
| `scale`, `rounding` | только для `avg`, оба обязательны: среднее делится `BigDecimal` и округляется явно |

Типы: `sum` / `min` / `max` дают тип поля (Integer остаётся Integer, Decimal Decimal), `avg` Decimal, `count` Integer.
`sum` Decimal точная (`0.1 + 0.2 = 0.3`, масштаб = наибольший из слагаемых), без округления. Строки без значения поля в
`sum` / `min` / `max` / `avg` не участвуют (`count` считает строки). `min` / `max` / `avg` без единого значения дают пустой
текст ячейки. Переполнение `Long` в `sum` Integer это ошибка.

**Раскладка.** Строки итога привязываются цепочкой `keepWithNext` к строке, которую итожат: последняя строка данных
группы (для группы без строк заголовок группы) плюс все строки подвала образуют один блок; подвал таблицы привязан к
последней строке таблицы (через подвал группы тоже). Итог не окажется один вверху страницы: если подвал не помещается,
на следующую страницу вместе с ним уходит последняя строка данных. Блок длиннее пустой страницы
(`1 + число строк подвала`) вызвал бы `LayoutOverflowException` (на практике невозможно: подвал это единицы строк).
Страницы, где итогов нет, раскладываются как раньше (цепочки и golden-файлы не менялись).

**`cases`** (ячейка строки): `cases: [{where: <предикат>, text|bind, format, optional}, ...]`. Выигрывает первый вариант,
чей `where` истинен; иначе собственное содержимое ячейки (`text` или `bind` + `format` + `optional`, по сути `else`);
если нет ни варианта, ни собственного содержимого, ячейка пустая. `optional` и `format` задаются на каждый вариант
отдельно. Кроме `where` и содержимого вариант других ключей не имеет (`align` и `style` общие для ячейки).

**`format`** на Decimal-bind (как раньше): `{pattern: "0.##", locale: ru, rounding: HALF_UP}`. Количество в
спецификации:

```yaml
quantity:
  style: data
  cases:
    - where: {field: kind, eq: MATERIAL}              # материалы: до 2 знаков, хвостовые нули срезаны, запятая
      bind: "${item.quantity}"
      format: {pattern: "0.##", locale: ru, rounding: HALF_UP}
  bind: "${item.quantity}"                            # остальные виды: целое, HALF_UP
  format: {pattern: "0", rounding: HALF_UP}
note:                                                 # единица только у материалов
  style: data
  cases:
    - where: {field: kind, eq: MATERIAL}
      bind: "${item.unit}"
      optional: true
```

**Что не покрыто:** выражения общего вида (`${a} * ${b}`: только закрытые операции `multiply` / `add` / `subtract` /
`divide`), вложенные итоги (подитоги подгрупп), накопительные итоги (running total), итоги с текстом из данных
(`label` литерал, подстановки значений нет), итог по Date / String (`min` / `max` только числа), `group` итоги без
`groupBy`, правое выравнивание чисел (во flow-таблице есть только `left` / `center`), сортировка по `sequence`,
группировка по не-enum полю, вложенные группы, подстановка значений в `titles`, несколько таблиц потока на шаблон.
Значение итога не часть записи `item`: ячейки строк данных его не видят.

**Соответствие IR** (`report-ir` `FlowTables.build`): `columns[]` в `IrColumn` (`stick: first` в
`stickToFirstRow`, `last` в `stickToLastRow`), `rowHeight` в `IrTable.rowHeight`, `header` в `IrTableHeader`
(`height`, `repeat`; ячейка: `rotate: 90` в `VERTICAL_BOTTOM_TO_TOP`, `lines` в `manualLines`; текст шапки только
здесь), `groupTitle` и `keep.titleChain` в `IrGroupTitle` (`column`, `style`, `align`, `spacerBefore`,
`spacerAfter`, `keepWithRows`), `fill: blank` в `IrTable.fillBlank`, ячейки строки в `IrCell` по bind-ам записи
`item`, `totals` в `IrTotalRow` (`scope: group` в `IrGroup.footer`, `table` в `IrTable.footer`). Движок строит из
`IrGroupTitle` цепочку `keepWithNext` и пустые строки, из `fillBlank` дозаполнение, из подвалов строки итогов и цепочку
к итожимой строке.

Пример из `gost-spec.yaml` (спецификация ГОСТ 2.106, колонки 6+6+8+70+63+10+22 = 185 мм):

```yaml
- id: body
  type: flow
  table:
    rowHeight: 8
    fill: blank                  # cover each page down to the frame with blank bordered rows
    keep: {titleChain: true}     # spacers + title + first data line are never split by a page break
    styles: {head: tableHeader, data: tableText, groupTitle: groupHeader}
    # GOST R 2.106-2019 p.1 order. A kind without items makes no group (skipEmpty), no sortBy = source order.
    groupBy:
      field: kind
      order: [ASSEMBLY, PART, STANDARD, OTHER, MATERIAL]
      titles:
        ASSEMBLY: "Сборочные единицы"
        PART: "Детали"
        STANDARD: "Стандартные изделия"
        OTHER: "Прочие изделия"
        MATERIAL: "Материалы"
      skipEmpty: true
    # one running number over all groups (scope: table), 1, 2, 3, ...
    computed:
      position: {sequence: {scope: table, start: 1, step: 1}}
    columns:
      - {id: format, width: 6, stick: first, align: center}
      - {id: zone, width: 6, stick: first, align: center}
      - {id: position, width: 8, stick: first, align: center}
      - {id: designation, width: 70}
      - {id: name, width: 63}
      - {id: quantity, width: 10, stick: last, align: center}
      - {id: note, width: 22, stick: last, align: center}
    header:
      height: 15
      repeat: true               # on every page
      cells:
        format: {text: "Формат", rotate: 90, style: head}
        zone: {text: "Зона", rotate: 90, style: head}
        position: {text: "Поз.", rotate: 90, style: head}
        designation: {text: "Обозначение", style: head}
        name: {text: "Наименование", style: head}
        quantity: {text: "Кол.", rotate: 90, style: head}
        note: {text: "Примечание", lines: ["Приме-", "чание"], style: head}
    groupTitle: {column: name, style: groupTitle, align: center, spacerBefore: 2, spacerAfter: 1}
    row:
      cells:
        format: ~
        zone: ~
        position: {bind: "${item.position}", style: data}
        designation: {bind: "${item.designation}", optional: true, style: data}
        name: {bind: "${item.name}", style: data}
        # materials: up to 2 decimals, trailing zeros trimmed, decimal comma (1,5; 0,35; 3). Every other kind
        # is a whole count (rounded half up): Loodsman counts them in pieces.
        quantity:
          style: data
          cases:
            - where: {field: kind, eq: MATERIAL}
              bind: "${item.quantity}"
              format: {pattern: "0.##", locale: ru, rounding: HALF_UP}
          bind: "${item.quantity}"
          format: {pattern: "0", rounding: HALF_UP}
        # the unit is shown for materials only (other kinds are pieces and get no unit)
        note:
          style: data
          cases:
            - where: {field: kind, eq: MATERIAL}
              bind: "${item.unit}"
              optional: true
```

**Миграция (этап 3).** Раньше код задавал группы (`FlowGroup`, `GROUPS` в `Specification.kt`), нумерацию (`item.position`) и
текст количества (`item.quantityText`, `formattedQuantity()`), а `unit` отдавался только материалам. Теперь запись `item`
несёт сырые поля (`designation`, `name`, `kind`, `quantity` Decimal, `unit`), а группировку, нумерацию, формат
количества и правило «единица только у материалов» описывает YAML. `FlowTables.build(spec, schema, rows, path)` принимает
плоский список записей (`FlowGroup` и `buildRows` удалены), сам проверяет контракт и применяет правила.
`formattedQuantity()` удалена из `report-data`; её копия `legacyFormattedQuantity` живёт в тестах как эталон
(`SpecificationQuantityFormatTest`). Известные отличия YAML-формата от старого кода только для отрицательных и нечисловых
значений (недостижимы для данных Loodsman), см. комментарий теста. Свой шаблон со старым контрактом: `item.position` объявите
в `computed`, `item.quantityText` замените ячейкой с `format`, `groupTitle` без `groupBy` теперь ошибка.

**Ограничения.** Параметры `${param.x}`
в таблице потока не поддерживаются, ширины и высоты числами. Выравнивание `right` в таблице потока нет, поворот
только 0 и 90. Таблица рисуется от левого поля листа на каждой странице; блок `flow` с таблицей и блоки слотов
уже участвуют в расстановке, но не рисуются самим `flow`. В `TemplateMain` таблица рисуется (см. ниже).

**`TemplateMain` и таблица потока.** Если в шаблоне есть `flow` с `table:`, строки берутся из корня `item:` файла
`--data` (последовательность карт), таблица проходит через настоящий движок (перенос, пагинация, дозаполнение),
число страниц определяет пагинация (`--pages` игнорируется), остальные блоки шаблона дорисовываются на каждую
страницу. Группы, сортировка, нумерация и форматы работают как в спецификации: поле `groupBy.field` читается из файла как
enum без тегов, его домен задаёт сам шаблон (`order` + `omit`), значение вне домена даёт ошибку контракта
`...groupBy.order`, а не потерянную строку (тег `!enum` тоже есть, домен тогда значения файла). Колонка с 1 и 2.5 в разных
строках становится Decimal. Пример: `purchased-list.yaml` + `purchased-list-data.yaml` (стиль «Ведомость покупных
изделий», не точная форма ЕСКД). Блоки с id слотов (`stamp`, `leftMargin`, ...) в таком
шаблоне лучше не называть: движок считает их своими. Рамку листа движок рисует сам по полям, рамка из шаблона
ляжет поверх.

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
Таблица потока (тело спецификации) описана блоком `body` (см. «Таблица потока»); его id не слот.

## Известные ограничения

- Поворот текста в `TemplateMain` только 0 и 90 (в Layout IR нет других ориентаций).
  YAML принимает 270, адаптер на нём падает с понятной ошибкой.
- `flow` без `table:` в `TemplateMain` ничего не рисует; с `table:` рисует через движок (строки из `item:` в `--data`).
- Один шрифт (GOST Type B), поле `style` в `TemplateMain` игнорируется.
- `repeat.from` не резолвится.
- Блоки шаблона не из слотов участвуют в расстановке основного движка, но не рисуются.
- Bind только скалярный: `repeat.from` не резолвится. Выражений нет (только закрытая арифметика `computed` и `totals`, см. «Данные таблицы»); вложенных и накопительных итогов нет.
- Лист нулевой или отрицательной ширины и высоты даёт ошибку валидации.

## Тесты

- `report-template`: загрузка, валидация, привязки, поворот, наборы, область потока; `DataTest` (типы, схема,
  форматы, `--data`), `TemplateContractTest` (контракт, `format`, `optional`, пути ошибок).
- `report-template`: `FlowTableTest` (загрузка, неизвестные ключи, структура, ширина, стили, контракт строки), `FlowShapingTest`
  (where / sortBy / groupBy / computed / cases / format: загрузка, валидация, контракт, прогон на данных, `DataYaml`),
  `FlowTotalsTest` (арифметика `computed`: типы, цикл, деление на ноль, точность; `totals`: sum / count / min / max / avg,
  `where`, `skipEmpty`, контракт и валидация с путями).
- `report-ir`: `FlowTablesTest` (YAML-описание в `IrTable`: стили, шапка, optional).
- `report-ir`: `FlowTotalsIrTest` (`totals` в `IrGroup.footer` / `IrTable.footer`, стили, колонки).
- `report-layout`: `FlowTableLayoutTest` (спейсеры, цепочка, `fillBlank`, `header.repeat`), `TotalRowLayoutTest` (цепочка итога,
  итог внизу страницы, дозаполнение после подвала, переполнение; свой измеритель текста).
- `reports/specification`: `SpecificationTableParityTest` (`IrTable` из YAML равен старой таблице,
  `LegacySpecificationTable` только в тестах), `SpecificationQuantityFormatTest` (формат количества и правило единицы против
  старого форматтера).
- `report-cli`: `TemplateMainTest` (смоук `--data`, ошибка контракта до раскладки, таблица потока с пагинацией).
- `report-geometry`: `AnchorTest` для `placeOrigin` и `resolveAnchor`.
- `report-ir`: `FrameSpecsTemplateParityTest` сверяет `FrameSpecs` из YAML со старыми
  константами (`LegacyFrameSpecs.kt`, только для этой сверки).
- `reports/specification`: `StaticBlocksGoldenTest` с эталонами в `src/test/resources/golden`.
  Каталог `reports/` попадает под правило `.gitignore` строки `/reports/`, новые файлы
  добавляются через `git add -f`.
