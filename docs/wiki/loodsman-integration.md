# Loodsman API интеграция

`report-loodsman` (`dev.reportgenerator.loodsman`) — клиент Loodsman PDM API v4,
реализует `PdmClient` из `report-api`. `report-server` (`dev.reportgenerator.server`) —
Ktor REST-обёртка вокруг него: `POST /specifications/{versionId}` тянет спецификацию из
Loodsman и рендерит PDF.

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

Маппинг типа Loodsman -> `ItemDto.kind` (`mapItemKind`): Сборочный чертеж -> DOCUMENTATION, Комплекс -> COMPLEX,
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
