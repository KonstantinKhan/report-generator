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
  material:
    name: Материал
    byKind:                 # переопределение имени для вида (пример)
      STANDARD: Материал изделия
  companyName:              # атрибут есть только у сборочных единиц и деталей: общего name нет
    byKind:
      ASSEMBLY: Наименование предприятия
      PART: Наименование предприятия

link:
  attributes:               # атрибуты связи: id ключа модели -> список правил
    position:               # встроенный ключ: всегда загружается, попадает в Usage.position
      - relation: COMPOSITION
        name: Позиция
      - relation: COMPOSITION   # более конкретное правило выигрывает (пример)
        child: [STANDARD]
        name: Позиция изделия
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

**`attributes`** — узловые атрибуты модели. Каждый ключ (`mass`, `material`, `companyName`) обязан быть в `Registries.attributeKeys`.
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

`Registries` содержит словари известных типов, связей и ключей атрибутов:

```kotlin
Registries.itemKinds  // ASSEMBLY, PART, STANDARD, OTHER, MATERIAL, DOCUMENT, COMPLEX, KIT
Registries.relations  // COMPOSITION, DOCUMENT
Registries.attributeKeys  // mass, material, companyName
Registries.linkAttributeKeys  // position, note
```

`MappingConfig.validate()` проверяет, что все ключи в конфиге существуют в реестрах, и что правила
правила каждого ключа `link.attributes` (включая `position`) не пересекаются по селекторам одинаковой специфичности.

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
   минимальной глубине, повторные вхождения в дерево используют те же данные.

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
- **Version и State** — ВСЕГДА из свойств объекта (`prop.version` и `prop.state`, trim). Не настраиваются конфигом.
  Непустое значение → `Field.Value(строка)`, иначе `Field.Missing`.

### Состояния значений и доступ к ним

`Designation`, `Name` и `Field<T>` (позиция, единица, версия, состояние) — sealed-типы с тремя состояниями:
`Value` (значение есть), `Missing` (в PDM пусто), `NotLoaded` (не запрашивалось). `null` в API нет. Доступ —
`orElse(default)`, значение по умолчанию вызывающий указывает явно: `node.version.orElse("—")`.
`Missing` даёт `default`, `NotLoaded` бросает `IllegalStateException`: обращение к незапрошенному значению —
ошибка вызывающего кода, а не пустая ячейка. Адаптер `NotLoaded` не создаёт (поля заполнены всегда), оно
остаётся значением по умолчанию у вручную собранных `ProductNode` в тестах.

Значения атрибутов (`ProductNode.attributes`, `Usage.attributes`) читаются так же: `attributes[ключ].orElse("—")`.
Это функция на `AttributeValue?`: `Text` даёт текст, `Number` — число строкой, `Empty` («запрошено, значения нет»)
и отсутствие ключа («не запрашивалось») дают `default`.
Текст для пустых значений (прочерк, «отсутствует», пустая строка) выбирает отчёт, модель его не хранит.

- **Позиция** (`Usage.position`) — выбирается по правилам ключа `position` в `mapping.link.attributes` (`select(relation, parentKind, childKind)`).
  Если есть правило, ищется атрибут связи с именем из правила в ответе `/get-link-attributes`. Значение преобразуется
  в `Int`: успешно → `Field.Value(число)`, нет → `Field.Missing`.
- **Атрибуты связи** (`Usage.attributes`) — только ключи из `query.linkAttributes`. Для каждого ключа ищется список
  правил в `mapping.link.attributes[ключ]` и выбирается по специфичности селектора (как для позиции).
  Если есть правило и атрибут найден в `/get-link-attributes` → `AttributeValue.Text(значение)`, иначе `Empty`.
- **Количество** (`Usage.quantity`) — из `minQuantity`/`maxQuantity` связи: оба пусты → `Quantity.Missing`;
  одно значение или `min == max` → `Quantity.Exact`; иначе `Quantity.Range`. **Единица** — `unit` связи.
- **Атрибуты узла** (`ProductNode.attributes`) — только те ключи, что перечислены в `query.attributes`. Каждый ключ
  обязан быть в `mapping.attributes`, источник выбирается из `rule.nameFor(kind)` (с учётом переопределения `byKind`).
  Непустое значение → `AttributeValue.Text`, иначе `Empty`. Не запрошенные ключи отсутствуют.
- `query.includeUnmappedAttributes = true` — остальные непустые атрибуты объекта добавляются как
  `AttributeKey.Unmapped(name)`. Исключаются все известные имена: из `mapping.attributes.*.name` и
  `mapping.attributes.*.byKind.*`, из `mapping.identity.*.{name}` и их `byKind` переопределений.

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
