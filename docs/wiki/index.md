# Wiki: report-engine

Инженерный генератор документов из данных PDM. Специализированный layout engine
для документов ЕСКД (спецификации, чертёжные надписи), а не универсальный
конструктор отчётов.

Главная архитектурная идея — IR-граница между смыслом документа и его физическим
размещением. Полное описание: **[architecture-0.2.md](../architecture-0.2.md)**
(актуальная версия; исходный документ — [architecture-0.1.md](../architecture-0.1.md),
сохранён как есть).

## Статус

Реализованы фазы 1–7 из **[dev-plan-0.1.md](../dev-plan-0.1.md)**:
- Фазы 1–6: foundation, text measurement, layout engine, SVG/PDF renderers, end-to-end
- Фаза 7: форматирование таблицы спецификации (фиксированная высота 8мм, 4 блока, границы ячеек, синтетический italic, дозаполнение страницы)
- Плюс: рамка/штамп (`FrameSpec`, §34 — основная надпись, доп. графы, подписи "Копировал"/"Формат")

10 модулей, 55+ тестов, всё зелёное. Подробно — [development-status.md](development-status.md).

## Карта wiki

- **[architecture.md](architecture.md)** — pipeline, границы модулей, откуда
  куда данные текут
- **[modules.md](modules.md)** — таблица всех 10 Gradle-модулей: назначение,
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
  форматируется тело таблицы спецификации: 4 блока, фиксированная высота 8мм,
  граница ячеек, синтетический italic, word-wrap, дозаполнение страницы
- **[eskd-title-block.md](eskd-title-block.md)** — рамка/штамп: `FrameSpec`,
  геометрия основной надписи и доп. граф, два прохода `renderPages()` ради
  вычисляемого номера/количества листов, баг с вырезом на углах ячеек
- **[fonts-and-licensing.md](fonts-and-licensing.md)** — какие шрифты
  используются, что настоящее, что заглушка, что с лицензиями
- **[known-gaps.md](known-gaps.md)** — честный список того, что не доделано
  или упрощено, и почему это осознанно
- **[development-status.md](development-status.md)** — что сделано по фазам,
  список коммитов
- **[running-the-project.md](running-the-project.md)** — как собрать,
  прогнать тесты, сгенерировать реальный PDF/SVG

## Если нужно быстро

Сгенерировать реальный документ и посмотреть глазами:

```
./gradlew :report-cli:run --args="output"
```

Результат — `report-cli/output/specification.pdf` и `.svg`. Подробности в
[running-the-project.md](running-the-project.md).
