# Как собрать, протестировать, запустить

## Требования

- JDK 21 (используется как toolchain для всех модулей)
- Gradle не обязателен отдельно — есть wrapper (`./gradlew`)

## Сборка и тесты

```bash
./gradlew build                       # весь проект, 13 модулей, 368 тестов (на 2026-10-03, 0 падений)
./gradlew test --continue -q          # только тесты, не останавливаясь на первом упавшем модуле
./gradlew :report-template:test       # тесты одного модуля (146)
./gradlew :reports:specification:test # golden- и parity-тесты спецификации (44)
./gradlew :report-cli:test            # TemplateMain: смоук --data, контракт, пагинация потока (13)
```

Число тестов по модулям считается из `*/build/test-results/test/*.xml` после прогона; цифры в [index.md](index.md) и
[development-status.md](development-status.md) взяты оттуда. Тесты `report-cli` и `reports/specification` раскладывают
несколько страниц, а `PdfBoxTextMeasurer` заново разбирает TTF на каждый `measure()`, поэтому в их `build.gradle.kts`
стоит `maxHeapSize = "4g"`: не убирайте и не уменьшайте, иначе тесты падают с `OutOfMemoryError`.

## Сгенерировать реальный документ

```bash
./gradlew :report-cli:run --args="output"
```

Пишет `report-cli/output/specification.svg` и `.pdf` — реальная спецификация из зашитой в `Main.kt` тестовой фикстуры.
Если аргумент не передан — пишет в `output/` относительно рабочей директории Gradle-таска.

**Важно:** после правки любого файла — если результат не поменялся, сначала проверь `stat`/`ls -la` на сгенерированный
файл и на изменённый исходник. Реальный случай из практики: пользователь поменял размер шрифта в `Styles.kt`, посмотрел на
PDF — visually показалось, что не изменилось. Причина была банальной: PDF был сгенерирован ДО правки (`report-cli:run` после
правки не перезапускали).

## Отрисовать свой YAML-шаблон: `runTemplate`

```bash
./gradlew :report-cli:runTemplate -q -Pargs="путь/к/шаблону.yaml output --pages 2 --data данные.yaml"
```

Точка входа `report-cli/.../TemplateMain.kt`, задача Gradle `runTemplate` (рабочая папка `report-cli/`).

| Аргумент | Смысл |
|---|---|
| `[шаблон.yaml]` | путь к YAML; без аргументов берётся встроенный `sheet-frame.yaml` (ресурс) |
| `[папка]` | куда писать результат, по умолчанию `output` |
| `--pages N` | число страниц (>= 1, по умолчанию 1); страница 1 строится как `first`, остальные как `rest`. Если в шаблоне есть `flow` с `table:`, число страниц даёт пагинация, `--pages` игнорируется |
| `--data файл.yaml` | типизированные данные (`doc:`, `item:`), схема выводится из файла; без флага берутся демо-данные (`doc.designation`, `doc.name`, `doc.mass`, `doc.issued`, `doc.approvedBy`) |

Результат: `<name>-page-N.svg` на каждую страницу и `<name>.pdf`, `name` это поле `name:` из YAML (пусто: `template`).
Абсолютные пути печатаются строками `wrote ...`. Ошибка шаблона или контракта: список `путь: сообщение` в stderr, код выхода 1,
файлы не пишутся; ошибки аргументов и данных (`data file not found`, нет значения для bind) это `error: ...`, тоже код 1.

**Грабли**

- **Рабочая папка.** Через Gradle относительные пути (шаблон, папка вывода, `--data`) считаются от `report-cli/`, а при запуске
  `main` из IDE от корня репозитория (или от того, что задано в конфигурации запуска). Папка `output` попадёт в разные места;
  надёжнее передавать абсолютные пути. Папки вывода `report-cli/output/` и `/output/` в `.gitignore`.
- **Пробелы.** `-Pargs` режется по пробелам: путь с пробелом не пройдёт.
- **Шаблон боевого движка не подменить.** Шаблоны из `report-cli/.../templates/` боевой движок спецификации не читает: он
  использует только `report-ir/src/main/resources/templates/gost-spec.yaml`.
- **Id слотов.** В `runTemplate` блоки `stamp`, `leftMargin` и др. свободные имена (привязка к слотам включена только на
  пути спецификации), `reserves` работает у любого блока.

**Шаблоны практикума.** `report-cli/src/main/resources/templates/tutorial/` (`01-sheet.yaml` ... `10-complete.yaml`, шаги 9а-9ж
таблицы потока `09*-*.yaml`, данные `*-data.yaml`) и `tutorial/errors/` (намеренно сломанные шаблоны `01`-`28` для проверки
текстов ошибок): разбор в [template-guide.md](template-guide.md). Ещё: `purchased-list.yaml` (+ `purchased-list-data.yaml`,
группы, итоги), `table-demo.yaml`, `sheet-frame.yaml`. Справка по полям: [template-yaml.md](template-yaml.md).

## Golden-эталоны: как они работают и как с ними обращаться

Три семейства эталонов:

| Где | Что хранит | Тесты |
|---|---|---|
| `report-render-svg/src/test/resources/snapshots/simple-table.svg` | SVG-текст | `SvgSnapshotTest` |
| `report-render-pdf/src/test/resources/snapshots/simple-table.png` | растеризация PDF | `PdfRendererTest` |
| `reports/specification/src/test/resources/golden/*.txt` | текстовый дамп Layout IR (одна строка на элемент, длины в сотых мм) | `StaticBlocksGoldenTest` (спецификация, 9 эталонов), `TotalsGoldenTest`, `LineNumbersGoldenTest`, `StylesAndBreaksGoldenTest`, `MultiHeaderGoldenTest`, `RemainderGoldenTest` |

Все самозагружаются: если файла эталона нет, тест создаёт его и проходит, иначе сравнивает байт-в-байт:

```kotlin
val golden = File("src/test/resources/golden/$name.txt")
if (!golden.exists()) golden.writeText(actual)
assertEquals(golden.readText(), actual, "golden mismatch: $name")
```

**Правила (строго)**

1. **Не пересоздавать эталон вслепую.** Упавший golden это сигнал, а не помеха. Сначала `git diff`/сравнение: изменились ли
   только те строки, которые вы хотели изменить (пример: правило нижней границы добавило ровно 70 строк `Line` в 8 файлов
   `spec-*.txt` и ничего не убрало). Любая другая разница это регрессия.
2. Эталон нужно сверять с **ручным расчётом** (в новых тестах геометрия проверяется ещё и явными assert-ами с числами,
   посчитанными вручную), иначе при пересоздании тест проходит тривиально: сравнивает файл с самим собой.
3. Только после этого удалить файл эталона, прогнать тест, **открыть** пересозданный эталон и закоммитить его вместе с кодом.
4. Эталоны `spec-*` относятся к боевому `gost-spec.yaml`: правка этого YAML меняет их побайтно.
5. Для визуальной проверки PDF спецификации есть переменная окружения `GOLDEN_PDF_DIR`:
   `GOLDEN_PDF_DIR=/tmp/pdfs ./gradlew :reports:specification:test --tests '*StaticBlocksGoldenTest'` кладёт `<name>.pdf` рядом
   (контрольные PDF лежат в `reports/specification/src/test/resources/golden/pdf/`). Растеризация: `pdftoppm -r 150 -png файл.pdf out`.
6. Каталог `reports/` больше не в `.gitignore` (коммит `e588fac`), новые эталоны добавляются обычным `git add`.

Пересоздание старых SVG/PNG снапшотов (только после ручной проверки, что изменение законно):

```bash
rm report-render-svg/src/test/resources/snapshots/simple-table.svg
rm report-render-pdf/src/test/resources/snapshots/simple-table.png
./gradlew :report-render-svg:test :report-render-pdf:test
```

Единственная реальная проверка корректности пересозданного эталона это осмотр результата (PDF через `Read`, SVG текстом).

## Проверка архитектурных границ

Перед коммитом любых правок в `report-layout-ir` или рендереры — грепом,
не на глаз:

```bash
grep -rn "import dev.reportgenerator.ir" report-layout-ir/src/main/
grep -rn "import dev.reportgenerator.ir\." report-render-svg/src/main/ report-render-pdf/src/main/
```

Оба должны быть пустыми (`exit code 1`). Дополнительные проверки направлений зависимостей (в том числе `report-template`)
и зачем это важно: [architecture.md](architecture.md).
