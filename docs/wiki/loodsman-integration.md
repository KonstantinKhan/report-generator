# Loodsman API интеграция

`report-loodsman` (`dev.reportgenerator.loodsman`) — реализация интерфейса `PdmClient`
поверх Loodsman PDM API v4. Содержит два клиента: `LoodsmanPdmClient` (java.net.http)
и `LoodsmanKtorPdmClient` (Ktor). `report-server` (`dev.reportgenerator.server`) —
Ktor REST-обёртка вокруг них: `POST /specifications/{versionId}` тянет спецификацию из
Loodsman через `PdmClient` и рендерит PDF.

Этот документ — не общее описание модуля, а список конкретных грабель API, на которые
уже наступили. Каждый пункт стоил минимум одной сессии отладки.

## Авторизация

- Заголовок `web-loodsman-session`, **не** `Authorization`.
- Обязателен cookie jar: `HttpClient` с `CookieManager(CookiePolicy.ACCEPT_ALL)`.
  Без него сессия не держится между запросами.
- `POST /api/v4/Auth/login` → `{ sessionId }`.
- При 401/419 — сессия истекла/невалидна: сбросить кэш, повторить запрос один раз
  (`LoodsmanPdmClient.send()`, параметр `retried`).

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
`name` = `product`. У Материала `designation == null`, `name` = `product`.
Тип «Программные изделия и базы данных» (`SOFTWARE`) не замаплен, строк не будет.

Реализовано в `SpecificationAssembly.isDesignationKeyed()` / `hasProductDesignationAttr()` — единая точка правила,
используется и для корневого документа (`LoodsmanPdmClient.fetchSpecification`,
по `prop.type`), и для каждого дочернего объекта (по `childProps.type`). **Обе точки
обязательны** — баг уже был в том, что правило реализовали для детей, но забыли про
корневой документ, и любой корневой документ не-Деталь/СЕ падал с той же ошибкой.

Маппинг типа Loodsman -> `ItemKind` (`mapItemKind`, internal в `SpecificationAssembly`): Сборочный чертеж -> DOCUMENTATION, Комплекс -> COMPLEX,
Сборочная единица -> ASSEMBLY, Деталь -> PART, Стандартное изделие -> STANDARD, Прочее изделие -> OTHER,
Материал по КД -> MATERIAL, Комплект -> SET. Дети других типов молча пропускаются.

Связи: всё, кроме Документации, читается по связи «Состоит из ...»; Документация (Сборочный чертеж) по связи «Документы»
(`get-link-list`, имя связи не проверено на живом стенде; нет связи — группа просто пуста). Количество у Документации не
нужно (в ячейке пусто, отсутствие `minQuantity`/`maxQuantity` не ошибка).
Атрибут «Обозначение изделия» (имя подтверждено; нет атрибута в ответе = не заполнен, сервер пустые не отдаёт) запрашивается через `get-info-about-version-mode-3` только для
Стандартного/Прочего изделия.

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

**Правильный источник типа объекта** — `childProps.type` из `get-prop-objects` для
конкретного `idChild`, уже и так запрашивается в цикле по детям. Отдельный
`get-type-list` для этого не нужен вообще.

## Прочее

- Batch-эндпоинты атрибутов (`/objects/by-ids/attributes/...`) возвращают
  `isSuccess=false` — не работают, не использовать. Только single-object запросы
  по одному `idVersion`/`idChild` за раз.
- Количество — из `minQuantity`/`maxQuantity` (Double) в `LinkedObjectDto`, не из
  текстового атрибута "Количество". Если `min == max` — берём это значение, иначе
  `min` (или бросаем `LoodsmanApiException`, если оба `null`).
- `get-info-about-version-mode-3` отдаёт атрибуты списком `[{name, value}]`, не
  keyed map — собирать через `associateBy { it.name }` самим.

## Конфиг маппинга и ProductStructureClient

`LoodsmanProductStructureClient` — адаптер, реализующий интерфейс `ProductStructureClient` из `report-domain`
(`loadStructure(StructureQuery): ProductNode`). Возвращает дерево состава изделия в доменной модели
(`ProductNode` + `Usage`), не зависящей от словаря Loodsman. Соответствие названий типов, связей и атрибутов
PDM типам модели задаётся конфигом маппинга, а не кодом. `report-server` пока использует старый `PdmClient`;
новый адаптер подключён только в `Main.kt` модуля `report-loodsman`.

### Структура YAML конфига маппинга

Ресурс `loodsman-mapping.yaml` определяет отображение типов, связей, идентификаторов и атрибутов:

```yaml
kinds:                      # имя типа в PDM -> id вида (без учёта регистра и лишних пробелов)
  Деталь: PART
  Сборочная единица: ASSEMBLY
  Сборочный чертеж: DOCUMENT
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

identity:
  designation:
    type: prop
    field: PRODUCT
  name:
    type: attribute
    name: Наименование
  byKind:                   # переопределение для отдельных видов (STANDARD, OTHER)
    STANDARD:
      designation: { type: attribute, name: Обозначение изделия }
      name: { type: prop, field: PRODUCT }

position:
  type: attribute
  name: Позиция

attributes:                 # id ключа атрибута модели -> источник
  version: { type: prop, field: VERSION }
  state: { type: prop, field: STATE }
  mass: { type: attribute, name: Масса }
  material: { type: attribute, name: Материал }

onUnmapped:
  kind: KEEP
  relation: SKIP
```

(В ресурсе `byKind` задан и для `STANDARD`, и для `OTHER`, поля записаны в блочном стиле.)

**`type: prop`** — значение из свойств объекта (`PRODUCT`, `VERSION`, `STATE`, `TYPE` из `/get-prop-objects`).
**`type: attribute`** — значение из атрибутов объекта (ищется по имени через `/get-info-about-version-mode-3`).

**`identity.byKind`** — переопределение источников обозначения и наименования для отдельных видов изделий:
у стандартных и прочих изделий обозначение лежит в атрибуте «Обозначение изделия», а `product` содержит наименование.

### Реестры (Registries)

`Registries` содержит словари известных типов, связей и ключей атрибутов:

```kotlin
Registries.itemKinds  // ASSEMBLY, PART, STANDARD, OTHER, MATERIAL, DOCUMENT, COMPLEX, KIT
Registries.relations  // COMPOSITION, DOCUMENT
Registries.attributeKeys  // version, state, mass, material
```

`MappingConfig.validate()` проверяет, что все ключи в конфиге существуют в реестрах.

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

Сейчас политика применяется только к типам объектов (`onUnmapped.kind`). `onUnmapped.relation` адаптером не
используется: при загрузке запрашиваются только отношения из `StructureQuery.relations`, остальные связи
объекта игнорируются.

### Загрузка конфига

```kotlin
val config = MappingConfigLoader.load()  // LOODSMAN_MAPPING_FILE env или classpath:/loodsman-mapping.yaml
val config = MappingConfigLoader.parse(yamlText)  // из строки
```

Разбор YAML использует полиморфизм по свойству `type` (Kaml `PolymorphismStyle.Property`).

### Алгоритм загрузки структуры

`LoodsmanProductStructureClient.loadStructure(query: StructureQuery)`:

1. **Разрешение связей.** Для каждого отношения из `query.relations` ищется id типа связи в `get-link-list`
   (только `type == 0`, имя сравнивается без учёта регистра и лишних пробелов). Каждая связь из
   `requiredRelations` конфига обязана быть в метаданных, иначе `LoodsmanApiException`. Отношение вне конфига
   или без связи в PDM — тоже `LoodsmanApiException`.

2. **Обход по уровням.** `depth = 0` — только корень, запросов связей нет. `depth = N` — связи разворачиваются
   у узлов уровней `0..N-1`, дети уровня `N` загружаются (свойства и атрибуты), но их `usages` пусты.
   На каждом уровне:
   - свойства всех узлов уровня запрашиваются одним `get-prop-objects` (чанками по 100);
   - тип узла определяется по `type` из свойств (`KindMapper`), а не по обозначению;
   - связи (`get-linked-objects-for-objects`) и атрибуты (`get-info-about-version-mode-3`, один запрос на узел)
     запрашиваются параллельно через `Semaphore(concurrency)`;
   - следующий уровень — дети, которых ещё нет среди загруженных узлов. Поэтому обход конечен даже при цикле.

3. **Кэш.** Свойства, атрибуты и связи хранятся по `idVersion`. Узел разворачивается один раз на минимальной
   глубине, повторные вхождения в дерево используют те же данные.

4. **Сборка дерева** идёт из кэша рекурсией с остаточной глубиной. Цикл определяется по текущему пути →
   `LoodsmanApiException` с путём. Дети, чей тип пропущен политикой `SKIP`, в `usages` не попадают.

Запросов остаётся по два на узел (связи и атрибуты): `get-info-about-version-mode-3` принимает один id. Батчится
только `get-prop-objects`.

### Что попадает в ProductNode и Usage

- **Обозначение и наименование** — из источников `identity` (с переопределением `byKind`). Пустое значение →
  `Designation.Missing` / `Name.Missing`.

### Состояния значений и доступ к ним

`Designation`, `Name` и `Field<T>` (позиция, единица) — sealed-типы с тремя состояниями:
`Value` (значение есть), `Missing` (в PDM пусто), `NotLoaded` (не запрашивалось). `null` в API нет. Доступ —
`orElse(default)`, значение по умолчанию вызывающий указывает явно: `child.name.orElse("—")`.
`Missing` даёт `default`, `NotLoaded` бросает `IllegalStateException`: обращение к незапрошенному значению —
ошибка вызывающего кода, а не пустая ячейка. Адаптер `NotLoaded` сейчас не создаёт, всегда `Value` или `Missing`.
Текст для пустых значений (прочерк, «отсутствует», пустая строка) выбирает отчёт, модель его не хранит.
- **Позиция** (`Usage.position`) — значение из `mapping.position`, прочитанное у **дочернего** объекта.
  Не число или нет значения → `Field.Missing`. Для её чтения дочерним узлам загружаются атрибуты.
- **Количество** (`Usage.quantity`) — из `minQuantity`/`maxQuantity` связи: оба пусты → `Quantity.Missing`;
  одно значение или `min == max` → `Quantity.Exact`; иначе `Quantity.Range`. **Единица** — `unit` связи.
- **Атрибуты узла** — только те ключи, что перечислены в `query.attributes`. Каждый ключ обязан быть в
  `mapping.attributes`, источник может быть `prop` (напр. версия и состояние) или `attribute`. Непустое значение →
  `AttributeValue.Text`, иначе `AttributeValue.Empty`. Не запрошенные ключи в `attributes` отсутствуют.
- `query.includeUnmappedAttributes = true` — остальные непустые атрибуты объекта добавляются как
  `AttributeKey.Unmapped(name)`.

### Env-переменная

**`LOODSMAN_MAPPING_FILE`** (опциональная): путь к файлу конфига маппинга. Если не задана, используется
`classpath:/loodsman-mapping.yaml`. Читается `MappingConfigLoader`; `report-server` пока этот адаптер не создаёт.

## Конфигурация (report-server)

Обязательные env-переменные (без них сервер не стартует — fail fast с понятным
сообщением, см. `AppConfig`): `LOODSMAN_BASE_URL`, `LOODSMAN_DB_NAME`,
`LOODSMAN_USERNAME`, `LOODSMAN_PASSWORD`. Опциональные: `REPORT_OUTPUT_DIR`
(default `/data/reports`), `SERVER_PORT` (default `8080`).

`LoodsmanApiException` от клиента маппится в HTTP 502 (`StatusPages` в
`Routing.kt`) — апстрим-ошибка Loodsman отличается от внутренней ошибки сервера.

## См. также

- `report-loodsman/src/main/kotlin/dev/reportgenerator/loodsman/` — сам клиент
  (`LoodsmanPdmClient.kt`, `SpecificationAssembly.kt`)
- [development-status.md](development-status.md) — статус интеграции по коммитам
- [specification-table-format.md](specification-table-format.md) — как колонки
  таблицы спецификации устроены на уровне рендеринга
