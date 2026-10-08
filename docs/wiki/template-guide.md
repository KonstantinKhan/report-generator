# Как создавать шаблон с нуля (практикум)

Пошаговое руководство для инженера, который собирает много YAML-шаблонов и гоняет их через
`./gradlew :report-cli:runTemplate`. Это практикум (цель, YAML, что увидеть, типичная ошибка), а не справочник:
по полям и API смотрите [template-yaml.md](template-yaml.md).

Каждый шаг лежит в файле `report-cli/src/main/resources/templates/tutorial/NN-*.yaml` и реально запускался.
Числа в «что увидеть» взяты из полученных SVG (команда проверки: `grep '<rect\|<line\|<text' файл.svg`).
Если вы меняете шаблон, а координаты в тексте расходятся, верьте запуску, а не тексту.

## 0. Что нужно знать за минуту

**Модель.** Лист (`sheet`) → блоки (`blocks`) → привязки (`attach`). Блок это `frame`, `rect`, `table`, `text`
или `flow`; наборы (`blocksets`) это переиспользуемые группы блоков. Всё в мм, ось x вправо, y вниз, начало в левом
верхнем углу листа.

**Три слоя шаблона**

| Слой | Что описывает | Где в YAML |
|---|---|---|
| Геометрия | где и какого размера лежат рамки, штампы, надписи | `sheet`, `blocks`, `blocksets`, `attach`, `when`, `reserves` |
| Данные | откуда берётся текст и как он форматируется | `bind: "${doc.x}"`, `format`, `optional`, схема данных, файл `--data` |
| Таблица-поток | основная таблица с произвольным числом строк и страниц | блок `type: flow` с `table:` |

**Где лежат файлы.** Шаблоны для CLI: `report-cli/src/main/resources/templates/`. Ваш шаблон может лежать где
угодно: путь передаётся аргументом. Для боевого движка спецификации существует только
`report-ir/src/main/resources/templates/gost-spec.yaml` (см. шаг 12).

**Как запустить** (из корня репозитория; пути внутри `-Pargs` считаются от `report-cli/`):

```
./gradlew :report-cli:runTemplate -q -Pargs="src/main/resources/templates/tutorial/01-sheet.yaml output"
./gradlew :report-cli:runTemplate -q -Pargs="my.yaml output --pages 3 --data my-data.yaml"
```

- Аргументы: `[шаблон.yaml] [папка-вывода] [--pages N] [--data данные.yaml]`. Аргументы режутся по пробелам: путь с пробелом не пройдёт.
- Результат: `<name>-page-N.svg` на каждую страницу и `<name>.pdf`, где `name` это поле `name:` из YAML. Печатаются строки
  `wrote /абсолютный/путь`. Папка вывода по умолчанию `report-cli/output` (в `.gitignore`).
- Страница 1 строится как `first`, остальные как `rest` (поле `when`). `--pages N` задаёт число страниц; если в шаблоне есть
  таблица-поток, число страниц определяет пагинация, а `--pages` игнорируется (проверено: `--pages 5` на шаблоне шага 10 дало 2 SVG и PDF).
- Без `--data` берутся демо-данные: `doc.designation`, `doc.name`, `doc.mass`, `doc.issued`, `doc.approvedBy` (без значения) и `page.number`, `page.total`.
- Ошибка шаблона: список `путь: сообщение` в stderr и код выхода 1, файлы не пишутся.

Цикл работы: правим YAML → запускаем (1-2 секунды на Gradle с демоном) → смотрим SVG в браузере или `grep` координат.

Ниже во всех командах для краткости: `RUN() { ./gradlew :report-cli:runTemplate -q -Pargs="$*"; }` и `T=src/main/resources/templates/tutorial`.

## Шаг 1. Лист

**Цель:** задать формат, ориентацию, поля и (по желанию) свои точки. Файл `tutorial/01-sheet.yaml`.

```yaml
name: t01-sheet

sheet:
  format: A4
  orientation: portrait
  margins: {top: 5, right: 5, bottom: 5, left: 20}
  anchors:
    mark: {x: 100, y: 50}      # своя точка: 100 мм вправо, 50 мм вниз от левого верхнего угла листа

blocks:
  # маркер, чтобы увидеть якорь глазами: квадрат 10x10, центр которого стоит в sheet.mark
  - id: marker
    type: rect
    size: {width: 10, height: 10}
    attach: {self: center, to: sheet.mark}
```

```
RUN $T/01-sheet.yaml output
```

**Что увидеть:** SVG `210.0mm x 297.0mm` (`viewBox="0 0 210.0 297.0"`), белая подложка и один квадрат
`<rect x="95.0" y="45.0" width="10.0" height="10.0" ... stroke-width="0.25"/>`: центр в (100, 50), линия тонкая (0.25) по умолчанию у `rect`.

- `format`: `A4|A3|A2|A1|A0` или `width:`+`height:` вместо него. `orientation: landscape` меняет стороны местами.
- Поля `margins` задают область контента (лист минус поля): отсюда якоря `sheet.contentTopLeft` и ещё три угла.
- Пустой лист (`blocks: []`) допустим: получится только белая страница.

**Типичная ошибка:** ждать, что поля сдвинут блоки сами. Не сдвигают. Блок без `attach` встаёт в `sheet.topLeft` (0, 0),
поля учитывает только привязка к `sheet.content*` (шаг 2 и 4).

## Шаг 2. Рамка по области контента

**Цель:** нарисовать рамку листа и понять, чем `frame` отличается от `rect`. Файл `tutorial/02-frame.yaml`.

```yaml
blocks:
  - id: frame
    type: frame                # толстая линия по умолчанию
    size: {width: 185, height: 287}
    attach: {self: topLeft, to: sheet.contentTopLeft}

  # для сравнения: rect того же вида, тонкий по умолчанию; сдвинут на 10 мм внутрь
  - id: inner
    type: rect
    size: {width: 165, height: 267}
    attach: {self: topLeft, to: frame.topLeft, offset: {x: 10, y: 10}}
```

**Размер считается руками:** `width = ширина листа − left − right`, `height = высота листа − top − bottom`.
A4 книжная с полями 20/5/5/5: 210−20−5 = **185**, 297−5−5 = **287**. A3 альбомная (420×297) с теми же полями: **395 × 287**
(проверено запуском `sheet-frame.yaml`: `<rect x="20.0" y="5.0" width="395.0" height="287.0">`). Автоматического «размер = область
контента» нет.

**Что увидеть:**

```
<rect x="20.0" y="5.0" width="185.0" height="287.0" ... stroke-width="0.71"/>
<rect x="30.0" y="15.0" width="165.0" height="267.0" ... stroke-width="0.25"/>
```

`frame` и `rect` рисуются одинаково (контур без заливки), разница только в линии по умолчанию: `frame` 0.71 мм, `rect` 0.25 мм.
`thickness:` принимает `thin`, `thick` или число в мм. Надпись внутри рамки это отдельный блок `text` (шаг 4).

**Типичная ошибка:** неверно посчитанный `size`. Рамка тогда выйдет за область контента или не дойдёт до неё, а всё, что привязано к
`frame.bottomRight`, поедет вместе с ней.

## Шаг 3. Основная надпись: таблица

**Цель:** собрать штамп из колонок и рядов, объединить ячейки, задать границы, текст и `bind`. Файл `tutorial/03-stamp.yaml`.

```yaml
  # штамп 110 x 26 мм: 20+25+20+45 по ширине, 10+8+8 по высоте
  - id: stamp
    type: table
    columns: [20, 25, 20, 45]
    rows:
      - height: 10
        cells:
          - {text: "Обозн.", align: center}
          - {bind: "${doc.designation}", span: 3, align: center, fontSize: 14}
      - height: 8
        cells:
          - {text: "Наимен.", rowSpan: 2, align: center}      # ячейка тянется на 2 ряда
          - {bind: "${doc.name}", span: 3, borders: {bottom: thin}}
      - height: 8
        cells:                                                # колонка 0 занята rowSpan сверху: ячейки только для 1..3
          # общая граница рисуется, если её просит ЛЮБОЙ из соседей, и побеждает толстая:
          # чтобы получить тонкую линию, тонкой её надо назвать с обеих сторон (top у нижних ячеек = bottom у верхней)
          - {text: "Лист", align: center, borders: {top: thin, right: thin}}
          - {bind: "${page.number}", align: center, borders: {top: thin, left: thin, right: thin}}
          - {bind: "${page.total}", align: center, borders: {top: thin, left: thin}}
    attach: {self: bottomRight, to: frame.bottomRight}        # угол штампа вкладывается в угол рамки
```

(Выше только блок `stamp`; `sheet` и `frame` те же, что в шаге 2.)

**Правила сетки**

- `columns` ширины в мм, ширина таблицы = их сумма, высота = сумма `height` рядов.
- Каждый ряд обязан покрыть все колонки ровно один раз: свои `span` + колонки, занятые `rowSpan` сверху. В ряду с `rowSpan` сверху
  такую колонку **пропускают** (в третьем ряду выше ячеек три, а не четыре).
- Ячейка: скаляр (= `text`), `~` (пустая) или словарь (`text`/`bind`, `span`, `rowSpan`, `align`, `fontSize`, `rotate`, `borders`, ...).
- `text` всегда литерал; значение из данных даёт только `bind` (шаг 8).

**Границы: правило пары.** По умолчанию каждая сторона `thick` (0.71). Общее ребро двух ячеек рисуется, если его просит хотя бы одна
из сторон; если стороны просят разное, рисуются обе линии и видна толстая. Проверено отдельным файлом `tutorial/03b-borders.yaml`
(таблица `borders: none`): тонкая слева направо против толстой даёт две линии на одном x (`stroke-width` 0.25 и 0.71), одиночная
тонкая или одиночная толстая даёт одну. Вывод для практики: тонкую внутреннюю линию задавайте **с обеих сторон**
(`bottom: thin` у верхней ячейки и `top: thin` у нижней), так и сделано в `gost-spec.yaml`.

**Что увидеть (`03-stamp`):** штамп занимает x 95..205, y 266..292 (110 × 26, угол в углу рамки 205, 292). Обозначение по умолчанию
демо-данных `AAA.00.000` (кегль 14 pt = `font-size="4.94"`), `Лист` и `1` в нижнем ряду. Тонкие линии 0.25 только по y=284 (от x=114.88
до 205.12) и по x=140 и x=160 в нижнем ряду; остальное 0.71.

**Типичные ошибки**

- Не хватает ячеек в ряду: `blocks[0].rows[0].cells: cell spans add up to 2, table has 3 columns` (файл `errors/06-row-cover.yaml`).
- Тонкая граница «не рисуется тонкой»: забыли указать её на соседней ячейке (см. правило пары).

## Шаг 4. Привязка к другим блокам

**Цель:** ставить блоки относительно друг друга. Читается так: точка `self` этого блока ставится в точку `to`, плюс `offset`
(оси листа). Файл `tutorial/04-attach.yaml`. Каждый приём ниже проверен координатами.

| Приём | YAML | Результат |
|---|---|---|
| Внутрь (один и тот же угол) | `a`: `{self: topLeft, to: frame.topLeft, offset: {x: 10, y: 10}}` | `a` в (30, 15), 60 × 30 |
| Наружу (противоположный угол) | `b`: `{self: topLeft, to: a.topRight, offset: {x: 5, y: 0}}` | `b` в (95, 15): впритык справа + зазор 5 |
| Под блоком | `c`: `{self: topLeft, to: a.bottomLeft, offset: {x: 0, y: 5}}` | `c` в (30, 50) |
| По осям раздельно | `d`: `x: {self: topLeft, to: b.topLeft}`, `y: {self: bottomLeft, to: c.bottomLeft}` | `d` в (95, 62), 20 × 8: x от `b`, низ вровень с `c` |
| Центр в центр | `aLabel` (`text`): `{self: center, to: a.center}` | подпись внутри `a` |
| К ячейке | `e`: `{self: topLeft, to: "grid.cell[1,2].bottomLeft", offset: {x: 0, y: 3}}` | `e` в (80, 99) |
| К границе колонки/ряда | `f`: `x: {to: "grid.col[1].left"}`, `y: {to: "grid.row[0].bottom"}` | `f` в (50, 88) |
| В столбик, без кавычек | `g`: `self: bottomRight`, `to: grid.cell[0,1].topLeft` | `g` в (40, 70) |

Таблица `grid` (колонки 20, 30, 30; ряды 8, 8) стоит в (30, 80) и занимает 80 × 16.

Полный набор якорей: 9 стандартных (`topLeft topCenter topRight middleLeft center middleRight bottomLeft bottomCenter bottomRight`) у
любого блока; `sheet.*` (9 стандартных по полному листу, 4 `sheet.content*` по области контента, свои из `sheet.anchors`);
у таблицы `col[i].left|right`, `row[j].top|bottom`, `cell[r,c].<9 якорей>`. Середин области контента нет, только четыре угла.

**Типичная ошибка: YAML в одну строку с `[`.** Квадратная скобка внутри `{...}` ломает разбор. В документации упомянут только
`cell[r,c]`, но ломается и `col[1]`, и `row[0]`. Реальный текст (так выглядит ошибка, если написать `x: {self: topLeft, to: grid.col[1].left}`):

```
template error:
  invalid YAML: while parsing a flow mapping
 at line 69, column 10:
          x: {self: topLeft, to: grid.col[1].left}
             ^
expected ',' or '}', but got [
 at line 69, column 38:
```

Лечение: взять значение в кавычки (`to: "grid.col[1].left"`) или писать привязку в столбик.

**Ещё три правила**

- Цель привязки должна быть видна минимум на тех же страницах (`when`), граф привязок без циклов (тексты ошибок в шаге 11).
- Блок `use:` (вызов набора) привязывается так же, как обычный (шаг 6).
- «Разбить» ячейку нельзя: делайте сетку мельче и объединяйте `span`/`rowSpan` или вкладывайте таблицу, привязанную к `внешняя.cell[r,c].topLeft` (см. раздел в [template-yaml.md](template-yaml.md)).

## Шаг 5. Боковая повёрнутая таблица

**Цель:** подписи вдоль левого поля, текст снизу вверх. Файл `tutorial/05-side.yaml`.

```yaml
  - id: side
    type: table
    rotate: 90
    columns: [60, 40, 35]
    rows:
      - height: 5
        cells:
          - {text: "Инв. № подл.", align: center}
          - {text: "Подп. и дата", align: center}
          - {text: "Взам. инв. №", align: center}
      - height: 7
        cells: [~, ~, ~]
    # правый край стоит на левом крае рамки, низ совпадает с низом рамки: блок висит снаружи слева
    attach: {self: bottomRight, to: frame.bottomLeft}
```

Таблицу описываем как обычную (колонки слева направо), `rotate: 90` поворачивает её против часовой стрелки: **первая колонка уходит вниз**,
ряды идут справа налево (первый ряд снаружи, последний прижат к рамке), текст читается снизу вверх. Блок занимает повёрнутый
охватывающий прямоугольник (12 мм в ширину, 135 в высоту), поэтому привязываем по его якорям в осях листа.

**Что увидеть:** блок x 8..20, y 157..292; ряд 0 (5 мм) занимает x 8..13, ряд 1 (7 мм) x 13..20; колонка 0 (60 мм) внизу y 232..292, колонка 1
y 192..232, колонка 2 y 157..192. Текст: `transform="translate(11.76, 271.57) rotate(-90)"`.

**Типичные ошибки:** ждать, что колонки пойдут сверху вниз (идут снизу вверх); поворот 270 для таблицы разрешён, но только если у каждой ячейки `rotate: 90` (итог 0 или 90), а для **текста** нет (шаг 11); `rotate` у `text`-блока крутит только глифы, а не размер блока.

## Шаг 6. Повторное использование: blockset

**Цель:** описать группу блоков один раз и вызывать много раз с параметрами. Файл `tutorial/06-blockset.yaml`.

```yaml
blocksets:
  labelBox:
    params: {w: 40, h: 12, label: null}      # null = параметр обязателен, остальные имеют умолчание
    ports: {tail: box.bottomLeft}
    blocks:
      - {id: box, type: rect, size: {width: "${param.w}", height: "${param.h}"}}
      - id: caption
        type: text
        text: "${param.label}"
        align: center
        size: {width: "${param.w}", height: 6}
        attach: {self: center, to: box.center}

  pair:
    params: {left: null, right: null}
    blocks:
      - {id: l, use: labelBox, args: {label: "${param.left}"}}
      - {id: r, use: labelBox, args: {label: "${param.right}", w: 60}, attach: {self: topLeft, to: l.topRight}}

blocks:
  - id: one
    use: labelBox
    args: {label: "Один"}                    # w и h из умолчаний: 40 x 12
    attach: {self: topLeft, to: frame.topLeft, offset: {x: 10, y: 10}}
  - id: two
    use: labelBox
    args: {label: "Два", w: 70, h: 20}
    attach: {self: topLeft, to: one.tail, offset: {x: 0, y: 5}}      # порт набора как обычный якорь
  - id: both
    use: pair
    args: {left: "Лево", right: "Право"}
    attach: {self: topLeft, to: two.bottomLeft, offset: {x: 0, y: 5}}
```

**Что увидеть:** `one` (30, 15, 40 × 12); `two` (30, 32, 70 × 20), потому что порт `one.tail` = (30, 27) + 5; `both`: `l` (30, 57, 40 × 12) и
`r` (70, 57, 60 × 12), то есть параметр `w: 60` переопределил умолчание, а `r` встал справа от `l` внутри набора.

**Правила**

- Определения `blocksets:` только на верхнем уровне файла; вложенные вызовы разрешены, рекурсия нет.
- Блок без `attach` внутри набора встаёт в начало набора (отсюда `box` и `l` без привязки); положение набора на листе задаёт только `attach` на вызове.
- Внутри набора блоки видят только друг друга по `id`, снаружи видны якоря охватывающего прямоугольника и `ports` (имена портов не должны совпадать со стандартными якорями).
- `${param.x}` подставляется в числа, `text` и `args`; **в `bind` нельзя** (путь статичен).
- `when` вызова объединяется по И с `when` детей.

**Когда выносить в набор:** штамп, который нужен в двух видах (первая страница и продолжение) с общей шапкой; повторяющаяся подписная ячейка;
всё, что вы копируете между шаблонами. Образец: `headerStrip` в `gost-spec.yaml` (общая полоса «Изм / Лист / № докум. / Подп. / Дата» для двух штампов).

## Шаг 7. Страницы

**Цель:** разные блоки на первой и следующих страницах. Файл `tutorial/07-pages.yaml`.

```yaml
  - id: firstStamp                     # только первая страница: штамп 40 мм
    type: table
    when: first
    reserves: true
    columns: [100, 85]
    rows:
      - {height: 25, cells: [{bind: "${doc.designation}", align: center}, {text: "Первый лист", align: center}]}
      - {height: 15, cells: [{bind: "${doc.name}"}, {bind: "${page.number}", align: center}]}
    attach: {self: bottomRight, to: frame.bottomRight}

  - id: restStamp                      # страницы 2+: штамп 15 мм
    type: table
    when: rest
    reserves: true
    columns: [100, 75, 10]
    rows:
      - height: 15
        cells:
          - {bind: "${doc.designation}", align: center}
          - {text: "Продолжение", align: center}
          - {bind: "${page.number}", align: center}
    attach: {self: bottomRight, to: frame.bottomRight}
```

```
RUN $T/07-pages.yaml output --pages 3
```

**Что увидеть:** три SVG. Страница 1: `Первый лист`, `1`, верхняя линия штампа на y=252 (292−40). Страницы 2 и 3: `Продолжение`, номера `2` и `3`,
верхняя линия на y=277 (292−15), надписи `Первый лист` нет.

- `when`: `first`, `rest`, `all` (по умолчанию).
- `reserves: true` вычитает блок из области потока (область контента минус такие блоки, видимые на странице; побеждает ближайшая верхняя грань,
  срезается только низ). На шаге 7 потока нет, поэтому эффект виден на шаге 9 и в шаге 10 (на первой странице поток кончается над штампом: y=267).
- Привязать блок можно только к блоку, видимому как минимум на тех же страницах: `all` к `first` нельзя (текст ошибки в шаге 11).
- Без потока `--pages N` просто строит N копий. С потоком число страниц считает движок.

**Типичная ошибка:** забыть `reserves: true` у штампа. Поток тогда покроет и область под штампом.

## Шаг 8. Данные: корни, типы, контракт, `--data`

**Цель:** вывести в блоки значения разных типов. Файлы `tutorial/08-data.yaml` и `tutorial/08-data-data.yaml`.

Каждый `bind` это один путь `${корень.поле}` с корнем `doc` (данные документа), `page` (`page.number`, `page.total`, объявлены всегда), `item` (строка таблицы-потока,
шаг 9) или `line` (`line.number`, номер физической строки таблицы, только в ячейках строк потока, шаг 9г). Выражений нет: `${a}/${b}` в одном bind нельзя. Типы: `String`, `Integer`, `Decimal`, `Date`, `Boolean`, `Enum`; `List` и `Record` в bind недопустимы.

Данные (`08-data-data.yaml`):

```yaml
doc:
  designation: ВП.01.002
  mass: 7.255                 # Decimal
  issued: 2026-03-09          # Date
  code: !str "007"            # String (без тега: Integer 7)
  status: !enum DRAFT         # Enum, домен = значения из файла
  checker: ~                  # String без значения (только для optional: true)
```

Блоки (`08-data.yaml`, фрагменты):

```yaml
  - id: mass                             # Decimal + format: до 2 знаков, запятая
    type: text
    bind: "${doc.mass}"
    format: {pattern: "0.##", locale: ru, rounding: HALF_UP}
    size: {width: 100, height: 6}
    attach: {self: topLeft, to: designation.bottomLeft}

  - id: issued                           # Date + format
    type: text
    bind: "${doc.issued}"
    format: {pattern: "dd.MM.yyyy"}
    ...
  - id: checker                          # поле объявлено (~), значения нет: optional даёт пустую строку
    type: text
    bind: "${doc.checker}"
    optional: true
    ...
```

```
RUN $T/08-data.yaml output --data $T/08-data-data.yaml
```

**Что увидеть** (тексты в порядке блоков): `ВП.01.002`, `7,26` (7.255, `0.##`, HALF_UP, запятая), `09.03.2026`, `007`, `DRAFT`, затем (после пустого `checker`) `1`
(`page.number`). Для `checker` текстового элемента нет вовсе.

**Типы из файла.** `3` Integer, `1.5` Decimal, `2026-01-31` Date, `true|false` Boolean, иное String. Тег принудительно задаёт тип:
`!str`, `!int`, `!decimal`, `!date`, `!bool`, `!enum`. `~` это String-поле без значения. Карта = `Record`, последовательность карт = `List`.
Корень `item:` это последовательность карт, строки таблицы-потока (шаг 9).

**Схема и контракт.** Схема выводится из файла (или берётся демо). Перед раскладкой проверяется контракт: каждый bind обязан указывать на скалярное
поле схемы, `format` обязан подходить типу (`pattern` обязателен для Decimal/Date; для остальных типов format запрещён). Все ошибки печатаются разом,
с YAML-путями. Значение проверяется отдельно, при раскладке: путь есть в схеме, а значения нет, это ошибка, если нет `optional: true`.

**Как читать ошибки контракта**

```
./gradlew ... 08-data.yaml output                 # без --data: демо-схема не знает doc.code, doc.status, doc.checker
template error:
  blocks[4].bind: unknown field 'doc.code' (doc has: designation, name, mass, issued, approvedBy)
  blocks[5].bind: unknown field 'doc.status' (doc has: designation, name, mass, issued, approvedBy)
  blocks[6].bind: unknown field 'doc.checker' (doc has: designation, name, mass, issued, approvedBy)
```

`blocks[4]` это пятый блок списка `blocks:` (счёт с нуля), в скобках перечислены поля схемы. Подсказка `did you mean` появляется при похожем имени
(`errors/01-typo-bind.yaml`, `doc.designaton`):

```
  blocks[0].bind: unknown field 'doc.designaton' (doc has: designation, name, mass, issued, approvedBy), did you mean 'doc.designation'?
```

**Типичные ошибки**

- Поле есть в схеме, но не в данных, нет `optional: true` (`errors/09-missing-value.yaml`, демо-поле `approvedBy` объявлено без значения):
  `error: no value for bind '${doc.approvedBy}' (mark the cell 'optional: true' to render it empty)`
- `format` на String (`errors/02-bind-format.yaml`): `blocks[0].format: format is not supported for String (only Decimal and Date)`.
- `${a}/${b}` в одном bind (`errors/10-expr-in-text.yaml`): `blocks[0].bind: bind must be a single path expression like ${doc.designation} (roots: doc, page, item, line), got '${doc.designation}/${doc.name}'`.
- Число `007` без `!str` станет Integer 7.

## Шаг 9. Таблица-поток

Основная таблица листа: сколько строк, столько страниц. Блок `type: flow` с `table:`. Что рисовать (колонки, шапка, стили) и как данные попадают
в таблицу (фильтр, сортировка, группы, нумерация, формат, итоги) описывает YAML; перенос слов, пагинацию и дозаполнение страницы делает код (`report-layout`).
В CLI поток идёт через настоящий движок, строки берутся из корня `item:` файла `--data`.

### 9а. Каркас: колонки, шапка, строки

Файлы `tutorial/09-flow.yaml` + `tutorial/09-flow-data.yaml`. Ширины колонок 10+85+25+20+45 = 185 мм = ширина области контента.

```yaml
blocks:
  # штамп снизу; reserves: true вычитает его из области потока
  - id: titleBlock
    type: table
    reserves: true
    columns: [185]
    rows:
      - {height: 15, cells: [{bind: "${doc.designation}", align: center, fontSize: 14}]}
    attach: {self: bottomRight, to: sheet.contentBottomRight}

  - id: body
    type: flow                          # без size, attach, reserves; один на шаблон
    table:
      rowHeight: 8                      # высота строки, мм
      fill: blank                       # дозаполнить страницу пустыми строками до штампа
      keep: {titleChain: true}
      styles: {head: tableHeader, data: tableText}
      columns:
        - {id: n, width: 10, align: center}
        - {id: name, width: 85}
        - {id: qty, width: 25, align: center}
        - {id: unit, width: 20, align: center}
        - {id: note, width: 45}
      header:
        height: 15
        repeat: true                    # шапка на каждой странице
        cells:                          # по id колонки, покрыть надо все
          n: {text: "№", rotate: 90, style: head}
          name: {text: "Наименование", style: head}
          qty: {text: "Кол.", style: head}
          unit: {text: "Ед.", style: head}
          note: {text: "Примечание", lines: ["Приме-", "чание"], style: head}
      row:
        cells:
          n: {bind: "${item.n}", style: data}
          name: {bind: "${item.name}", style: data}
          qty: {bind: "${item.qty}", style: data}
          unit: {bind: "${item.unit}", style: data}
          note: {bind: "${item.note}", optional: true, style: data}
```

Данные: `doc: {designation: "ТЕСТ.01.000"}` и `item:` из 4 строк (`n`, `name`, `qty`, `unit`, у первой `note`; название третьей длинное).

```
RUN $T/09-flow.yaml output --data $T/09-flow-data.yaml
```

**Что увидеть:**

- Рамку листа рисует сам движок (185 × 287 в (20, 5)), шаблонная рамка не нужна.
- Шапка y 5..20 (15 мм), `№` повёрнут (`rotate(-90)`), `Примечание` в две строки: `Приме-` (y=11.66) и `чание` (y=15.86).
- Строки по 8 мм от y=20: текст в строках 1, 2 на y=25.26 и 33.26. Третье наименование не влезло в колонку 85 мм и перенеслось на вторую физическую строку:
  `Шайба 6, пружинная, с антикоррозионным` (y=41.26) и `покрытием по ГОСТ 6402-70` (y=49.26), строка выросла до 16 мм. Четвёртая строка на y=57.26.
- Дальше пустые строки с границами до штампа. Штамп начинается с y=277, **последняя пустая строка растянута на остаток** (горизонтальные линии на 268 и 277: 9 мм, а не 8).
- Колонка `qty` с `24` и `12.5` в разных строках стала Decimal: `24` и `12.5` выведены как есть (без `format`).

**Правила и подводные камни**

- Ширины колонок должны сойтись с шириной области контента (шаг 11, ошибка с числами). `width`, `rowHeight` числами, `${param.x}` в потоке не поддерживается.
- `stick: first|last` у колонки привязывает одностроковое значение к первой/последней физической строке, если соседняя колонка растянула строку.
- `align`: только `left` (умолчание) и `center`. Правого выравнивания чисел нет.
- Шапка: `rotate` 0 или 90 (270 ошибка при загрузке), `lines` нельзя вместе с `rotate`. `repeat: false` оставляет шапку только на первой странице.
- Шапка и данные берут стили из закрытого списка (`tableHeader`, `tableText`, `groupHeader`, `totalText`, ...), `styles:` задаёт псевдонимы. Поле `style` в блоках вне потока в CLI игнорируется.
- Один шрифт (GOST Type B).
- id блоков в CLI свободны: `stamp`, `leftMargin` и другие id слотов `gost-spec.yaml` в `TemplateMain` ничего не значат, `reserves` работает (раньше такой блок молча терял `reserves`,
  исправлено: привязка к слотам включена только у основного движка спецификации). Проверено: `09-flow.yaml` с `id: stamp` и `id: titleBlock` даёт побайтно один и тот же SVG.
- Блок шаблона в CLI рисует `layOutTemplate` поверх движка; рамка из шаблона ляжет поверх рамки движка. Проверено: с блоком `frame` в шаблоне с потоком в SVG два одинаковых `<rect x="20.0" y="5.0" width="185.0" ...>`: безвредно, но лишнее.
- Такой же поток без `--data` даёт каскад ошибок контракта (`item has: pos`, схема `item` пуста). Файл данных нужен всегда.

### 9б. Правила данных: where, sortBy, groupBy, computed, cases, format

Файлы `tutorial/09b-groups.yaml` + `tutorial/09b-groups-data.yaml`. Порядок применения: `where` → арифметика `computed` → `sortBy` → `groupBy` → `sequence` → `totals`.
Ключевые куски (остальное как в 9а, колонки 10+85+25+20+45):

```yaml
      where: {not: {field: status, eq: "отменено"}}       # отменённые строки в таблицу не попадают
      sortBy:
        - {field: name, order: asc, nulls: last}           # внутри группы по имени, натурально: «Болт 2» раньше «Болт 10»
      groupBy:
        field: kind                                        # поле item, читается как enum; домен = order + omit
        order: [FASTENER, CABLE]                           # порядок групп
        titles: {FASTENER: "Крепёж", CABLE: "Кабели"}
        omit: [DISCONTINUED]                               # значение осознанно отбрасывается (без этого было бы ошибкой)
      computed:
        pos: {sequence: {scope: group, start: 1, step: 1}} # нумерация с 1 в каждой группе
      groupTitle: {column: name, style: title, align: center, spacerBefore: 1, spacerAfter: 1}
      ...
          qty:                                              # cases: первый подходящий вариант; собственное содержимое = else
            style: data
            cases:
              - where: {field: unit, in: [шт, компл]}
                bind: "${item.qty}"
                format: {pattern: "0", rounding: HALF_UP}   # штуки: целое
            bind: "${item.qty}"
            format: {pattern: "0.##", locale: ru, rounding: HALF_UP}   # остальное: до 2 знаков, запятая
```

Данные (`item:`): шесть строк с полями `kind`, `name`, `qty`, `unit`, `status`; одна со статусом `отменено` («Шайба»), одна с `kind: DISCONTINUED`, остальные
(`Кабель ВВГ 3x1,5`, `Болт 10`, `Болт 2`, `Кабель КГ 2x1`) нормальные.

```
RUN $T/09b-groups.yaml output --data $T/09b-groups-data.yaml
```

**Что увидеть** (одна страница):

- Шапка 10 мм. Заголовок группы `Крепёж` на y=28.26: спейсер до (8 мм), заголовок (по центру колонки `name`), спейсер после, затем строки.
- Группа 1: `1 Болт 2 ... 3 компл` (2.5 → `0` HALF_UP → `3`), `2 Болт 10 ... 8 шт оцинк.`. «Болт 2» раньше «Болт 10» (натуральная сортировка).
  «Шайба» (отменено) и «Старый крепёж» (`DISCONTINUED`, в `omit`) в таблице нет.
- Группа 2 `Кабели` (y=68.26): `1 Кабель ВВГ 3x1,5 ... 12,5 м`, `2 Кабель КГ 2x1 ... 0,35 км`. Нумерация началась заново (`scope: group`), `0.##` дал запятую и срезал нули.
- Порядок групп задаёт `order`, а не файл (в файле `CABLE` стоял первым).

**Домен enum.** `groupBy.field` в файле это обычная строка, шаблон сам делает из неё enum: домен = `order` + `omit` (плюс значения из файла при `!enum`). Значение, которого нет ни в `order`, ни в `omit`, это ошибка:

```
  blocks[0].table.groupBy.order: 'kind' can also be DISCONTINUED: list it in 'order' (a group) or 'omit' (dropped on purpose), rows must not be lost silently
```

Предикат (`where`, `cases[].where`): `{field, eq|ne|in|isNull|notNull}` и комбинаторы `and`/`or`/`not` (каждый ровно один ключ). `where` и `groupBy` видят только поля записи.
`sequence` нельзя использовать в `where`/`sortBy`:

```
  blocks[0].table.where.field: computed field 'pos' is not available here (where / groupBy read the record's own fields, sortBy also arithmetic computed fields, a sequence depends on the row order)
```

### 9в. Арифметика и итоги

Файлы `tutorial/09c-totals.yaml` + `tutorial/09c-totals-data.yaml`. Колонки 10+70+20+15+30+40 = 185.

```yaml
      computed:
        pos: {sequence: {scope: group}}
        cost: {multiply: [qty, price], scale: 2, rounding: HALF_UP}   # стоимость; нет цены, нет и стоимости
      ...
      totals:                                  # строки итогов: подвал группы и подвал таблицы
        - id: groupCost
          scope: group                         # по строкам каждой группы
          agg: sum
          field: cost
          label: "Итого по группе"
          labelColumn: name
          valueColumn: cost
          format: {pattern: "0.00", locale: ru, rounding: HALF_UP}
        - id: totalCost
          scope: table                         # по всей таблице
          agg: sum
          field: cost
          label: "Всего"
          labelColumn: name
          valueColumn: cost
          format: {pattern: "0.00", locale: ru, rounding: HALF_UP}
        - {id: positions, scope: table, agg: count, label: "Позиций", labelColumn: name, valueColumn: qty}
        - id: avgPrice                         # среднее по строкам, у которых цена есть
          scope: table
          agg: avg
          field: price
          label: "Средняя цена"
          labelColumn: name
          valueColumn: price
          scale: 2
          rounding: HALF_UP
          where: {field: price, notNull: true}
          format: {pattern: "0.00", locale: ru}
```

Данные: `Болт М6x20` (24 шт, 3.2), `Шайба 6` (48 шт, 0.45), `Смазка` (0.35 кг, **без цены**), `Кабель ВВГ 3x1,5` (12.5 м, 64).

**Что увидеть:**

- `Болт М6x20`: цена `3,20`, сумма `76,80`; `Шайба 6`: `0,45`, `21,60`; `Смазка`: цена `по запросу` (вариант `cases` на `price isNull`), суммы нет (нет цены, нет и стоимости; `optional: true` на ячейке даёт пустую).
- Подвал группы: `Итого по группе` ... `98,40` (76,80 + 21,60, строка без стоимости не участвует); группа `Кабели`: `800,00`.
- Подвал таблицы после последней группы: `Всего` `898,40`, `Позиций` `4` (count считает строки, в том числе без цены), `Средняя цена` `22,55` ((3.2 + 0.45 + 64) / 3, `Смазка` не входит из-за `where` итога).
- Без `format` Decimal выводится как есть (`0.35`, `12.5`: точка), см. колонку `qty`.
- Итог привязан к последней строке итожимой группы: подвал не окажется один вверху страницы (в сквозном примере шага 10 `Итого по группе` стоит на странице 1 сразу после своих строк).

Сводка по `totals`: `scope` `group` (нужен `groupBy`) или `table`; `agg` `sum|count|min|max|avg`; `field` обязателен кроме `count`; `avg` требует `scale` и `rounding`;
`skipEmpty` по умолчанию `true`. Подробности и ограничения (нет вложенных и накопительных итогов) в справочнике.

### 9г. Номер физической строки: `${line.number}` и `lines`

Файлы `tutorial/09d-line-numbers.yaml` + `tutorial/09d-line-numbers-data.yaml`. Колонки 10+10+80+20+65 = 185 мм.

Если «Примечание» длинное, запись занимает несколько строк по 8 мм. `pos` (`computed: sequence`) нумерует записи и стоит один раз, а колонке «№ строки»
нужен номер КАЖДОЙ физической строки. Число зависит от переноса слов, который измеряется при раскладке, поэтому его ставит движок: ячейке задаётся
`bind: "${line.number}"` (корень `line`, только в ячейках строк потока).

```yaml
      computed:
        pos: {sequence: {}}             # номер ЗАПИСИ: один на запись
      lines:                            # необязательно, значения по умолчанию те же, кроме fill
        start: 1                        # номер первой строки
        scope: table                    # table: сквозной по страницам, page: с начала каждой страницы
        fill: true                      # пустые строки дозаполнения тоже нумеруются (по умолчанию false)
      row:
        cells:
          line: {bind: "${line.number}", style: data}
          pos: {bind: "${item.pos}", style: data}
```

Данные: `Болт М6x20`, `Гайка М6` (примечание в 3 строки), `Шайба 6`, `Кабель ВВГ 3x1,5` (примечание в 2 строки), `Смазка`.

**Что увидеть** (из SVG, колонка «№ строки» / колонка «№ п/п»):

| Строка таблицы | № строки | № п/п | Наименование / примечание |
|---|---|---|---|
| 1 | 1 | 1 | Болт М6x20 |
| 2 | 2 | 2 | Гайка М6, примечание: `оцинкованная, класс прочности 8.8,` |
| 3 | 3 | пусто | `покрытие по ГОСТ 9.307, толщина слоя` |
| 4 | 4 | пусто | `9 мкм, без хроматирования` |
| 5 | 5 | 3 | Шайба 6 |
| 6 | 6 | 4 | Кабель ВВГ 3x1,5, примечание: `бухта, длина 12,5 м, оболочка из ПВХ` |
| 7 | 7 | пусто | `пластиката пониженной горючести` |
| 8 | 8 | 5 | Смазка |
| 9..32 | 9..32 | пусто | пустые строки дозаполнения (при `fill: true`; при `false` без чисел) |

- Номер идёт по строкам, а не по записям: у записи с примечанием в 3 строки номера 2, 3, 4, следующая запись начинается с 5.
- Заголовки групп, пустые строки вокруг них и строки итогов не нумеруются и счёт не двигают (в этом шаге групп нет, правило проверено тестами раскладки).
- Номер сквозной по страницам (`scope: table`): запись, разорванная границей страницы, продолжает счёт на следующей; `scope: page` начнёт с `start` на каждой странице.
- Пустые строки дозаполнения по умолчанию остаются без номера (`fill: false`): нумеровать ли их, решает ключ.
- Чтобы перевести колонку «№ строки» с `${item.pos}` на номер строки, замените bind ячейки на `"${line.number}"` (`computed.pos` можно оставить, если `pos` нужен в другой колонке).

### 9д. Параметризованные стили и принудительный перенос `\n`

Файлы `tutorial/09e-styles-and-breaks.yaml` + `tutorial/09e-styles-and-breaks-data.yaml`. Колонки 10+10+105+20+40 = 185 мм.

**Стили с параметрами.** Раньше псевдоним в `styles` был только именем встроенного стиля. Теперь можно взять встроенный стиль за основу и поменять кегль и флаги:

```yaml
      styles:
        data: tableText                                   # строка: просто имя встроенного стиля
        head: {base: tableHeader, size: 4.5}              # объект: копия tableHeader, кегль 4,5 мм
        note: {base: tableText, size: 3, italic: true}    # неуказанное (гарнитура, bold, underline) берётся из base
```

`base` обязателен (имя из набора встроенных), `size` это кегль в мм (> 0), `bold` / `italic` / `underline` это `true` / `false`. Использование то же: `style: head` в ячейке шапки,
`style: note` в ячейке строки (так же в `groupTitle.style` и `totals[].style`). Псевдоним нельзя называть именем встроенного стиля (`tableText: {...}` ошибка): назовите `big`, `note`.

**Принудительный перенос.** `\n` в значении ячейки строки это жёсткий перенос. В данных он пишется в двойных кавычках:

```yaml
  - {name: "Гайка М6", qty: 24, note: "оцинкованная\nкласс прочности 8.8\nГОСТ 9.307"}
  - {name: "Шайба 6", qty: 48, note: "нержавеющая сталь, покрытие по ГОСТ 9.307\n\nпоставка россыпью"}
  - {name: "Кабель ВВГ 3x1,5", qty: 12.5, note: "бухта\n"}
```

Каждый сегмент между `\n` переносится по словам отдельно; пустой сегмент (`\n\n`) пустая строка; `\n` в конце текста отбрасывается; `\n` в начале даёт пустую первую строку.

Запуск: `RUN $T/09e-styles-and-breaks.yaml output --data $T/09e-styles-and-breaks-data.yaml`.

**Что увидеть** (из SVG, кегль в `font-size`; колонки «№ строки» / «№ п/п» / примечание):

| Строка таблицы | № строки | № п/п | Примечание |
|---|---|---|---|
| 1 | 1 | 1 | Болт М6x20, примечания нет |
| 2 | 2 | 2 | `оцинкованная` (Гайка М6) |
| 3 | 3 | пусто | `класс прочности 8.8` |
| 4 | 4 | пусто | `ГОСТ 9.307` |
| 5 | 5 | 3 | `нержавеющая сталь,` (Шайба 6; первый сегмент не влез в 38 мм и перенёсся по словам) |
| 6 | 6 | пусто | `покрытие по ГОСТ 9.307` |
| 7 | 7 | пусто | пустая строка (`\n\n`): номер есть, текста нет |
| 8 | 8 | пусто | `поставка россыпью` |
| 9 | 9 | 4 | `бухта` (Кабель ВВГ 3x1,5; конечный `\n` лишней строки не даёт) |
| 10 | 10 | 5 | Смазка, примечания нет |

- Шапка нарисована кеглем `4.5` (`font-size="4.5"` в `<text>`), примечания кеглем `3.0` курсивом, остальное `3.5` (в SVG `3.4999999999999996`: мм в пункты и обратно).
- Номер строки идёт по физическим строкам (10 строк у 5 записей), `pos` стоит только на первой строке записи.
- Кегль крупнее 3,5 мм меняет перенос. Колонка «№ строки» с `rotate: 90` и кеглем 4,5 мм не помещается в шапку 15 мм («№ строки» длиннее), поэтому в примере `header.height: 20`: повёрнутый текст
  рисуется одной строкой и при нехватке высоты обрезается до первого куска. Неповёрнутую шапку без `lines` кегль переносит по ширине колонки на несколько строк, и высоту `header.height` нужно увеличить.
- Стили и переносы проверены тестами (`FlowTableTest`, `FlowTablesTest`, `TableMeasurementTest`, `StylesAndBreaksGoldenTest`, `TemplateMainTest`).

### 9е. Многоуровневая шапка: `header.rows`, `span`, `rowSpan`

Файлы `tutorial/09f-multi-header.yaml` + `tutorial/09f-multi-header-data.yaml`. Лист А3 альбомный, колонки 7+60+45+70+55+70+16+16+16+16+24 = 395 мм (ширина области контента 420 - 20 - 5).

Вместо `height` + `cells` по id шапка задаётся списком рядов; ячейки каждого ряда идут по порядку колонок, как в блоке `table`:

```yaml
      header:
        repeat: true
        rows:
          - height: 9
            cells:
              - {text: "№ строки", rowSpan: 2, rotate: 90, style: headSmall}
              - {text: "Наименование", rowSpan: 2, style: head}
              ...                                                   # 6 ячеек с rowSpan: 2
              - {text: "Количество", span: 4, style: head}          # объединяет 4 колонки
              - {text: "Примечание", rowSpan: 2, style: head}
          - height: 18
            cells:                                                  # только колонки, не занятые rowSpan сверху: 4 штуки
              - {text: "на изделие", lines: ["на из-", "делие"], style: headSmall}
              ...
```

Запуск: `RUN $T/09f-multi-header.yaml output --data $T/09f-multi-header-data.yaml`.

**Что увидеть** (из SVG, `grep '<rect'`; шапка 9 + 18 = 27 мм, от 5 до 32 мм, строки данных с 32 мм):

| Ячейка | `<rect x y width height>` |
|---|---|
| «№ строки» (`rowSpan: 2`) | `20 5 7 27` |
| «Наименование» | `27 5 60 27` |
| «Количество» (`span: 4`) | `327 5 64 9` |
| «на изделие» / «на комплект» / «на регулировку» / «всего» (ряд 2) | `327 14 16 18`, `343 14 16 18`, `359 14 16 18`, `375 14 16 18` |
| «Примечание» | `391 5 24 27` |

- Ячейки ставятся по порядку на колонки, а не по id; id колонок нужны строкам данных. Каждый ряд покрывает все колонки ровно один раз (свои `span` плюс колонки, занятые `rowSpan` сверху), `rowSpan` не выходит за последний ряд: правило и тексты ошибок те же, что у блока `table`.
- Высота шапки это сумма высот рядов; она же сдвигает вниз содержимое, на каждой странице при `repeat: true`.
- Текст по центру объединённого прямоугольника; повёрнутый («№ строки», 7 x 27 мм) тоже, но рисуется только первая строка переноса: текст должен помещаться в высоту объединённой ячейки.
- `rows` и `height` / `cells` взаимоисключающие. Однорядная шапка (шаги 9а, 9д) не меняется.

### 9ж. Остаток высоты: `remainder: gap`

Файлы `tutorial/09g-remainder-gap.yaml` и `tutorial/09g-remainder-stretch.yaml` (общие данные `tutorial/09g-remainder-gap-data.yaml`). Лист А3 альбомный, штамп 185 x 33 мм внизу
справа; таблица заканчивается над штампом. Запуск:
`RUN $T/09g-remainder-gap.yaml output --data $T/09g-remainder-gap-data.yaml` (и то же для `09g-remainder-stretch.yaml`).

```yaml
      rowHeight: 8
      fill: blank
      remainder: gap        # stretch (по умолчанию) или gap; только вместе с fill: blank
      # по страницам: remainder: {first: gap, rest: stretch}  (first = стр. 1, rest = стр. 2+; пропущенный ключ = stretch)
```

Расчёт: шапка 15 мм (`y` 5..20), 6 записей кончаются на `20 + 6 * 8 = 68`, штамп начинается на `292 - 33 = 259`, `259 - 68 = 191 = 23 * 8 + 7`.

| | последняя пустая строка | последняя граница | зазор над штампом |
|---|---|---|---|
| `gap` | 8 мм (`244..252`) | `252` | 7 мм |
| `stretch` | 8 + 7 = 15 мм (`244..259`) | `259` | нет |

**Что увидеть** (из SVG, горизонтали колонки «№» от `x 20` до `x 30`): `gap` 30 границ `20, 28, ..., 252`; `stretch` те же до `244` и последняя `259`.
Верх штампа `y = 259` (линия 0,71 мм). Нижняя граница последней строки рисуется в обоих режимах; в `stretch` она ложится на верх штампа.

- `gap` для форм, где таблица кончается над штампом и все строки должны быть одной высоты; `stretch` (по умолчанию) закрывает страницу до самого низа.
- `remainder` без `fill: blank` или с другим значением ошибка с путём `blocks[N].table.remainder`.
- Остаток считается на каждой странице; страница без данных и вторая страница работают так же. Нумерация пустых строк (`lines.fill: true`) не меняется.

## Шаг 10. Сквозной пример

**Цель:** собрать с нуля законченный документ «Ведомость покупных изделий»: лист, штамп первой и следующих страниц, боковая надпись, поток с группами и итогами.
Файлы `tutorial/10-complete.yaml` и `tutorial/10-complete-data.yaml` (38 строк данных, две страницы).

Порядок сборки, который работает для любого нового шаблона:

1. `sheet` (формат, поля), пустой лист запустить.
2. Штампы и надписи (геометрия): блоки/наборы, `when`, `reserves: true`. Запустить без потока, посмотреть положение.
3. Данные: определить `doc.*` (обозначение, наименование), набросать `item:` в файле данных.
4. Поток: колонки (сумма = 185), шапка, строки `bind: ${item.*}`, `fill`.
5. Правила данных (`groupBy`, `sortBy`, `computed`, `cases`, `format`).
6. Итоги (`totals`).
7. Запуск на реальных объёмах: смотрим перенос страниц.

Структура `10-complete.yaml` (полный текст в файле):

```yaml
name: t10-complete
sheet: {format: A4, orientation: portrait, margins: {top: 5, right: 5, bottom: 5, left: 20}}

blocksets:
  stampBase:                           # общая часть штампа: обозначение, «Лист», номер; 10 мм
    blocks:
      - id: grid
        type: table
        columns: [120, 40, 25]
        rows:
          - height: 10
            cells:
              - {bind: "${doc.designation}", align: center, fontSize: 14}
              - {text: "Лист", align: center}
              - {bind: "${page.number}", align: center}

blocks:
  - id: firstStamp                     # первая страница: наименование над общей частью
    type: table
    when: first
    reserves: true
    columns: [185]
    rows:
      - {height: 15, cells: [{bind: "${doc.name}", align: center, fontSize: 14}]}
    attach: {self: bottomRight, to: firstBase.topRight}      # над общей частью
  - {id: firstBase, use: stampBase, when: first, reserves: true, attach: {self: bottomRight, to: sheet.contentBottomRight}}
  - {id: restBase,  use: stampBase, when: rest,  reserves: true, attach: {self: bottomRight, to: sheet.contentBottomRight}}
  - id: side                           # боковая подпись, на всех страницах
    type: table
    rotate: 90
    columns: [40, 40]
    rows:
      - height: 5
        cells: [{text: "Инв. № подл.", align: center}, {text: "Подп. и дата", align: center}]
      - {height: 7, cells: [~, ~]}
    attach: {self: bottomRight, to: sheet.contentBottomLeft}
  - id: body
    type: flow
    table: ...                         # группы, sortBy, computed pos и cost, 7 колонок 10+70+30+15+15+20+25, шапка 12 мм, totals
```

```
RUN $T/10-complete.yaml output --data $T/10-complete-data.yaml
```

**Что увидеть:**

- **Две страницы** (`t10-complete-page-1.svg`, `-page-2.svg`) и PDF; 38 позиций, число страниц посчитал движок.
- Страница 1: шапка потока (5..17), группа `Крепёж` (20 строк) с `Итого по группе` `2559,50` (y=206.26), группа `Подшипники` начинается на странице 1 и
  продолжается на странице 2 (6002..6008 на первой, 6010 и дальше на второй; заголовок группы на второй странице не повторяется). Поток кончается над штампом (y=267, штамп 25 мм),
  `ВП.00.001`, `Ведомость покупных изделий` (y=276.27), `Лист` и `1` в штампе.
- Страница 2: шапка повторена, строки до `Итого по группе` `12045,00` (подшипники, y=70.26), группа `Электроизделия`, её `Итого по группе` `4152,00`
  (y=166.26), затем `Всего` `18756,50` (2559,50 + 12045,00 + 4152,00), `Позиций` `38`, пустые строки до штампа (штамп 10 мм, верх y=282), `Лист 2`.
- Боковая подпись в x 8..20 (`Инв. № подл.` на y=281.57).
- Кабели без цены (каждый 4-й): `по запросу`, стоимости нет, в сумму не входят.

## Шаг 11. Типичные ошибки и их тексты

Все тексты получены запуском (файлы `tutorial/errors/*.yaml`). Формат: `путь: сообщение`; выход 1.

| Что сломали | Файл | Реальный текст |
|---|---|---|
| `[` внутри `{...}` без кавычек | (шаг 4) | `invalid YAML: while parsing a flow mapping ... expected ',' or '}', but got [` |
| опечатка в bind | `01-typo-bind` | `blocks[0].bind: unknown field 'doc.designaton' (doc has: designation, name, mass, issued, approvedBy), did you mean 'doc.designation'?` |
| `format` не для типа | `02-bind-format` | `blocks[0].format: format is not supported for String (only Decimal and Date)` |
| цикл привязок | `03-cycle` | `blocks[0].attach.to: attach cycle: a -> b -> a` |
| `all` привязан к `first` | `04-visibility` | `blocks[1].attach.to: block 'always' (when: all) attaches to 'first' which exists only on first pages` |
| поворот текста 270 | `05-rotate270` | `blocks[0].rotate: text rotation 270 is not supported (expected 0\|90)` |
| таблица 270, ячейка без `rotate` | `21-table-rotate270` | `blocks[0].rows[0].cells[0].rotate: text rotation 270 (table rotate 270 + cell rotate 0) is not supported (expected 0\|90)` |
| ряд не покрывает колонки | `06-row-cover` | `blocks[0].rows[0].cells: cell spans add up to 2, table has 3 columns` |
| неизвестный якорь / блок | `07-unknown-anchor` | `blocks[0].attach.to: unknown anchor 'sheet.contentTopMiddle'` и `blocks[1].attach.to: unknown block 'nope' in 'nope.topLeft'` |
| опечатка в ключе | `08-unknown-key` | `blocks[0].sise: unknown field 'sise' (allowed: anchors, attach, id, reserves, size, thickness, type, when) (line 8)` |
| нет значения, нет `optional` | `09-missing-value` | `error: no value for bind '${doc.approvedBy}' (mark the cell 'optional: true' to render it empty)` |
| два bind в одном | `10-expr-in-text` | `blocks[0].bind: bind must be a single path expression like ${doc.designation} (roots: doc, page, item, line), got '${doc.designation}/${doc.name}'` |
| толщина словом | `11-thick-number` | `blocks[0].thickness: expected number (mm) or ${param.x}, got 'bold' (line 8)` |
| ширина колонок | `12-flow-width` | `blocks[0].table.columns: column widths sum to 180.0 mm, flow region is 185.0 mm wide (sheet content width, compared to 0.01 mm, no tolerance)` |
| значение enum вне `order`/`omit` | `13-flow-enum` | `blocks[0].table.groupBy.order: 'kind' can also be DISCONTINUED: list it in 'order' (a group) or 'omit' (dropped on purpose), rows must not be lost silently` |
| неизвестный стиль | `14-flow-style` | `blocks[0].table.row.cells.pos.style: unknown style 'dataa' (aliases: head, data, title; built-in: designation, frameText, frameTextLarge, groupHeader, heading, mainText, tableHeader, tableText, totalText)` |
| размер шрифта 0 в стиле | `22-flow-style-size` | `blocks[1].table.styles.head.size: must be > 0, got 0.0` |
| неизвестный `base` стиля | `23-flow-style-base` | `blocks[1].table.styles.head.base: unknown style 'tableHeadr' (designation, frameText, frameTextLarge, groupHeader, heading, mainText, tableHeader, tableText, totalText)` |
| опечатка в ключе стиля | `24-flow-style-key` | `blocks[1].table.styles.head.sise: unknown field 'sise' (allowed: base, bold, italic, size, underline) (line 25)` |
| псевдоним = имя встроенного стиля | `25-flow-style-shadow` | `blocks[1].table.styles.tableText: alias 'tableText' shadows a built-in style` |
| шапка: ряд не покрывает колонки | `26-flow-header-gap` | `blocks[1].table.header.rows[0].cells: cell spans add up to 10, table has 11 columns` |
| шапка: `rowSpan` за последний ряд | `27-flow-header-rowspan` | `blocks[1].table.header.rows[1].cells: cell 3 rowSpan 2 exceeds the table's 2 rows` |
| шапка: две формы сразу | `28-flow-header-both` | `blocks[1].table.header: 'rows' excludes 'height' (use 'rows' or 'height' + 'cells') (line 41)` |
| неизвестное поле item | `15-flow-bind` | `blocks[0].table.row.cells.name.bind: unknown field 'item.nme' (item has: kind, name, qty, unit, status, note, pos), did you mean 'item.name'?` |
| поворот шапки 270 | `16-flow-rotate270` | `blocks[0].table.header.cells.pos.rotate: expected 0\|90, got 270` |
| `where` читает computed | `17-flow-where-computed` | `blocks[0].table.where.field: computed field 'pos' is not available here (where / groupBy read the record's own fields, ...)` |
| `size` у потока | `18-flow-size` | `blocks[0].size: flow table fills the flow region, remove 'size'` |
| два потока | (проверено в scratch) | `blocks[2].table: only one flow table per template (the engine lays out a single main table)` |
| у строки данных нет поля | `19-row-missing-data` (данные к `09-flow.yaml`) | `error: source row 2 {n=2, name=Гайка М6, qty=24}: no value for bind '${item.unit}' (mark the cell 'optional: true' to render it empty)` |
| в поле строк разные типы | `20-mixed-types-data` (данные к `09-flow.yaml`) | `item[2].qty: mixed types in one field: String here, Integer in item[0].qty (row 3 and row 1); use one type or tag the values (!str, !int, ...)` |
| файла `--data` нет | (scratch) | `error: data file not found: /путь/nofile.yaml` |

Запуск любого из них: `RUN $T/errors/12-flow-width.yaml output --data $T/09b-groups-data.yaml` (для плоских файлов `--data` не нужен). Файлы `22`-`25` запускаются с `--data $T/09e-styles-and-breaks-data.yaml`, файлы `26`-`28` с `--data $T/09f-multi-header-data.yaml`. Файлы `19`, `20` это данные: `RUN $T/09-flow.yaml output --data $T/errors/19-row-missing-data.yaml`.

**Куда смотреть при ошибке**

- Индекс в пути это номер элемента списка с нуля: `blocks[4]` пятый блок, `rows[1].cells[2]` второй ряд, третья ячейка.
- Ошибка «блок виден на меньшем числе страниц» лечится выравниванием `when` или привязкой к общему блоку (`all`).
- Если запуск прошёл, а картинка не та: открыть SVG и `grep '<rect\|<line\|<text'`, сравнить координаты с расчётом (правило проверки в заголовке страницы).

## Шаг 12. Чек-лист и сопровождение

**Чек-лист нового шаблона**

1. `name` уникален (по нему называются файлы `<name>-page-N.svg`).
2. `sheet`: формат, ориентация, поля; `size` рамки посчитан по формуле `лист − поля`.
3. Каждый блок с `id` из `[A-Za-z0-9_-]`, не `sheet`/`self`, уникальный в области.
4. Каждая таблица: ряды покрывают все колонки, тонкие линии заданы с обеих сторон.
5. `[`, `{`, `,` в привязках на одной строке в кавычках (`"t.cell[0,1].topLeft"`).
6. `when` у цели не уже, чем у блока; блоки штампов с `reserves: true`.
7. Все bind: пути есть в схеме данных (или в файле `--data`), необязательные с `optional: true`, `format` под тип.
8. Поток: один на шаблон, ширины колонок = ширина области контента, шапка покрывает все колонки, id блоков любые.
9. Для enum-группировки: `order` + `omit` покрывают все значения, `titles` для каждого значения из `order`.
10. Запустить на **реальных** объёмах данных (больше одной страницы) и осмотреть SVG.

**Поддержка**

- Golden-тесты для CLI-шаблонов не нужны: CLI рисует любой YAML без эталонов. Эталоны есть только у шаблона боевого движка (`StaticBlocksGoldenTest` и др.).
- Боевой движок спецификации читает один шаблон: `report-ir/src/main/resources/templates/gost-spec.yaml`. Его блоки `stamp`, `continuationStamp`, `leftMargin`, `belowFrame`,
  `specLeft`, `mainTitleRight` сопоставлены полям `PageSetup` кодом (`StaticSlot`) только на этом пути (`bindStaticSlots`); тело спецификации это блок `body`. Править осторожно: golden сравнивает вывод побайтно.
  Шаблоны из `report-cli/.../templates/` боевой движок не читает: их нельзя «подложить» вместо `gost-spec.yaml` без кода.
- Тест `TemplateMainTest` не перечисляет ресурсы CLI-шаблонов, поэтому добавление файлов в `templates/` или `templates/tutorial/` его не ломает (проверено `./gradlew :report-cli:test`).
- **Остаётся кодом:** алгоритм раскладки потока (измерение, перенос слов, пагинация, дозаполнение, цепочки `keep`), стили (закрытый набор `Styles.named`), шрифт, адаптеры данных (из Loodsman и т.п.),
  отрисовка в SVG/PDF.

**Ограничения (проверено запусками и справкой)**

- Текст поворачивается только на 0 и 90. 270 у `text`-блока и итоговый поворот ячейки таблицы (блок + ячейка) не 0 и не 90 дают ошибку при загрузке с путём (шаг 11); таблица 270 читается, только если у каждой ячейки `rotate: 90`.
- В строках `item:` одно поле одного типа: `5` и `ok` в одном поле это ошибка (`!str` у каждого значения лечит); `1` и `1.5` сливаются в Decimal.
- Один шрифт (GOST Type B), поле `style` вне потока в CLI игнорируется.
- В шаблоне один `flow`, в наборе `flow` недопустим, `${param.x}` в потоке не работает, правого выравнивания чисел нет.
- Шаблон с потоком в CLI рисует и блоки шаблона (на каждой странице), и рамку листа от движка; `--pages` игнорируется.
- Итоги: без вложенных подитогов и накопительных; `min`/`max` только числа.
- Не проверено: PDF визуально (генерируется, содержимое проверялось только по SVG); `!int`, `!bool`, `!decimal`, `!date` теги кроме `!str`/`!enum`; `repeat: {count: N}`; `flow` внутри набора; свои `width`/`height` листа вместо `format`.

См. также: [template-yaml.md](template-yaml.md) (справка по полям, схема и API),
`report-ir/src/main/resources/templates/gost-spec.yaml` (боевой шаблон).
