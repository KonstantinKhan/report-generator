# Как собрать, протестировать, запустить

## Требования

- JDK 21 (используется как toolchain для всех модулей)
- Gradle не обязателен отдельно — есть wrapper (`./gradlew`)

## Сборка и тесты

```bash
./gradlew build            # весь проект, все 10 модулей, 39 тестов
./gradlew :report-layout:test   # тесты одного модуля
```

## Сгенерировать реальный документ

```bash
./gradlew :report-cli:run --args="output"
```

Пишет `report-cli/output/specification.svg` и `.pdf` — реальная
спецификация из зашитой в `Main.kt` тестовой фикстуры (4 позиции: Корпус,
Вал, Втулка, Болт М6). Если аргумент не передан — пишет в `output/`
относительно рабочей директории Gradle-таска (не всегда предсказуемо какой
именно — см. заметку ниже).

**Важно:** после правки любого файла — если результат не поменялся,
сначала проверь `stat`/`ls -la` на сгенерированный файл и на изменённый
исходник. Реальный случай из практики: пользователь поменял размер шрифта
в `Styles.kt`, посмотрел на PDF — visually показалось, что не изменилось.
Причина была banальной: PDF был сгенерирован ДО правки (`report-cli:run`
после правки не перезапускали). Урок — Gradle's `run`-таск не UP-TO-DATE
кэшируемый в смысле "пропустить исполнение", но если файл не перегенерить,
он и не появится с новым содержимым.

## Golden snapshot тесты — как они работают

Несколько тестов (`SvgSnapshotTest`, `PdfRendererTest`,
`SpecificationEndToEndTest`) используют самозагружающийся снапшот:

```kotlin
val golden = File("src/test/resources/snapshots/....svg")
if (!golden.exists()) {
    golden.parentFile.mkdirs()
    golden.writeText(actual)
}
assertEquals(golden.readText(), actual)
```

Первый прогон создаёт файл-эталон и коммитится в git. Каждый следующий
прогон сравнивает байт-в-байт. Если правка ЗАКОННО меняет рендер (например
поменялся размер шрифта, добавилась рамка ячейки) — старый эталон нужно
удалить руками перед прогоном тестов, чтобы он пересоздался:

```bash
rm report-render-svg/src/test/resources/snapshots/simple-table.svg
rm report-render-pdf/src/test/resources/snapshots/simple-table.png
rm reports/specification/src/test/resources/snapshots/specification.svg
./gradlew build
```

**Всегда открывай пересозданный эталон глазами** перед тем как коммитить —
тест "проходит" в момент пересоздания тривиально (сравнивает файл с самим
собой). Единственная реальная проверка корректности — визуальный осмотр
результата (PDF через `Read`, SVG — текстом или через артефакт).

## Проверка архитектурных границ

Перед коммитом любых правок в `report-layout-ir` или рендереры — грепом,
не на глаз:

```bash
grep -rn "import dev.reportgenerator.ir" report-layout-ir/src/main/
grep -rn "import dev.reportgenerator.ir\." report-render-svg/src/main/ report-render-pdf/src/main/
```

Оба должны быть пустыми (`exit code 1`). См.
[architecture.md](architecture.md) зачем это важно.
