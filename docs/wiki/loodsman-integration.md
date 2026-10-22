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
| `objectInfo` | `ObjectInfoApi` | `/api/v4/ObjectInfo` | `propObjects(versionIds)`, `infoAboutVersion(versionId)`, `linkedObjects(objectIds, linkTypeIds, inverse = false)`, `linkAttributes(linkId)` |
| `metaData` | `MetaDataApi` | `/api/v4/MetaData` | `typeList()`, `linkList()` |

Итого 8 эндпоинтов и `logout()`. Одна группа — один файл. Конструкторы групп `internal`: создать группу снаружи нельзя.
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

Поле `product` в свойствах — это generic-слот "ключевой атрибут", и что он означает,
**зависит от типа объекта**:

| Тип объекта | `product` содержит | Отдельный атрибут `Наименование` есть? |
|---|---|---|
| Сборочный чертеж | Обозначение | Да |
| Комплекс | Обозначение | Да |
| Сборочная единица | Обозначение | Да, опционально (если нет — fallback на `product`) |
| Деталь | Обозначение | Да, опционально (если нет — fallback на `product`) |
| Комплект | Обозначение | Да |
| Стандартное изделие | **Наименование** | Нет; Обозначение = отдельный атрибут «Обозначение изделия» |
| Прочее изделие | **Наименование** | Нет; Обозначение = отдельный атрибут «Обозначение изделия» |
| Материал по КД | **Наименование** | Нет; Обозначение не заполняется |

Бизнес-правило колонок: у типов с `product` = Обозначение это `designation`, Наименование берётся из атрибута (fallback на
`product`). У Стандартного/Прочего изделия `designation` = атрибут «Обозначение изделия» (нет атрибута — пусто),
`name` = `product`. Программные изделия и базы данных (`SOFTWARE`) не замаплены, строк не будет.

Реализовано конфигом, а не кодом: блок `identity` (+ `byKind`) в `loodsman-mapping.yaml` задаёт источник обозначения и
наименования для каждого вида. Корень документа и дочерние объекты загружаются одним и тем же кодом, поэтому правило
одинаково для всех. Старый баг был в том, что правило реализовали для детей, а для корня забыли.

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

Маппинг названия вида Loodsman → `PdmNodeKind` задаёт секция `kinds` в YAML. Сравнение названий — правило (в) ниже.
Затем `PdmNodeKind` → `ItemKind` отчёта выполняет `toItemKind()` в `ProductNodeSpecification.kt`:

| Тип Loodsman | `PdmNodeKind` | `ItemKind` (спецификация) |
|---|---|---|
| Сборочный чертеж | `AssemblyDrawing` (id `ASSEMBLY_DRAWING`) | DOCUMENTATION |
| Комплекс | `Complex` | COMPLEX |
| Сборочная единица | `Assembly` | ASSEMBLY |
| Деталь | `Part` | PART |
| Стандартное изделие | `Standard` | STANDARD |
| Прочее изделие | `Other` | OTHER |
| Материал по КД | `Material` | MATERIAL |
| Комплект | `Kit` | SET |

Связи: `relations` в YAML — «Состоит из ...» (`COMPOSITION`) и «Документы» (`DOCUMENT`). Состав идёт по `COMPOSITION`,
документация (сборочный чертеж) — по `DOCUMENT` (имя связи не проверено на живом стенде; нет связи — группа пуста).
Количество у документации не нужно.

Атрибут «Обозначение изделия» (имя подтверждено; нет атрибута в ответе = не заполнен, сервер пустые не отдаёт)
запрашивается через `get-info-about-version-mode-3` только там, где `byKind` его указывает (Стандартное и Прочее изделие).

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
«Количество». Правило в `LoodsmanProductStructureClient.quantityOf`:

- оба `null` → `Quantity.Missing`;
- ровно одно `null` → `LoodsmanApiException` (половина диапазона — ошибка данных Loodsman);
- `min == max` → `Quantity.Exact`;
- иначе `Quantity.Range`.

В спецификации `Exact` даёт число, `Range` и `Missing` — `SpecificationDataException` (диапазон в спецификации не
выводится). У документации количество не читается: в спецификации 0.0 и пустая ячейка.

**(б) Материал.** У `ProductKind.Material` в домене `designation` берётся из `product` (свойство `PRODUCT`), `name` —
из атрибута «Наименование» (`byKind` для материала нет). В спецификации обозначение **выводится в столбец «Наименование»**,
столбец обозначения пуст. Правило отчёта живёт в конвертере `ProductNode.toItem()`, а не в маппинге: маппинг отдаёт
доменную модель как есть. Без обозначения у материала — `SpecificationDataException`. Единица измерения только у
материалов — [specification-table-format.md](specification-table-format.md).

**(в) Ё.** `KindMapper` при сравнении названий видов из YAML и из PDM приводит строку к нижнему регистру, схлопывает
пробелы и сводит `ё` к `е`. Это сделано, потому что в данных Loodsman одно и то же слово пишется с `ё` и с `е`.
`RelationMapper` такого сведения не делает (см. [known-gaps.md](known-gaps.md)).

**(г) Неизвестные виды.** `ProductKind.Unmapped` (политика `onUnmapped.kind: KEEP` сохраняет такой вид в домене) и
`UnmappedUsage` спецификация пропускает, в отчёт не попадают. Если неизвестен вид самого корня, загрузка падает
`LoodsmanApiException` (`Root object ... has unmapped type`).

**Корень документа.** Без обозначения — `SpecificationDataException`. Без наименования вместо него выводится
обозначение.

## Конфиг маппинга и ProductStructureClient

`LoodsmanProductStructureClient(api, mapping, concurrency)` — адаптер, реализующий интерфейс `PdmNodeStructureClient`
из `report-domain` (`loadStructure(StructureQuery): ProductNode`). Возвращает дерево состава изделия в доменной модели
(`PdmNode` + `Usage`), не зависящей от словаря Loodsman. Соответствие названий типов, связей и атрибутов PDM
моделям задаётся конфигом маппинга, а не кодом. `report-server` создаёт адаптер с конфигом по умолчанию
(`MappingConfigLoader.load()`).

### Структура YAML конфига маппинга

Ресурс `report-loodsman/src/main/resources/loodsman-mapping.yaml` (актуальная версия, полный файл):

```yaml
kinds:                      # имя типа в PDM -> id вида (без учёта регистра и лишних пробелов)
  Деталь: PART
  Сборочная единица: ASSEMBLY
  Сборочный чертеж: ASSEMBLY_DRAWING
  Комплекс: COMPLEX
  Стандартное изделие: STANDARD
  Прочее изделие: OTHER
  Материал по КД: MATERIAL
  Комплект: KIT

relations:                  # имя связи в PDM -> id отношения
  Состоит из ...: COMPOSITION
  Документы: DOCUMENT

requiredRelations:          # должны существовать в метаданных PDM, иначе LoodsmanApiException
  - COMPOSITION
  - DOCUMENT

identity:
  designation:
    type: prop
    field: PRODUCT
  name:
    type: attribute
    name: Наименование
  byKind:                   # переопределение для отдельных видов (STANDARD, OTHER)
    STANDARD:
      designation:
        type: attribute
        name: Обозначение изделия
      name:
        type: prop
        field: PRODUCT
    OTHER:
      designation:
        type: attribute
        name: Обозначение изделия
      name:
        type: prop
        field: PRODUCT

attributes:                 # атрибуты узла: id ключа модели -> имя атрибута в PDM
  mass:
    name: Масса
  companyName:              # атрибут есть только у сборочных единиц и деталей: общего name нет
    byKind:
      ASSEMBLY: Наименование предприятия
      PART: Наименование предприятия

properties:                 # поля узла из свойств объекта (get-prop-objects): PRODUCT, VERSION, STATE, TYPE
  version: VERSION
  state: STATE

link:
  fields:                   # поля связи из списка связей (get-linked-objects-for-objects)
    quantityMin: MIN_QUANTITY   # числовые: MIN_QUANTITY, MAX_QUANTITY
    quantityMax: MAX_QUANTITY
    unit: UNIT                  # текстовое: UNIT
  attributes:               # атрибуты связей модели: id ключа модели -> список правил
    position:               # встроенный ключ: всегда загружается, попадает в Usage.position
      - relation: COMPOSITION
        name: Позиция
    note:
      - relation: COMPOSITION
        name: Примечание

onUnmapped:
  kind: KEEP
  relation: SKIP
```

**`identity`** — источники обозначения и наименования объекта (`prop` или `attribute`), с переопределением
по типу (`byKind`). У стандартных и прочих изделий обозначение в атрибуте «Обозначение изделия»,
наименование в свойстве `product`.

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

Ключ `position` встроенный: он загружается всегда и проецируется в типизированное поле `Usage.position`
(в `Usage.attributes` не дублируется), поэтому в `StructureQuery.linkAttributes` его указывать нельзя —
`LoodsmanApiException`. Остальные ключи (`note`) загружаются только по запросу. Правило выбирается по специфичности:
оба селектора (3) > только `child` (2) > только `parent` (1) > без селекторов (0). Пересечение одинаково специфичных
правил — ошибка валидации.

### Реестры (Registries)

`Registries` (`mapping/Registries.kt`) содержит словари известных видов, связей и ключей атрибутов:

```kotlin
Registries.itemKinds          // ASSEMBLY, PART, STANDARD, OTHER, MATERIAL, ASSEMBLY_DRAWING, COMPLEX, KIT
Registries.relations          // COMPOSITION, DOCUMENT
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

- **KEEP** — неизвестный тип/связь сохраняется как `ProductKind.Unmapped` / `Relation.Unmapped`.
- **SKIP** — объект неизвестного типа пропускается (ребёнок не появляется в `usages`). Если неизвестен тип
  самого корня — `LoodsmanApiException`.
- **FAIL** — исключение при неизвестном типе.

Политика `onUnmapped.kind` применяется к видам объектов. `onUnmapped.relation` реализована в `RelationMapper.map`, но
адаптер вызывает только обратное преобразование `pdmNameOf` и запрашивает отношения из `StructureQuery.relations`:
остальные связи объекта игнорируются.

### Загрузка конфига

```kotlin
val config = MappingConfigLoader.load()  // LOODSMAN_MAPPING_FILE env или classpath:/loodsman-mapping.yaml
val config = MappingConfigLoader.parse(yamlText)  // из строки
```

Разбор YAML использует полиморфизм по свойству `type` (Kaml `PolymorphismStyle.Property`).

### Алгоритм загрузки структуры

`LoodsmanProductStructureClient.loadStructure(query: StructureQuery)`:

1. **Разрешение связей и атрибутов узла.** Для каждого отношения из `query.relations` ищется id типа связи в `get-link-list`
   (только `type == 0`, имя сравнивается без учёта регистра и лишних пробелов). Каждая связь из `requiredRelations`
   конфига обязана быть в метаданных, иначе `LoodsmanApiException`. Для каждого атрибута узла из `query.attributes`
   проверяется наличие конфигурированного правила в `mapping.attributes`, иначе `LoodsmanApiException`.

2. **Обход по уровням (фаза 1).** `depth = 0` — только корень, запросов связей нет. `depth = N` — связи разворачиваются
   у узлов уровней `0..N-1`, дети уровня `N` загружаются (свойства и атрибуты), но их `usages` пусты.
   На каждом уровне:
   - свойства всех узлов уровня запрашиваются одним `get-prop-objects` (чанками по 100);
   - тип узла определяется по `type` из свойств (`KindMapper`), а не по обозначению;
   - связи (`get-linked-objects-for-objects`) и узловые атрибуты (`get-info-about-version-mode-3`, один запрос на узел)
     запрашиваются параллельно через `Semaphore(concurrency)` — если нужны;
   - следующий уровень — дети, которых ещё нет среди загруженных узлов. Поэтому обход конечен даже при цикле.

3. **Загрузка атрибутов связей (фаза 2).** ПОСЛЕ полного обхода дерева: если запрошены `query.linkAttributes`
   или есть правило для `position`, для каждой загруженной связи с известными видами обоих концов, у которой правило
   подходит по тройке, делается один запрос `/api/v4/ObjectInfo/get-link-attributes` (параллельно, `Semaphore(concurrency)`).
   Результаты собираются в карту `linkId → атрибуты`.

4. **Кэш.** Свойства, узловые атрибуты и связи хранятся по `idVersion`/`idLink`. Узел разворачивается один раз на
   минимальной глубине, повторные вхождения в дерево используют те же данные. Метаданные связей (`get-link-list`)
   загружаются лениво (`SuspendLazy`) один раз на экземпляр адаптера.

5. **Сборка дерева** идёт из кэша рекурсией с остаточной глубиной, используя загруженные атрибуты связей. Цикл
   определяется по текущему пути → `LoodsmanApiException` с путём. Дети, чей тип пропущен политикой `SKIP`, в
   `usages` не попадают. `ProductNode.version` и `ProductNode.state` всегда заполняются из `prop.version`/`prop.state`.

**Стоимость по API:**
- Свойства: батч `get-prop-objects` (один на уровень);
- Узловые атрибуты: один `get-info-about-version-mode-3` на узел (если нужны);
- Связи: один `get-linked-objects-for-objects` на узел;
- Атрибуты связей: один `get-link-attributes` на связь (если нужны или есть правила позиции).

### Что попадает в ProductNode и Usage

- **Обозначение и наименование** — из источников `identity` (с переопределением `byKind`). Пустое значение →
  `Designation.Missing` / `Name.Missing`.
- **Version и State** — ВСЕГДА из свойств объекта, источник задаёт блок `properties` (по умолчанию `VERSION` и
  `STATE`). Значение — трим; непустое → `Field.Value(строка)`, пустое или отсутствующее → `Field.Missing`
  (версия у объекта бывает не задана, состояние ожидается всегда, но отсутствие не считается ошибкой загрузки).

### Состояния значений и доступ к ним

`Designation`, `Name` и `Field<T>` (позиция, единица, версия, состояние) — sealed-типы с тремя состояниями:
`Value` (значение есть), `Missing` (в PDM пусто), `NotLoaded` (не запрашивалось). `null` в API нет. Доступ —
`orElse(default)`, значение по умолчанию вызывающий указывает явно: `node.version.orElse("—")`.
`Missing` даёт `default`, `NotLoaded` бросает `IllegalStateException`: обращение к незапрошенному значению —
ошибка вызывающего кода, а не пустая ячейка. Адаптер `NotLoaded` не создаёт (поля заполнены всегда), оно
остаётся значением по умолчанию у вручную собранных `PdmNode` в тестах.

Значения атрибутов (`ProductNode.attributes`, `Usage.attributes`) читаются так же: `attributes[ключ].orElse("—")`.
Это функция на `AttributeValue?`: `Text` даёт текст, `Number` — число строкой, `Empty` («запрошено, значения нет»)
и отсутствие ключа («не запрашивалось») дают `default`.

Количество читается так же: `usage.quantity.orElse("—")`. `Exact` даёт число строкой (`1.0`), `Range` — `min–max`
(`4.0–6.0`), `Missing` — `default`, `NotLoaded` бросает `IllegalStateException`. Для вычислений (суммирование по
дереву) вызывающий код разбирает `Quantity` сам и выбирает границу диапазона по смыслу отчёта.
Текст для пустых значений (прочерк, «отсутствует», пустая строка) выбирает отчёт, модель его не хранит.

- **Позиция** (`Usage.position`) — выбирается по правилам ключа `position` в `mapping.link.attributes` (`select(relation, parentKind, childKind)`).
  Если есть правило, ищется атрибут связи с именем из правила в ответе `/get-link-attributes`. Значение преобразуется
  в `Int`: успешно → `Field.Value(число)`, нет → `Field.Missing`.
- **Атрибуты связи** (`Usage.attributes`) — только ключи из `query.linkAttributes`. Для каждого ключа ищется список
  правил в `mapping.link.attributes[ключ]` и выбирается по специфичности селектора (как для позиции).
  Если есть правило и атрибут найден в `/get-link-attributes` → `AttributeValue.Text(значение)`, иначе `Empty`.
- **Количество** (`Usage.quantity`) — поля связи из `link.fields.quantityMin` и `quantityMax`, по правилу (а) выше.
  **Единица** (`Usage.unit`) — поле из `link.fields.unit` (по умолчанию `unit`).
  Валидация конфига: `quantityMin`/`quantityMax` должны быть числовыми полями, `unit` — текстовым.
- **Атрибуты узла** (`ProductNode.attributes`) — только те ключи, что перечислены в `query.attributes`. Каждый ключ
  обязан быть в `mapping.attributes`, источник выбирается из `rule.nameFor(kind)` (с учётом переопределения `byKind`).
  Непустое значение → `AttributeValue.Text`, иначе `Empty`. Не запрошенные ключи отсутствуют.
- `query.includeUnmappedAttributes = true` — остальные непустые атрибуты объекта добавляются как
  `AttributeKey.Unmapped(name)`. Исключаются все известные имена: из `mapping.attributes.*.name` и
  `mapping.attributes.*.byKind.*`, из `mapping.identity.*.{name}` и их `byKind` переопределений.

### Откуда что берётся

| Данные | Источник в API | Настройка в конфиге | Куда пишется |
|---|---|---|---|
| Вид объекта | свойство `type` (`get-prop-objects`) | `kinds` | `ProductNode.kind` |
| Версия, состояние | свойства `version`, `state` | `properties` | `ProductNode.version`, `state` |
| Обозначение, наименование | свойство или атрибут объекта | `identity` (+ `byKind`) | `ProductNode.designation`, `name` |
| Атрибуты узла | атрибуты объекта (`get-info-about-version-mode-3`) | `attributes` (+ `byKind`) | `ProductNode.attributes` |
| Количество | поля связи `minQuantity`, `maxQuantity` | `link.fields.quantityMin`, `quantityMax` | `Usage.quantity` |
| Единица | поле связи `unit` | `link.fields.unit` | `Usage.unit` |
| Позиция | атрибут связи (`get-link-attributes`) | `link.attributes.position` | `Usage.position` |
| Прочие атрибуты связи | атрибуты связи (`get-link-attributes`) | `link.attributes.<ключ>` | `Usage.attributes` |
| Тип связи | `idLinkType` + `get-link-list` | `relations` | `Usage.relation` |

Правило: всё, что приходит вместе с объектом или списком связей (свойства и поля связи), не требует
дополнительных запросов. Атрибуты узла и связи — отдельные запросы, поэтому они настраиваются правилами и
запрашиваются только по необходимости. Всё перечисленное задаётся в `loodsman-mapping.yaml`.

### Env-переменная

**`LOODSMAN_MAPPING_FILE`** (опциональная): путь к файлу конфига маппинга. Если не задана, используется
`classpath:/loodsman-mapping.yaml`. Читается `MappingConfigLoader`, его же использует `report-server`.

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
  - `SpecificationPipelineTest` — регрессионный тест конвейера: структура → спецификация. Фиксирует правила прежней сборки
    (материал и столбец «Наименование», ё, стандартные и прочие изделия, документы). Если правило меняется намеренно,
    тест обновляется вместе с правилом.

## См. также

- `loodsman-client/src/main/kotlin/io/github/konstantinkhan/loodsman/` — клиент (`LoodsmanApi`, группы `AuthApi` / `ObjectInfoApi` /
  `MetaDataApi`, `SessionClient`, `SessionHolder`, DTO)
- `report-loodsman/src/main/kotlin/dev/reportgenerator/loodsman/` — `LoodsmanProductStructureClient.kt`,
  `mapping/` (`KindMapper`, `RelationMapper`, `Registries`, `MappingConfig`)
- `reports/specification/src/main/kotlin/dev/reportgenerator/reports/specification/` — `SpecificationQuery.kt`,
  `ProductNodeSpecification.kt` (правила отчёта)
- [development-status.md](development-status.md) — статус интеграции по коммитам
- [design-decisions.md](design-decisions.md#решения-ветки-refactorloodsman-api) — почему слои устроены так
- [known-gaps.md](known-gaps.md#loodsman-интеграция-ветка-refactorloodsman-api) — что осталось недоделанным
- [specification-table-format.md](specification-table-format.md) — как колонки
  таблицы спецификации устроены на уровне рендеринга
