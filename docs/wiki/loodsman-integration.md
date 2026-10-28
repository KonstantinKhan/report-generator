# Loodsman API интеграция

Интеграция состоит из трёх частей, каждая в своём слое:

- **`loodsman-client`** (`io.github.konstantinkhan.loodsman`) — отдельная библиотека: HTTP-клиент Loodsman PDM API v4.
  Класс `LoodsmanApi` с группами эндпоинтов, DTO, сессия. Ничего не знает о доменной модели.
- **`report-loodsman`** (`dev.reportgenerator.loodsman`) — слой DTO → домен: `LoodsmanProductStructureClient`
  реализует `PdmNodeStructureClient` из `report-domain` поверх `LoodsmanApi`, по YAML-конфигу маппинга.
- **`report-server`** (`dev.reportgenerator.server`) — Ktor REST: `POST /specifications/{versionId}` тянет структуру
  изделия, собирает спецификацию (`reports/specification`) и рендерит PDF.

Старый путь сборки (`PdmClient`, модуль `report-pdm`, `LoodsmanKtorPdmClient`, `Pdm2Domain`, а раньше `LoodsmanPdmClient` и
`SpecificationAssembly`) удалён в ветке `refactor/loodsman-api`. Единственный путь к структуре изделия — `PdmNodeStructureClient`.
История миграции — [development-status.md](development-status.md).

Этот документ — не общее описание модуля, а список конкретных грабель API, на которые уже наступили. Каждый пункт стоил
минимум одной сессии отладки.

## loodsman-client

### Что даёт

- `LoodsmanApi(config: LoodsmanConfig, httpClient: HttpClient)` — точка входа, final-класс без интерфейса.
  Эндпоинты сгруппированы по контроллерам Loodsman, см. [группы эндпоинтов](#группы-эндпоинтов) ниже. Плюс `logout()`.
  Сессия скрыта внутри: логин лениво при первом запросе (`SessionHolder`, под mutex), при 401/419 сессия сбрасывается
  и запрос повторяется один раз. Вызывающий код сессией не управляет, кроме `logout()`.
- `LoodsmanConfig(baseUrl, dbName, username, password)` — `toString()` скрывает пароль.
- `LoodsmanApiException(message, cause, status)` — все ошибки HTTP и сети. `status` — код ответа, `null` для сетевых сбоев.
  `isSessionExpired` = 401 или 419.
- `loodsmanHttpClient()` — готовый `HttpClient` на CIO. `HttpClientConfig<*>.installLoodsman()` — настройка для
  собственного `HttpClient`.

### Группы эндпоинтов

| Свойство `LoodsmanApi` | Класс | Контроллер Loodsman | Эндпоинты |
|---|---|---|---|
| `auth` | `AuthApi` | `/api/v4/Auth` | `databases()` (без сессии), `isAdmin()` |
| `objectInfo` | `ObjectInfoApi` | `/api/v4/ObjectInfo` | `propObjects(versionIds)`, `propObject(versionId)`, `infoAboutVersion(versionId)`, `linkedObjects(objectIds, linkTypeIds, inverse = false)`, `linkAttributes(linkId)` |
| `metaData` | `MetaDataApi` | `/api/v4/MetaData` | `typeList()`, `linkList()`, `attributeList(mode)`, `cacheVersion()` |
| `metaDataCustom` | `MetaDataCustomApi` | `/api/v4/MetaDataCustom` | `typesLinks()` (`types-links`: какие типы объектов могут быть связаны каким типом связи и в каком направлении) |

Итого 12 эндпоинтов и `logout()`. Одна группа — один файл. Конструкторы групп `internal`: создать группу снаружи нельзя.
Почему группы, а не плоский интерфейс, — в [design-decisions.md](design-decisions.md#группы-эндпоинтов-вместо-плоского-интерфейса).
До коммита `bd5afc1` был плоский интерфейс `LoodsmanApi` с 9 методами (включая `logout`) и реализация `KtorLoodsmanApi`;
заменено на группы.

**Правило: сессия и заголовок `web-loodsman-session` ставятся только в `SessionClient`.** Группы ходят через
`http.get(...)` из `SessionClient` (`SessionHolder.withSession`, повтор при 401/419). Голого `HttpClient` в группах нет;
исключение — `AuthApi.databases()`, эндпоинт, который отдаётся без сессии. Поэтому эндпоинт с сессией вызвать без неё нельзя.
Внутренний `Auth` оставлен только с `login`/`logout` для `SessionHolder`.

### Пример

```kotlin
val api = LoodsmanApi(LoodsmanConfig(baseUrl, dbName, username, password), loodsmanHttpClient())
try {
    api.objectInfo.propObjects(listOf(versionId))
    api.metaData.typeList()
    api.auth.databases()
} finally {
    api.logout()
}
```

### Как добавить эндпоинт

1. Найти группу по контроллеру Loodsman. Если контроллера ещё нет: создать `<Name>Api.kt` с конструктором
   `internal constructor(http: SessionClient, baseUrl: String)`, полем `blockBaseUrl = "$baseUrl/api/v4/<Controller>"`
   и свойством в `LoodsmanApi`.
2. Добавить метод в группу. Шаблон:
   `suspend fun имя(аргументы): Dto = http.get("$blockBaseUrl/<action>") { parameter("<имя>", значение) }`.
   Не `client.get` напрямую: иначе эндпоинт уйдёт без сессии.
3. DTO — в `dto/`, по конвенции `dto-conventions`.
4. Тест — в `LoodsmanApiTest` на `MockEngine`.

### Подключение из другого проекта

Координаты: `io.github.konstantinkhan:loodsman-client:0.1.0`. Варианты (описаны, в ходе работы не выполнялись):

- **Мультимодульный проект с этим репозиторием:** `include(":loodsman-client")` в `settings.gradle.kts` и
  `implementation(project(":loodsman-client"))`.
- **Composite build:** `includeBuild("путь/к/report-generator")` в `settings.gradle.kts` потребителя. Gradle подставит
  локальный проект вместо координат `io.github.konstantinkhan:loodsman-client`.
- **Локальная Maven-публикация:** выполнить `./gradlew :loodsman-client:publishToMavenLocal` в этом репозитории, в
  потребителе добавить `mavenLocal()` в `repositories` и объявить зависимость по координатам выше.

### Обязательно: `installLoodsman()` для своего HttpClient

Если потребитель создаёт `HttpClient` сам, он обязан вызвать `installLoodsman()`:

```kotlin
val client = HttpClient(CIO) { installLoodsman() }
```

Без неё не работают: `expectSuccess` и `HttpResponseValidator`, поэтому ответы с ошибкой не превращаются в
`LoodsmanApiException` и `isSessionExpired` не срабатывает, значит повторного логина нет; без `ContentNegotiation`
DTO не декодируются; без `HttpCookies` сессия не держится между запросами. `installLoodsman()` также включает
`HttpTimeout` и логирование Ktor на уровне INFO.

### Зависимости

- `ktor-client-core` — `api`, типы `HttpClient` входят в публичный API.
- `ktor-client-cio` — `implementation`, попадает в runtime-classpath потребителя, потому что `loodsmanHttpClient()`
  строит клиент на CIO. Потребитель со своим engine (OkHttp, Java и т.д.) может исключить CIO из транзитивных
  зависимостей и не вызывать `loodsmanHttpClient()`. Исключение в Gradle:
  `implementation("io.github.konstantinkhan:loodsman-client:0.1.0") { exclude(group = "io.ktor", module = "ktor-client-cio") }`.
- `kotlinx-serialization-json` и `ktor-serialization-kotlinx-json` — `implementation`.
- Логирование: Ktor `Logging` пишет через SLF4J. Привязку (logback, slf4j-simple и т.д.) выбирает потребитель;
  без неё SLF4J выводит предупреждение о NOP-логгере. В `report-loodsman` привязка — logback.

## Авторизация

- Заголовок `web-loodsman-session`, **не** `Authorization`.
- Куки между запросами: `installLoodsman()` ставит `HttpCookies`. Без них сессия не держится.
- `POST /api/v4/Auth/login` → `{ sessionId }`.
- При 401/419 сессия истекла: `SessionHolder` сбрасывает `sessionId`, логинится заново и повторяет запрос один раз
  (`isSessionExpired`). Другие ошибки не повторяются.
- `logout()` закрывает сессию, если она открыта. `report-server` вызывает его при остановке.

## Свойства vs атрибуты — главная ловушка

Loodsman хранит данные об объекте в **двух не связанных источниках**:

- **Свойства** (properties) — `/api/v4/ObjectInfo/get-prop-objects`. Возвращает
  `product`, `type`, `version`, `state`. Дёшево, один объект = одна запись.
- **Атрибуты** (attributes) — `/api/v4/ObjectInfo/get-info-about-version-mode-3`.
  Возвращает список `[{name, value}]` (не keyed map — искать по `name` вручную).

Это разные API, разные данные, и **не всегда одно дублирует другое**.

### Правило ключевого атрибута (то, из-за чего был баг с "Attribute 'Наименование' is missing")

Поле `product` в свойствах — слот «ключевой атрибут», и что он означает, **зависит от типа объекта**. Ключ типа задаётся
`attrName` из `get-type-list` (см. «Метаданные: снимок»). Значение ключа лежит в `product`, а не в списке атрибутов
объекта. Подтверждено на сервере (2026-10-22):

| Тип объекта | `product` содержит | «Наименование» в списке атрибутов |
|---|---|---|
| Сборочный чертеж | Обозначение | Да |
| Комплекс | Обозначение | Да |
| Сборочная единица | Обозначение | Да |
| Деталь | Обозначение | Да |
| Комплект | Обозначение | Да |
| Стандартное изделие | полное обозначение (напр. «Подшипник 411 ГОСТ 520-2011») | Нет |
| Прочее изделие | полное обозначение | Нет |
| Материал по КД | полное обозначение | Нет |

Единое правило чтения атрибута `X` у объекта типа `T` (`readAttribute` в модуле `mapping`): если `X` строго, `==`, равно ключевому атрибуту `T`, значение берётся из `product`; иначе — из списка атрибутов
(`get-info-about-version-mode-3`). Пустое значение — отсутствует. Блока `identity` в YAML нет:
`designation` и `name` — обычные правила `attributes` (имена `Обозначение` и `Наименование`).

Следствия для восьми видов: `designation` берётся из `product` (через ключ), `name` — «Наименование» из списка.
У стандартных и прочих изделий и материалов «Наименование» нет, поэтому `attributes[Name]` = `AttributeValue.Empty`, а обозначение
остаётся в ключевом атрибуте (`attributes[Designation]`); спецификация переносит его в столбец «Наименование» (см. «Правила преобразования в спецификацию»).

Пустой `product` при наличии ключа → `NodeKey.Missing` и `attributes[Designation]` = `AttributeValue.Empty`. Тип без ключевого атрибута → `NodeKey.Missing`.
Ключ узла (`PdmNode.key`) — `NodeKey.Value(attribute, text)`: `attribute` — доменный ключ правила, чьё имя совпало с
ключевым атрибутом вида; если такого правила нет — `AttributeKey.Unmapped(имя ключевого атрибута)`.

Реализовано кодом и конфигом: ключевой атрибут задаёт тип объекта (`attrName`), `designation` и `name` — обычные правила
`attributes` в `loodsman-mapping.yaml`. Корень документа и дочерние объекты читаются одним кодом (`toDomain` и
`readAttribute`), поэтому правило одинаково для всех. Старый баг был в том, что правило реализовали для детей, а для корня забыли.

## `idType` в связях — это тип СВЯЗИ, не тип ОБЪЕКТА

`/api/v4/ObjectInfo/get-linked-objects-for-objects` возвращает список связей:

```
LinkedObjectDto(idLink → linkId, idChild → versionId, idType → linkTypeId, minQuantity, maxQuantity)
```

`idType` здесь — id из `/api/v4/MetaData/get-link-list` (тип **связи**: "Состоит из",
"Документация" и т.д.), а **не** id из `/api/v4/MetaData/get-type-list` (тип
**объекта**: Деталь/СЕ/Стандартное/...). Это два разных ID-пространства с разными
эндпоинтами.

Был баг: код брал `child.idType` (тип связи, значения вроде 60/62/64) и искал его в
карте, построенной из `get-type-list` (типы объектов). Числа иногда совпадали
случайно, из-за чего объект классифицировался как не-Деталь/СЕ и падал на
отсутствующем `Наименование`.

**Правильный источник типа объекта** — `prop.type` из `get-prop-objects` для
конкретного `idChild`. Он запрашивается в каждом уровне обхода, отдельный `get-type-list`
для этого не нужен.

## Виды объектов и связи

Маппинг названия вида Loodsman → `PdmNodeKind` задаёт секция `kinds` в YAML. Сравнение названий — строгое, правило (в) ниже.
Затем `PdmNodeKind` → раздел спецификации раскладывает `PdmNode.toSpecificationData()` (`ProductNodeSpecification.kt`):

| Тип Loodsman | `PdmNodeKind` | Раздел спецификации |
|---|---|---|
| Сборочный чертеж | `AssemblyDrawing` (id `ASSEMBLY_DRAWING`) | Документация (только по «Документы») |
| Спецификация | `Specification` (id `SPECIFICATION`) | — (не попадает в отчёт) |
| Чертеж детали | `DetailDrawing` (id `DETAIL_DRAWING`) | — (не попадает в отчёт) |
| Комплекс | `Complex` | — (раздела нет, пропускается) |
| Сборочная единица | `Assembly` | Сборочные единицы (`assemblies`) |
| Деталь | `Part` | Детали (`parts`) |
| Стандартное изделие | `Standard` | Стандартные изделия (`standards`) |
| Прочее изделие | `Other` | Прочие изделия (`others`) |
| Материал по КД | `Material` | Материалы (`materials`) |
| Форма материала | `MaterialForm` (id `MATERIAL_FORM`) | Материалы (`materials`) через материал по «Изготавливается из ...» (см. ниже) |
| Комплект | `Kit` | — (раздела нет, пропускается) |

Связи: `relations` в YAML — список правил («Состоит из ...» → `COMPOSITION`, «Документы» → `DOCUMENT`,
«Технологическая ДСЕ для» → `TECHNOLOGICAL_PART`; правила — в [«Правила связей»](#правила-связей-relations)). Состав идёт по `COMPOSITION`,
документация (сборочный чертеж) — по `DOCUMENT` (имя связи не проверено на живом стенде; нет связи — группа пуста).
Количество у документации не нужно. `TECHNOLOGICAL_PART` в спецификацию не попадает (`ProductNodeSpecification` его не выводит).

**Форма материала и «Изготавливается из ...».** Форма (`MaterialForm`) входит в состав сборочной единицы по `COMPOSITION`
(связь «Состоит из ...», у неё нет атрибутов «Позиция» и «Примечание»). Её материал читается по `MADE_FROM`
(`Relation.MadeFrom`, `MadeFromUsage`): правило `link: "Изготавливается из ..."`, `direction` FORWARD, `from: [Форма материала]`.
Правило `from` ограничивает чтение: по другим видам эта связь не раскрывается. `MADE_FROM` не входит в `requiredRelations`,
поэтому его отсутствие в метаданных ошибкой не считается, но правило с неизвестным именем связи валидатор отклонит.
В запросе спецификации (`specificationQuery`) `MadeFrom` стоит в `relations`, без своего `relationDepth`: форма на уровне 1
(предел состава = 1), её связь «Изготавливается из ...» раскрывается до уровня 2 общим `depth = 2`. Материал попадает
в `MadeFromUsage.target` формы с атрибутами «Обозначение» и «Наименование». Связь материала (положение, количество,
единица, примечание) берётся с внешней связи «Состоит из ...» формы, а не с «Изготавливается из ...».

Обозначение стандартных и прочих изделий в столбец «Обозначение» берётся из отдельного атрибута Loodsman «Обозначение
изделия» (правило `productDesignation` с `byKind: {STANDARD, OTHER}`); «Обозначение» (ключевой атрибут, `product`) идёт в
столбец «Наименование». Список атрибутов объекта (`get-info-about-version-mode-3`) читается только по необходимости
(см. «Что попадает в PdmNode и Usage»).

> **Риск (не подтверждено на сервере).** Наличие атрибута «Обозначение изделия» у типов «Стандартное изделие» и
> «Прочее изделие» не проверено на живом стенде. Если атрибута нет, `MappingConfigValidator` упадёт при старте с
> `attribute 'Обозначение изделия' not found in Loodsman`: это и есть проверка.

## Прочее

- Batch-эндпоинты атрибутов (`/objects/by-ids/attributes/...`) возвращают
  `isSuccess=false` — не работают, не использовать. Только single-object запросы
  по одному `idVersion`/`idChild` за раз.
- `get-info-about-version-mode-3` отдаёт атрибуты списком `[{name, value}]`, не
  keyed map — собирать через `associateBy { it.name }` самим.

## Правила преобразования в спецификацию

Правила, которые определяют, что попадает в спецификацию и как. Код: `LoodsmanProductStructureClient`
(загрузка), `ProductNodeSpecification.kt` (`reports/specification`), `mapping/KindMapper.kt` (сравнение названий).
Пример для тестов: `SpecificationPipelineTest` (report-loodsman) фиксирует эти правила.

**(а) Количество.** Источник — поля связи `minQuantity` / `maxQuantity` (`link.fields`), не текстовый атрибут
«Количество». Правило в `TreeAssembler.quantityOf`:

- оба `null` → `Quantity.Missing`;
- ровно одно `null` → `LoodsmanApiException` (половина диапазона — ошибка данных Loodsman);
- `min == max` → `Quantity.Exact`;
- иначе `Quantity.Range`.

В спецификации `Exact` даёт число, `Range` и `Missing` — `SpecificationDataException` (диапазон в спецификации не
выводится). У документации количества нет: в разделе документации поля количества нет, ячейка пустая.

**(а2) Позиция.** Источник — атрибут связи «Позиция» по правилу `link.attributes.position`. Правило есть, а значения
нет — `Field.Missing` → позиция пустая. Значение в `Field.NotLoaded` в пайплайне не попадает (отсутствие правила или
значения — не ошибка загрузки). Сортировка по позиции внутри раздела и место элементов без позиции — в
[specification-table-format.md](specification-table-format.md).

**(а3) Формат и примечания.** Формат сборочной единицы — из документов самой сборочной единицы (первый «Спецификация»
с непустым «Формат»), детали — из «Чертёж детали». Для этого `specificationQuery` читает документы детей корня:
`depth = 2`, состав раскрывается только до детей корня (`relationDepth = {Composition: 1}`), `Format` — атрибут
документа, `Note` — атрибут связи состава. Примечание состава — `LinkAttributeKey.Note`, зона — `LinkAttributeKey.Zone` (атрибут связи «Зона», relation COMPOSITION) из `CompositionUsage.attributes`.
Правила записи в колонки «Формат» и «Примечание» (сложный формат `*)`, единица материала первой строкой) — в
[specification-table-format.md](specification-table-format.md#правила-формата-и-примечаний-спецификации).

**(б) Материал.** У материала обозначение (`AttributeKey.Designation`, ключевой атрибут) берётся из `product` (свойство `PRODUCT`),
наименование (`AttributeKey.Name`) — из атрибута «Наименование» (`byKind` для материала нет). В спецификации обозначение **выводится в столбец «Наименование»**,
столбец обозначения пуст. Правило отчёта живёт в конвертере `PdmNode.toSpecificationData()`, а не в маппинге: маппинг отдаёт
доменную модель как есть. Без обозначения у материала — `SpecificationDataException`. Единица измерения только у
материалов в колонке примечаний — [specification-table-format.md](specification-table-format.md).

**(б2) Стандартное и прочее изделие.** Обозначение (`AttributeKey.Designation`, ключевой атрибут, полное обозначение из `product`)
выводится в столбец «Наименование», а обозначение из атрибута «Обозначение изделия» (`AttributeKey.ProductDesignation`,
правило `productDesignation`) — в столбец «Обозначение». Нет «Обозначения изделия» — столбец пуст (ошибки нет); нет или не
загружено «Обозначение» — `SpecificationDataException`. Прежнее правило «наименование из атрибута «Наименование» имеет приоритет»
снято: у этих видов «Наименование» в Loodsman отсутствует. Для остальных видов правило (б) не меняется. Код: `standardDesignationAndName()`.

**(в) Названия строго.** `KindMapper` ищет вид точным совпадением `config.kinds[имя]`: регистр, `ё` и пробелы важны.
Нормализации (lowercase, `ё` → `е`, схлопывание пробелов) нет. Расхождение имени ловит валидация на старте: ошибка
перечисляет доступные имена из Loodsman.

**(б3) Материал через форму.** Форма материала (`MaterialForm`) выводится в раздел «Материалы» по материалу из первого
`MadeFromUsage` формы с видом `Material` (если их несколько, берётся первый). Наименование и обозначение — с узла материала
(через `designationAndName()`), позиция, количество, единица и примечание — с внешней связи формы. Форма без материала
(нет `MadeFromUsage` с видом `Material`) — `SpecificationDataException` с id формы. Код: `materialNode()` в `ProductNodeSpecification.kt`.

**(г) Неизвестные виды.** `PdmNodeKind.Unmapped` (политика `onUnmapped.kind: KEEP` сохраняет такой вид в домене) и
`UnmappedUsage` спецификация пропускает, в отчёт не попадают. Узел с неизвестным видом при `KEEP` раскрывается дальше:
его связи читаются, потомки попадают в дерево (пример: «Технологическая деталь»). Если неизвестен вид самого корня, загрузка падает
`LoodsmanApiException` (`Root object ... has unmapped type`).

**Корень документа.** Без обозначения — `SpecificationDataException`. Без наименования вместо него выводится
обозначение.

**Источник значений.** Спецификация читает обозначение, наименование и формат из `PdmNode.attributes` (запрос
`specificationQuery` просит эти ключи; примечание состава — атрибут связи `note`): `Text` → значение, `Empty` → отсутствует, ключа нет → «не загружено»
(`SpecificationDataException`). У `PdmNode` нет полей `designation`/`name`: читается только `attributes`.

## Конфиг маппинга и PdmNodeStructureClient

`LoodsmanProductStructureClient(api, mapping, concurrency = 8, metadata)` — адаптер, реализующий интерфейс `PdmNodeStructureClient`
из `report-domain` (`loadTree(StructureQuery): PdmNode`). Возвращает дерево состава изделия в доменной модели
(`PdmNode` + `Usage`), не зависящей от словаря Loodsman. Соответствие названий типов, связей и атрибутов PDM
моделям задаётся конфигом маппинга, а не кодом. Конструктор `internal`, без значения по умолчанию для `mapping`:
создать клиент с непроверенным маппингом снаружи модуля нельзя. Собирает его только `LoodsmanContext.bootstrap`.

## LoodsmanContext: сборка и fail-fast

`LoodsmanContext` (`report-loodsman`) — единственная точка сборки Loodsman-компонентов для приложения: свойства
`client: PdmNodeStructureClient` и `metadata: MetadataCache`. Конструктор закрытый, экземпляр создаётся только через
`LoodsmanContext.bootstrap(api, mapping)`:

1. создаёт `MetadataCache(api) { MappingConfigValidator.validate(mapping, it) }`;
2. вызывает `initialize()`: загрузка снимка и семантическая проверка маппинга;
3. собирает `LoodsmanProductStructureClient(api, mapping, metadata = cache)`.

Гарантии: если метаданные не загрузились или маппинг не совпал с Loodsman, `bootstrap` падает с сообщением, где перечислены
все ошибки проверки (`Metadata validation failed: ...`). Контекст с непроверенным маппингом получить нельзя.
`mapping` передаётся явно: `bootstrap` не читает YAML сам. Загрузка — в вызывающем коде (`report-server` в `Main.kt`:
`MappingConfigLoader.load()`, структурная проверка, затем `bootstrap`).

### Структура YAML конфига маппинга

Ресурс `report-loodsman/src/main/resources/loodsman-mapping.yaml` (актуальная версия, полный файл):

```yaml
kinds:                      # имя типа в PDM -> id вида (точное совпадение, см. правило (в))
  Деталь: PART
  Сборочная единица: ASSEMBLY
  Сборочный чертеж: ASSEMBLY_DRAWING
  Комплекс: COMPLEX
  Стандартное изделие: STANDARD
  Прочее изделие: OTHER
  Материал по КД: MATERIAL
  Комплект: KIT
  Спецификация: SPECIFICATION
  Чертеж детали: DETAIL_DRAWING
  Форма материала: MATERIAL_FORM

relations:                  # правила связей: список (см. «Правила связей»)
  - link: "Состоит из ..."  # прямое имя типа связи в Loodsman
    as: COMPOSITION         # смысл в домене (Relation)
    from: [Сборочная единица]
  - link: "Документы"
    as: DOCUMENT
    from: [Сборочная единица, Деталь, Прочее изделие]
  - link: "Технологическая ДСЕ для"
    direction: INVERSE      # по умолчанию FORWARD
    from: [Деталь]          # с каких типов узлов читать; пусто — любые
    as: TECHNOLOGICAL_PART
  - link: "Изготавливается из ..."
    as: MADE_FROM
    from: [Форма материала]

requiredRelations:          # смыслы из relations (поле as), которые должны существовать в метаданных PDM, иначе LoodsmanApiException
  - COMPOSITION
  - DOCUMENT

attributes:                 # атрибуты узла: id ключа модели -> имя атрибута в PDM
  designation:              # обозначение: для типа с ключом «Обозначение» читается из product
    name: Обозначение
  name:
    name: Наименование
  productDesignation:       # обозначение изделия: только стандартные и прочие изделия, для остальных видов правила нет
    byKind:
      STANDARD: Обозначение изделия
      OTHER: Обозначение изделия
  mass:
    name: Масса
  companyName:              # атрибут есть только у сборочных единиц и деталей: общего name нет
    byKind:
      ASSEMBLY: Наименование предприятия
      PART: Наименование предприятия
  format:                   # формат есть только у спецификации, чертежа детали и сборочного чертежа: общего name нет
    byKind:
      SPECIFICATION: Формат
      DETAIL_DRAWING: Формат
      ASSEMBLY_DRAWING: Формат

properties:                 # поля узла из свойств объекта (get-prop-objects): PRODUCT, VERSION, STATE, TYPE
  version: VERSION
  state: STATE

link:
  fields:                   # поля связи из списка связей (get-linked-objects-for-objects)
    quantityMin: MIN_QUANTITY   # числовые: MIN_QUANTITY, MAX_QUANTITY
    quantityMax: MAX_QUANTITY
    unit: UNIT                  # текстовое: UNIT
  attributes:               # атрибуты связей модели: id ключа модели -> список правил
    position:               # встроенный: без запроса из query (COMPOSITION), попадает в CompositionUsage.position
      - relation: COMPOSITION
        name: Позиция
    note:
      - relation: COMPOSITION
        name: Примечание

onUnmapped:
  kind: KEEP
  relation: SKIP
```

**`designation`, `name`** — обычные правила `attributes` (ключи `Registries.attributeKeys`). Чтение — через
`readAttribute` с учётом ключевого атрибута вида (см. «Правило ключевого атрибута»). Блока `identity` нет.

**`attributes`** — узловые атрибуты модели. Каждый ключ (`mass`, `companyName`) обязан быть в `Registries.attributeKeys`.
Правило содержит:
- `name` — имя атрибута в PDM по умолчанию;
- `byKind` — переопределение имени для отдельных видов (id вида -> имя).

Должен быть задан `name` или непустой `byKind`. Если для вида имени нет, значение узла — `AttributeValue.Empty`.

**`link.attributes`** — атрибуты связей модели (ключи `position`, `note` обязаны быть в `Registries.linkAttributeKeys`).
Для каждого ключа — список правил:
- `relation` — id отношения (обязателен);
- `parent`, `child` — множества id видов родителя/ребёнка (пусто = для всех);
- `name` — имя атрибута связи в PDM.

Ключ `position` встроенный: в `StructureQuery.linkAttributes` его указывать нельзя — `LoodsmanApiException`.
Он не запрашивается, а вычисляется при сборке дерева: только для COMPOSITION, по правилу пары видов; значение
ищется среди атрибутов связи и проецируется в типизированное поле `CompositionUsage.position` (в
`Usage.attributes` не дублируется). Нет правила под пару видов или значения → `Field.Missing`; отдельного запроса
ради позиции нет — она читается из тех же `get-link-attributes`, что и остальные атрибуты связи.
Остальные ключи (`note`) загружаются только по запросу. Правило выбирается по специфичности:
оба селектора (3) > только `child` (2) > только `parent` (1) > без селекторов (0). Пересечение одинаково специфичных
правил — ошибка валидации.

### Правила связей (`relations`)

`relations` — список правил. Поля правила:

- `link` — прямое имя типа связи в Loodsman (из `get-link-list`), сравнение строгое;
- `as` — смысл в домене (`Relation`): `COMPOSITION`, `DOCUMENT`, `TECHNOLOGICAL_PART`. Домен направления не знает;
- `direction` — `FORWARD` (по умолчанию) или `INVERSE`. От него зависит параметр `inverse` запроса `linkedObjects`;
- `from` — имена типов Loodsman, с узлов которых читается связь; пусто — любые типы.

Одно имя `link` может быть в двух правилах с разными направлениями. Правила с одинаковыми `link` и `direction`
не должны пересекаться по `from` (пустой `from` пересекается со всем); это проверяет `MappingConfig.validate()`.
Старый формат (map «имя связи: ID») не поддерживается.

**Обход по направлению.** `LinkLoader` для каждого раскрываемого узла выбирает правила, подходящие по его типу (`from`),
группирует их по направлению и делает один запрос `linkedObjects` на группу. Правило, не подошедшее по `from`,
отсеивается до запроса: узел без подходящих правил не получает ни одного вызова `linkedObjects`.

**Цена ошибки в `from` — тихая.** Дети типов вне списка не загружаются и в `usages` не попадают без единой ошибки.
Пример: `from: [Сборочная единица]` на `COMPOSITION` отсекает состав комплексов, комплектов и тех. ДСЕ, а заодно
лишает обход рёбер, через которые раньше находились циклы. Поэтому `from` сужают, только когда типы-носители связи
заведомо известны; типовой случай — `from` на `DOCUMENT` (документы держат сборочные единицы и детали, а запрос
документов у документов — почти всегда пустой). Валидатор проверяет каждый тип из `from`: существование в
метаданных и допустимость направления по `types-links`.
Факт, проверенный на живом Loodsman: при `inverse=true` для объекта X ответ содержит записи с `idParent` = X и
`idChild` = сосед на другом конце связи. Поэтому обход и `TreeAssembler` читают `idChild` как цель и для `INVERSE`:
`Usage.target` — связанный узел; для `INVERSE` это не потомок, а сосед.

Пример: связь id 11 «Технологическая ДСЕ для» (обратное имя «Технологическая ДСЕ»). В самой связи технологическая
деталь (тип 44) — родитель, деталь (тип 60) — ребёнок. От детали связь читается `direction: INVERSE`, `from: [Деталь]`:
в ответе `idParent` = деталь, `idChild` = технологическая деталь. Поэтому в `from` указывается тип детали (60).

**Ограничение.** `types-links` (см. «Метаданные: снимок») не защищает от подъёма по вложенности однотипных узлов:
технологическая сборка 65 ↔ 65 имеет и прямое (1), и обратное (-1) направление. Поэтому `from` задаётся вручную.

### Реестры (Registries)

`Registries` (`mapping/Registries.kt`) содержит словари известных видов, связей и ключей атрибутов:

```kotlin
Registries.itemKinds          // ASSEMBLY, PART, STANDARD, OTHER, MATERIAL, ASSEMBLY_DRAWING, COMPLEX, KIT
Registries.relations          // COMPOSITION, DOCUMENT, TECHNOLOGICAL_PART
Registries.attributeKeys      // mass, companyName
Registries.linkAttributeKeys  // position, note
```

`MappingConfig.validate()` проверяет, что все ключи в конфиге существуют в реестрах, и что правила каждого ключа
`link.attributes` (включая `position`) не пересекаются по селекторам одинаковой специфичности.

### Политика Unmapped

```yaml
onUnmapped:
  kind: KEEP      # или SKIP, FAIL
  relation: SKIP  # или KEEP, FAIL
```

- **KEEP** — неизвестный тип сохраняется как `PdmNodeKind.Unmapped` и узел раскрывается дальше.
- **SKIP** — объект неизвестного типа пропускается (ребёнок не появляется в `usages`). Если неизвестен тип
  самого корня — `LoodsmanApiException`.
- **FAIL** — исключение при неизвестном типе.

Политика `onUnmapped.kind` применяется к видам объектов. Правила связей при загрузке берутся только из `relations`
конфига для отношений из `StructureQuery.relations`. Запрос `Relation.Unmapped` в загрузке не поддерживается:
`LoodsmanApiException` (`Relation ... is not configured in mapping`). Поэтому `onUnmapped.relation` при обходе не
применяется: связи, не описанные в `relations`, не читаются.

### Загрузка конфига

```kotlin
val config = MappingConfigLoader.load()  // LOODSMAN_MAPPING_FILE env или classpath:/loodsman-mapping.yaml
val config = MappingConfigLoader.parse(yamlText)  // из строки
```

Разбор YAML использует полиморфизм по свойству `type` (Kaml `PolymorphismStyle.Property`).

Проверка конфига идёт в две фазы:

1. **Структурная** — при загрузке (`MappingConfigLoader.parse` → `MappingConfig.validate()`): реестры, `link.fields`,
   дубликаты правил связей. Не требует связи с Loodsman.
2. **Семантическая** — против снимка метаданных: `MappingConfigValidator.validate(config, snapshot): List<String>`.
   Собирает все ошибки сразу, не бросает исключений. Формат сообщения: `<путь в конфиге>: <что не так>`, например
   `kinds: type 'X' not found in Loodsman`. Проверяется:
   - ключи `kinds` — среди типов объектов снимка; у каждого типа есть ключевой атрибут, и он среди атрибутов Loodsman
     (`kinds: type 'X' has no key attribute in Loodsman`, `kinds: key attribute 'K' of type 'X' not found among Loodsman attributes`);
   - `relations`: `link` каждого правила — среди связей снимка; каждый тип из `from` — среди типов объектов;
     направление правила допустимо по `types-links` для каждого типа из `from`: `FORWARD` требует `1` или `0`,
     `INVERSE` требует `-1` или `0` (`linkDirections(тип, id связи)`);
   - каждый элемент `requiredRelations` — хотя бы одно имя связи из `relations` (поле `link`) найдено в снимке;
   - атрибуты: `attributes.*.name` и `attributes.*.byKind`, `link.attributes.*[].name`.

   Не проверяются: доменные реестры, `PropField`, `LinkField`, `onUnmapped`, `properties`.

   Фаза 2 подключена в `LoodsmanContext.bootstrap` (см. [выше](#loodsmancontext-сборка-и-fail-fast)):
   `initialize()` падает на старте, `refresh()` возвращает `Failed` и оставляет старый снимок.

### Правила сравнения имён

- **Сравнение строгое, без модификаций.** Имена типов объектов, связей и атрибутов, значения из Loodsman и ключи yaml
  сравниваются через `==` как есть: без `lowercase`, замены `ё` → `е`, схлопывания пробелов и без `trim`. Регистр,
  `ё` и пробелы важны с обеих сторон. Проверки на пустоту (пустая строка, `null`) сохраняются.
- **Ошибка валидации перечисляет доступные имена.** Если тип, связь или `requiredRelations` не найдены, сообщение
  содержит отсортированный список имён из Loodsman (`Available: ...`), чтобы пользователь скопировал точное имя.

### Устройство клиента

`LoodsmanProductStructureClient` — фасад: `loadTree` проверяет запрос, ведёт обход по уровням и проверяет корень;
`loadNode` читает один узел. Остальное — `internal` классы пакета `dev.reportgenerator.loodsman`:

- `AttributeRequests` — правила атрибутов (`ruleOf`), ключевой атрибут вида, `AttributeRequest` для вида.
- `NodeLoader` — свойства и атрибуты объектов: `loadNodes` пачками по 100, `loadNode` для одного узла, `toPdmNode`.
- `LinkLoader` — связи уровня по правилам `relations`: правила отбираются по типу узла (`from`), группируются по направлению,
  один запрос `get-linked-objects-for-objects` на группу.
- `LinkAttributeLoader` — атрибуты связей, правило позиции (`positionRuleOf`), ключи связей в реестре (`linkAttributeIdOf`).
- `TreeAssembler` — рекурсивная сборка `PdmNode` с `usages`, атрибуты связей, `quantityOf`.
- `Limiter` — параллельные вызовы с лимитом `concurrency` (семафор создаётся на каждый вызов `withPermits`).
- `StructureModels.kt` — `TraversalState` (узлы и связи обхода), `LoadedNode`, `Link`, `LinkAttributesData`.

### Алгоритм загрузки структуры

`LoodsmanProductStructureClient.loadTree(query: StructureQuery)`:

1. **Разрешение связей и атрибутов узла.** Для каждого отношения из `query.relations` берутся правила из `relations` конфига;
   для каждого правила ищется id типа связи в `get-link-list` по полю `link` (имя строго, `==`; горизонтальные связи
   тоже попадают в снимок). Отношение без правила — `LoodsmanApiException`. Каждая связь из `requiredRelations`
   конфига обязана быть в метаданных, иначе `LoodsmanApiException`. Для каждого атрибута узла из `query.attributes`
   проверяется наличие конфигурированного правила в `mapping.attributes`, иначе `LoodsmanApiException`.

2. **Обход по уровням (фаза 1).** `depth = 0` — только корень, запросов связей нет. `depth = N` — связи разворачиваются
   у узлов уровней `0..N-1`, дети уровня `N` загружаются (свойства и атрибуты), но их `usages` пусты.
   `depth = Int.MAX_VALUE` — полное дерево: конечность обеспечивают пустой frontier и дедупликация узлов, а не число.
   `query.relationDepth` задаёт отдельный предел по связи: связь `R` раскрывается из узлов уровней
   `0..min(depth, relationDepth[R])-1` — глубже отсечки дети по `R` не запрашиваются вовсе (нет запросов связей,
   свойств и атрибутов), остальные связи продолжают разворачиваться до `depth`. Типичное применение: состав до
   k-го уровня, документы на всех уровнях (`relationDepth = mapOf(Relation.Composition to k)`). Ключ вне
   `query.relations` или значение `< 1` — `LoodsmanApiException`.
   На каждом уровне:
   - свойства всех узлов уровня запрашиваются одним `get-prop-objects` (чанками по 100);
   - тип узла определяется по `type` из свойств (`KindMapper`), а не по обозначению;
   - связи (`get-linked-objects-for-objects`) и узловые атрибуты (`get-info-about-version-mode-3`, один запрос на узел)
     запрашиваются параллельно через `Limiter` (`Semaphore(concurrency)`) — если нужны; связи — по правилам, подходящим
     по типу узла (`from`), один запрос на каждое направление (`FORWARD` / `INVERSE`);
   - следующий уровень — дети, которых ещё нет среди загруженных узлов. Поэтому обход конечен даже при цикле.

3. **Загрузка атрибутов связей (фаза 2).** ПОСЛЕ полного обхода дерева: если запрошены `query.linkAttributes`
   или есть правило для `position`, для каждой загруженной связи с известными видами обоих концов, у которой правило
   подходит по тройке, делается один запрос `/api/v4/ObjectInfo/get-link-attributes` (параллельно, через `Limiter`).
   Результаты собираются в карту `linkId → атрибуты`.

4. **Кэш.** Свойства, узловые атрибуты и связи хранятся по `idVersion`/`idLink`. Узел разворачивается один раз на
   минимальной глубине, повторные вхождения в дерево используют те же данные. Метаданные (типы связей и объектов)
   берутся из `MetadataSource` один раз на `loadTree` (см. раздел «Метаданные: снимок, MetadataCache, обновление»).

5. **Сборка дерева** идёт из кэша рекурсией с остаточной глубиной, используя загруженные атрибуты связей. Цикл
   определяется по текущему пути → `LoodsmanApiException` с путём. Дети, чей тип пропущен политикой `SKIP`, в
   `usages` не попадают. `PdmNode.version` и `PdmNode.state` всегда заполняются из `prop.version`/`prop.state`.

**Стоимость по API:**
- Свойства: батч `get-prop-objects` (один на уровень);
- Узловые атрибуты: один `get-info-about-version-mode-3` на узел (если нужны);
- Связи: один `get-linked-objects-for-objects` на раскрываемый узел — только если уровень разрешён `depth`/`relationDepth`
  и тип узла подошёл по `from`; отсечённые по любому из них узлы не стоят ни одного вызова;
- Атрибуты связей: один `get-link-attributes` на связь (если нужны или есть правила позиции).

Неустранимый остаток без `from`: у каждого документного узла на разрешённых уровнях спрашиваются его документы —
у чертежей и спецификаций такой запрос обычно пуст (один лишний вызов на узел-документ). Лечится `from` на
`DOCUMENT` с перечнем типов-носителей документов.

**Стоимость запроса спецификации (`specificationQuery`, depth 2, состав до детей корня).** Относительно прежнего
запроса с depth 1 добавляются вызовы только за документами детей корня. Пусть N — число детей корня видов «Сборочная
единица», «Деталь», «Прочее изделие» (у них есть правило `DOCUMENT`), D — число документов этих детей:
- связи документов: +N вызовов `get-linked-objects-for-objects` (по одному на ребёнка; стандартные изделия и материалы
  правила `DOCUMENT` не имеют — вызова нет);
- свойства документов: +⌈D/100⌉ батчей `get-prop-objects` (один уровень, чанки по 100);
- атрибуты документов (обозначение, наименование, формат): +D вызовов `get-info-about-version-mode-3`;
- атрибуты связей: +0. У связи состава уже есть правило позиции, поэтому один `get-link-attributes` на связь
  читает и «Позицию», и «Примечание»;
- состав детей не раскрывается (`relationDepth`): +0 вызовов связей состава и узлов третьего уровня.
Итого порядок: N + D + ⌈D/100⌉ вызовов. Документы документов не запрашиваются (правило `from` их не включает).

### Что попадает в PdmNode и Usage

- **Обозначение и наименование** — обычные атрибуты `AttributeKey.Designation` и `AttributeKey.Name` в `PdmNode.attributes`,
  запрашиваются через `query.attributes` (чтение `readAttribute`: ключевой атрибут вида берётся из `product`).
  Пустое значение → `AttributeValue.Empty`, не запрошено → ключа нет.
- **Ключ узла** (`PdmNode.key`) — заполняется всегда, если у вида есть ключевой атрибут.
- **Список атрибутов объекта** (`get-info-about-version-mode-3`) запрашивается только если `includeUnmapped` или среди
  запрошенных есть неключевой атрибут вида (`AttributeRequest.needsObjectAttributes()`). Запрос только обозначения
  у вида с ключом «Обозначение» — 0 вызовов; запрос наименования — 1 вызов на объект. Запрос «Обозначения изделия» —
  1 вызов на стандартное и прочее изделие (не ключевой атрибут); у остальных видов правила нет, вызова нет.
- **Version и State** — ВСЕГДА из свойств объекта, источник задаёт блок `properties` (по умолчанию `VERSION` и
  `STATE`). Значение — трим; непустое → `Field.Value(строка)`, пустое или отсутствующее → `Field.Missing`
  (версия у объекта бывает не задана, состояние ожидается всегда, но отсутствие не считается ошибкой загрузки).

### Состояния значений и доступ к ним

`Field<T>` (позиция, единица, версия, состояние) — sealed-типы с тремя состояниями:
`Value` (значение есть), `Missing` (в PDM пусто), `NotLoaded` (не запрашивалось). `null` в API нет. Доступ —
`orElse(default)`, значение по умолчанию вызывающий указывает явно: `node.version.orElse("—")`.
`Missing` даёт `default`, `NotLoaded` бросает `IllegalStateException`: обращение к незапрошенному значению —
ошибка вызывающего кода, а не пустая ячейка. Адаптер `Field.NotLoaded` не создаёт (позиция, единица, версия и
состояние всегда `Value`/`Missing` — незапрошенными они не бывают), оно остаётся значением по умолчанию у вручную
собранных `PdmNode` в тестах.

Значения атрибутов (`PdmNode.attributes`, `Usage.attributes`) читаются так же: `attributes[ключ].orElse("—")`.
Это функция на `AttributeValue?`: `Text` даёт текст, `Number` — число строкой, `Empty` («запрошено, значения нет»)
и отсутствие ключа («не запрашивалось») дают `default`.

Количество читается так же: `usage.quantity.orElse("—")`. `Exact` даёт число строкой (`1.0`), `Range` — `min–max`
(`4.0–6.0`), `Missing` — `default`, `NotLoaded` бросает `IllegalStateException`. Для вычислений (суммирование по
дереву) вызывающий код разбирает `Quantity` сам и выбирает границу диапазона по смыслу отчёта.
Текст для пустых значений (прочерк, «отсутствует», пустая строка) выбирает отчёт, модель его не хранит.

- **Связанный узел** (`Usage.target`) — поле переименовано из `Usage.child`. Для `FORWARD` это потомок, для `INVERSE` — сосед
  по связи (см. «Правила связей»). `Usage` — `CompositionUsage`, `DocumentUsage`, `TechnologicalPartUsage`, `MadeFromUsage`, `UnmappedUsage`.
  `TechnologicalPartUsage` в спецификацию не попадает.
- **Позиция** (`CompositionUsage.position`) — выбирается по правилам ключа `position` в `mapping.link.attributes` (`select(relation, parentKind, childKind)`).
  Если есть правило, ищется атрибут связи с именем из правила в ответе `/get-link-attributes`. Значение преобразуется
  в `Int`: успешно → `Field.Value(число)`, нет → `Field.Missing`.
- **Атрибуты связи** (`Usage.attributes`) — только ключи из `query.linkAttributes`. Для каждого ключа ищется список
  правил в `mapping.link.attributes[ключ]` и выбирается по специфичности селектора (как для позиции).
  Если есть правило и атрибут найден в `/get-link-attributes` → `AttributeValue.Text(значение)`, иначе `Empty`.
- **Количество** (`Usage.quantity`) — поля связи из `link.fields.quantityMin` и `quantityMax`, по правилу (а) выше.
  **Единица** (`Usage.unit`) — поле из `link.fields.unit` (по умолчанию `unit`).
  Валидация конфига: `quantityMin`/`quantityMax` должны быть числовыми полями, `unit` — текстовым.
- **Атрибуты узла** (`PdmNode.attributes`) — только те ключи, что перечислены в `query.attributes`. Каждый ключ
  обязан быть в `mapping.attributes`, источник выбирается из `rule.nameFor(kind)` (с учётом переопределения `byKind`).
  Непустое значение → `AttributeValue.Text`, иначе `Empty`. Не запрошенные ключи отсутствуют.
- `query.includeUnmappedAttributes = true` — остальные непустые атрибуты объекта добавляются как
  `AttributeKey.Unmapped(name)`. Исключаются имена правил вида (`mapping.attributes.*.nameFor(kind)`, с учётом
  `byKind`) и ключевой атрибут вида; сравнение строгое, `==`.

### Откуда что берётся

| Данные | Источник в API | Настройка в конфиге | Куда пишется |
|---|---|---|---|
| Вид объекта | свойство `type` (`get-prop-objects`) | `kinds` | `PdmNode.kind` |
| Версия, состояние | свойства `version`, `state` | `properties` | `PdmNode.version`, `state` |
| Обозначение, наименование | ключевой атрибут типа (`product`) или атрибут объекта | `attributes.designation`, `attributes.name` (только если запрошены) | `PdmNode.attributes`; ключ узла — `PdmNode.key` |
| Обозначение изделия (стандартные и прочие изделия) | атрибут объекта «Обозначение изделия» | `attributes.productDesignation` (только если запрошено; у других видов правила нет) | `PdmNode.attributes` |
| Атрибуты узла | атрибуты объекта (`get-info-about-version-mode-3`) | `attributes` (+ `byKind`) | `PdmNode.attributes` |
| Количество | поля связи `minQuantity`, `maxQuantity` | `link.fields.quantityMin`, `quantityMax` | `Usage.quantity` |
| Единица | поле связи `unit` | `link.fields.unit` | `Usage.unit` |
| Позиция | атрибут связи (`get-link-attributes`) | `link.attributes.position` | `CompositionUsage.position` |
| Прочие атрибуты связи | атрибуты связи (`get-link-attributes`) | `link.attributes.<ключ>` | `Usage.attributes` |
| Тип связи | `idLinkType` + `get-link-list` (имя `link`) | `relations` (`as`, `direction`, `from`) | `Usage.relation` |
| Связанный узел | `idChild` ответа `get-linked-objects-for-objects` | — | `Usage.target` (для `INVERSE` — сосед, не потомок) |

Правило: всё, что приходит вместе с объектом или списком связей (свойства и поля связи), не требует
дополнительных запросов. Атрибуты узла и связи — отдельные запросы, поэтому они настраиваются правилами и
запрашиваются только по необходимости. Всё перечисленное задаётся в `loodsman-mapping.yaml`.

### Env-переменная

**`LOODSMAN_MAPPING_FILE`** (опциональная): путь к файлу конфига маппинга. Если не задана, используется
`classpath:/loodsman-mapping.yaml`. Читается `MappingConfigLoader`, его же использует `report-server`.

## Метаданные: снимок, MetadataCache, обновление

- **Снимок.** `MetadataLoader.load(api)` строит `MetadataSnapshot(linkTypes, objectTypes, attributeNames, version, typeLinks)`.
  Сначала читается версия кэша метаданных `GET /api/v4/MetaData/cache/version` (ответ — голое число int64), затем параллельно
  `get-type-list`, `get-link-list`, `GET /api/v4/MetaData/get-attribute-list-2?mode=0` (`attributeList(mode)`) и
  `GET /api/v4/MetaDataCustom/types-links` (`typesLinks()`). Ошибка любого запроса отменяет остальные. `attributeNames` —
  имена атрибутов как есть, без пустых и `null`.
  - Связи в снимок попадают **все**, фильтра `type == 0` больше нет. Горизонтальные связи (`type=1`: «Взаимозаменяемые»,
    «Чек-лист», «Пакет», «Результат проверки», «Преобразовано», «Удостоверяющий лист») помечены `LinkTypeInfo.horizontal`.
    Имена связей на сервере уникальны.
  - `types-links`: строки `typeId1`, `typeId2`, `idLinkType`, `direction`. `direction`: `1` — прямое, `-1` — только обратное,
    `0` — оба. `MetadataSnapshot.linkDirections(тип, id связи)` возвращает набор направлений для типа объекта.
    Допустимость направления проверяет `MappingConfigValidator` (см. «Структура YAML»).
  - Версия читается **до** данных: если метаданные изменятся во время загрузки, сохранённая версия окажется старой,
    и следующая проверка повторно обновит снимок.
- **Ключевой атрибут типа.** Это `attrName` из `get-type-list`, обрезанный по краям; пустое значение → `null`
  (`ObjectTypeInfo.keyAttribute`). Подтверждено на сервере 2026-10-22: 232 из 266 типов — «Обозначение»; исключения:
  Наименование (14), Обозначение оснастки (9), GID (7), K3Dkey (4); все 8 видов из конфига — «Обозначение».
  Значение ключа физически хранится в свойстве `product` объекта, в списке его атрибутов (`get-info-about-version-mode-3`)
  его нет. Поиск: `MetadataSnapshot.keyAttributeOf(typeName)` — строгое сравнение имени типа (`==`, как у `linkTypeId`);
  `null`, если тип неизвестен или ключ не задан.
- **`MetadataCache`** (`report-loodsman`) — `MetadataSource` с `AtomicReference<MetadataSnapshot?>`. Вторичный конструктор
  `MetadataCache(api, validate)` требует `validate` явно, значения по умолчанию нет:
  - `initialize()` — первая загрузка на старте `report-server`. Ошибка загрузки или непрошедшая `validate` проверка
    пробрасывается, сервер не стартует (fail-fast).
  - `snapshot()` — без обращения к Loodsman. Кидает `IllegalStateException`, если кэш не инициализирован.
  - `refresh(force = false): RefreshResult` — вызовы сериализованы `Mutex`. Сначала читается версия: если она совпала с текущей
    и `force = false`, результат `Unchanged(version)` без загрузки. Иначе загружается снимок и проверяется `validate`;
    пустой список ошибок — снимок подменяется целиком (`Updated(oldVersion, newVersion)`). Ошибка любого шага
    (включая запрос версии и `validate`) — результат `Failed(cause)`: старый снимок остаётся, ошибка пишется в лог.
    `CancellationException` не перехватывается.
- **Эндпоинт** `POST /admin/metadata/refresh?force=true|false` (по умолчанию `false`) в `report-server`:
  - `200 {"status":"updated"|"unchanged","version":N}`;
  - при `Failed` — `502 {"error":"<причина>"}`, старый снимок остаётся;
  - каждый вызов логируется на INFO с результатом, без учётных данных.
  - Аутентификации нет: решение владельца. Эндпоинт доступен всем, кто достучится до `report-server`.
- **Клиент** `LoodsmanProductStructureClient` получает `MetadataSource` параметром без значения по умолчанию. Снимок
  берётся один раз на `loadTree`: смена снимка в кэше во время обхода на уже начатый обход не влияет.
- **Длительность (живые замеры, 2026-10-22):**
  - старт (`initialize()`, полная загрузка) — ~37 с;
  - `get-type-list` — до ~50 с в неудачный момент; таймаут запроса `TYPE_LIST_REQUEST_TIMEOUT_MS` в `MetaDataApi` = 180 с
    (при 60 с второй подряд `force`-refresh падал по клиентскому таймауту);
  - `force`-refresh (полная загрузка) — ~52 с;
  - обычный `refresh` при той же версии — ~1 с (только `cache/version`).
- **Версия метаданных (живая проверка, 2026-10-23):** `cache/version` растёт при изменении метаданных. После правки метаданных
  в тестовой базе версия изменилась с 54990 на 54991, при этом `get-attribute-list-2` вернул 487 атрибутов вместо 486
  (типы и связи по числу и `attrName` не менялись). Это один замер: связь версии с правками типов и связей не
  проверялась, поэтому `force=true` остаётся запасным путём обновления.
  - `MetadataLoader` пишет на INFO время каждого запроса (`version`, `types`, `links`, `attributes`, мс) и итог:
    время загрузки и число элементов (`objectTypes`, `linkTypes`, `attributeNames`).
- **Требование к контейнеру:** `healthcheck` / `startupProbe` должны ждать не меньше **90 с** после старта, иначе
  оркестратор убьёт процесс до завершения первой загрузки метаданных.

## Конфигурация и запуск

### report-server

Обязательные env-переменные (без них сервер не стартует — fail fast с понятным
сообщением, см. `AppConfig`): `LOODSMAN_BASE_URL`, `LOODSMAN_DB_NAME`,
`LOODSMAN_USERNAME`, `LOODSMAN_PASSWORD`. Опциональные: `REPORT_OUTPUT_DIR`
(default `/data/reports`), `SERVER_PORT` (default `8080`), `LOODSMAN_MAPPING_FILE` (см. выше).

При остановке сервера вызывается `logout()` у `LoodsmanApi`. Ошибка logout только логируется и не мешает остановке;
`CancellationException` пробрасывается.

### HTTP-статусы маршрута спецификации

`POST /specifications/{versionId}` (параметр `customerRepresentative`, по умолчанию `true`). Маппинг ошибок — `StatusPages`
в `Routing.kt`:

| Ситуация | HTTP |
|---|---|
| `versionId` не целое число | 400 |
| `LoodsmanApiException`: Loodsman недоступен, объект не найден, сессия не восстановилась, ошибка метаданных или конфига маппинга | 502 |
| `SpecificationDataException`: нет обозначения у корня, диапазон или отсутствие количества у позиции, и т.п. | 502 |
| `IOException` сверх `LoodsmanApiException` | 502 |
| Прочее | 500, тело `internal error` |

Причина 502 из `SpecificationDataException` — данные изделия в PDM, а не баг сервера: до миграции такие ошибки тоже давали 502.

## Тесты

- `loodsman-client` — тесты на Ktor `MockEngine` (`ktor-client-mock`): проверяется поведение без сети. `LoodsmanApiTest`
  покрывает группы эндпоинтов, сессию и повтор при 401/419.
- `report-loodsman`:
  - `LoodsmanLoadNodeTest` — восемь видов на синтетических объектах с реальным YAML, пустой `product`;
  - `LoodsmanProductStructureClientTest` — `loadTree` и `loadNode` дают одинаковые `key`, `designation`, `name`;
  - `SpecificationPipelineTest` — регрессионный тест конвейера: структура → спецификация. Фиксирует правила прежней сборки
    (материал и столбец «Наименование», ё, стандартные и прочие изделия, документы). Если правило меняется намеренно,
    тест обновляется вместе с правилом.

## См. также

- `loodsman-client/src/main/kotlin/io/github/konstantinkhan/loodsman/` — клиент (`LoodsmanApi`, группы `AuthApi` / `ObjectInfoApi` /
  `MetaDataApi`, `SessionClient`, `SessionHolder`, DTO)
- `report-loodsman/src/main/kotlin/dev/reportgenerator/loodsman/` — `LoodsmanProductStructureClient.kt` и вспомогательные
  классы обхода (`NodeLoader`, `LinkLoader`, `LinkAttributeLoader`, `TreeAssembler`, `AttributeRequests`, `Limiter`,
  `StructureModels.kt`), `mapping/` (`KindMapper`, `RelationMapper`, `Registries`, `MappingConfig`)
- `reports/specification/src/main/kotlin/dev/reportgenerator/reports/specification/` — `SpecificationQuery.kt`,
  `ProductNodeSpecification.kt` (правила отчёта)
- [development-status.md](development-status.md) — статус интеграции по коммитам
- [design-decisions.md](design-decisions.md#решения-ветки-refactorloodsman-api) — почему слои устроены так
- [known-gaps.md](known-gaps.md#loodsman-интеграция-ветка-refactorloodsman-api) — что осталось недоделанным
- [specification-table-format.md](specification-table-format.md) — как колонки
  таблицы спецификации устроены на уровне рендеринга
