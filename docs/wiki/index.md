# Wiki: report-engine

Инженерный генератор документов из данных PDM. Специализированный layout engine
для документов ЕСКД (спецификации, чертёжные надписи), а не универсальный
конструктор отчётов.

Главная архитектурная идея — IR-граница между смыслом документа и его физическим
размещением. Полное описание: **[architecture-0.2.md](../architecture-0.2.md)**
(актуальная версия; исходный документ — [architecture-0.1.md](../architecture-0.1.md),
сохранён как есть).

## Статус

Реализованы фазы 1–6 из **[dev-plan-0.1.md](../dev-plan-0.1.md)** (foundation, text measurement, layout engine,
SVG/PDF рендереры, end-to-end), плюс то, чего в плане не было (нумерация «фаз» в вики не совпадает с dev-plan:
там фаза 7 это XLSX, она не начата, см. [known-gaps.md](known-gaps.md)):
- форматирование таблицы спецификации (фиксированная высота 8мм, группы, границы ячеек, синтетический italic, дозаполнение страницы);
- рамка/штамп (§34 — основная надпись, доп. графы, подписи "Копировал"/"Формат"), доп. таблицы листа;
- клиент Loodsman и REST-сервер (`report-loodsman`, `report-server`);
- **ветка `engine` (с 279b40f, 15 коммитов): декларативные YAML-шаблоны** (`report-template`): блоки, якоря, наборы, поворот,
  типизированный контекст данных и контракт, таблица потока целиком в YAML (колонки, многоуровневая шапка, `groupBy` /
  `where` / `sortBy` / `computed` / `cases` / `format` / `totals`, нумерация строк `${line.number}`, стили, `\n`,
  `remainder`), CLI `runTemplate`, golden- и parity-тесты. Подробно: [changelog-engine.md](changelog-engine.md).

13 Gradle-модулей (`settings.gradle.kts`), **368 тестов, 0 падений** (`./gradlew test`, 2026-10-03; по модулям:
`report-template` 146, `report-layout` 91, `reports/specification` 44, `report-ir` 40, `report-cli` 13, `report-geometry` 13,
`report-loodsman` 8, `report-data` 3, `report-layout-ir` 3, `report-render-pdf` 3, `report-render-svg` 2, `report-api` 1,
`report-server` 1). Подробно — [development-status.md](development-status.md).

## Карта wiki

- **[architecture.md](architecture.md)** — pipeline, границы модулей, откуда
  куда данные текут
- **[modules.md](modules.md)** — таблица всех 13 Gradle-модулей: назначение,
  зависимости, ключевые типы
- **[design-decisions.md](design-decisions.md)** — почему сделано именно так
  (fixed-point Length, FontRegistry на байтах не PDFont, overflow policy,
  разделение TextAlign/TextOrientation между слоями и т.д.)
- **[architecture-improvements.md](architecture-improvements.md)** — потенциальные
  архитектурные рефакторинги и идеи на будущее (упразднение report-api модуля и т.д.)
- **[eskd-specification-header.md](eskd-specification-header.md)** — как
  устроен заголовок таблицы спецификации (форма 1 по ГОСТ Р 2.106-2019):
  вертикальный текст, математика поворота, откуда взялись конкретные размеры
- **[specification-table-format.md](specification-table-format.md)** — как
  форматируется тело таблицы спецификации: 5 групп, фиксированная высота 8мм,
  граница ячеек, синтетический italic, word-wrap, дозаполнение страницы
  (правила данных теперь в YAML, см. баннер на странице)
- **[eskd-title-block.md](eskd-title-block.md)** — рамка/штамп: `FrameSpec`,
  геометрия основной надписи и доп. граф, два прохода `renderPages()` ради
  вычисляемого номера/количества листов, баг с вырезом на углах ячеек
  (частично устарело: пресеты теперь в YAML, см. баннер на странице)
- **[template-yaml.md](template-yaml.md)** — справка по YAML-шаблонам (`report-template`):
  блоки, наборы, типы (`frame`/`rect`/`table`/`text`/`flow`), привязка и якоря, типизированные данные и контракт,
  таблица потока (колонки, шапка `rows`, `groupBy`/`where`/`sortBy`/`computed`/`cases`/`totals`, `lines`, `remainder`),
  Kotlin API, запуск своего шаблона через `runTemplate`
- **[template-guide.md](template-guide.md)** — практикум «Как создавать шаблон с нуля»:
  от пустого листа до таблицы-потока с итогами, реальные запуски (файлы `report-cli/.../templates/tutorial/`),
  тексты ошибок, чек-лист
- **[changelog-engine.md](changelog-engine.md)** — что нового в ветке `engine`: хронология возможностей с хешами
  коммитов и «как мигрировать» для ломающих изменений
- **[loodsman-integration.md](loodsman-integration.md)** — клиент Loodsman PDM
  API: авторизация, разделение свойств/атрибутов, правило ключевого атрибута
  по типу объекта, известные грабли API
- **[fonts-and-licensing.md](fonts-and-licensing.md)** — какие шрифты
  используются, что настоящее, что заглушка, что с лицензиями
- **[known-gaps.md](known-gaps.md)** — актуальный список ограничений и того, что
  не доделано или упрощено, и почему это осознанно
- **[development-status.md](development-status.md)** — что сделано по фазам,
  список коммитов, раздел ветки `engine`
- **[running-the-project.md](running-the-project.md)** — как собрать,
  прогнать тесты, `runTemplate`, golden-тесты, сгенерировать реальный PDF/SVG

## Если нужно быстро

Сгенерировать реальную спецификацию на тестовых данных и посмотреть глазами:

```
./gradlew :report-cli:run --args="output"
```

Результат — `report-cli/output/specification.pdf` и `.svg`.

Отрисовать свой YAML-шаблон (SVG на каждую страницу + PDF), с данными и числом страниц:

```
./gradlew :report-cli:runTemplate -q -Pargs="path/to/your.yaml output --pages 2 --data data.yaml"
```

Без аргументов берётся встроенный `sheet-frame.yaml`. Относительные пути считаются от `report-cli/`.
Подробности и грабли — в [running-the-project.md](running-the-project.md), как писать шаблоны —
[template-guide.md](template-guide.md).
