---
name: loodsman-mapping
description: Добавление новых типов объектов (видов) и атрибутов Loodsman в модель данных report-generator. Используй этот скилл, когда пользователь просит добавить новый тип документа/детали/узла, новый атрибут, «получить данные атрибута в выводе», изменить маппинг, упоминает PdmNodeKind, AttributeKey, loodsman-mapping.yaml, Registries или жалуется, что «атрибут не приходит/пустой».
---

## Invariants

- Язык: Kotlin, сериализация: kotlinx-serialization.
- Маппинг декларативный (YAML), но домен — sealed-иерархии: новый вид или ключ атрибута **всегда** требуют правки кода.
  Одного YAML недостаточно.
- Все изменения согласованы тройкой: домен → реестр → YAML. `MappingConfig.validate()` на старте
  (`LoodsmanContext.bootstrap`) падает fail-fast, если YAML ссылается на несуществующий ключ реестра.

## Путь данных (почему именно эти файлы)

```
loodsman-mapping.yaml            (имя типа/атрибута в PDM -> id в домене)
  -> Registries.kt               (id -> sealed-вариант домена)
  -> KindMapper / AttributeRequests  (разрешение имён для вида, byKind)
  -> NodeLoader                  (грузит ТОЛЬКО явно запрошенные атрибуты)
  -> PdmNode.attributes          (AttributeKey -> AttributeValue)
  -> отчёт (ProductNodeSpecification -> DataContext -> шаблон YAML)
```

## Новый тип объекта

1. `report-domain/src/main/kotlin/dev/reportgenerator/domain/PdmNodeKind.kt` — новый `data object` (sealed-вариант).
2. `report-loodsman/src/main/kotlin/dev/reportgenerator/loodsman/mapping/Registries.kt` — `itemKinds`: `"ID"` ->
   вариант. ID — SCREAMING_SNAKE, латиницей.
3. `report-loodsman/src/main/resources/loodsman-mapping.yaml`, секция `kinds:` — `"Имя в Loodsman": ID`.
4. Тип обязан существовать в метаданных Loodsman (проверка на старте: `snapshot.hasObjectType`). После изменения
   метаданных стенда — `POST /admin/metadata/refresh` (report-server).
5. Если тип должен попасть в отчёт спецификации — `reports/specification/.../ProductNodeSpecification.kt`:
    - `group()`: фильтр решает, попадает ли usage в вывод (документы — только разрешённые виды);
    - `toItemKind()`: вид -> `ItemKind` (порядок enum = порядок групп по ГОСТ 2.106).

Без шага 3 тип становится `PdmNodeKind.Unmapped` (политика `onUnmapped.kind: KEEP`) и выпадает из отчёта молча.

## Новый атрибут

1. `report-domain/src/main/kotlin/dev/reportgenerator/domain/AttributeKey.kt` — новый `data object`.
2. `Registries.kt` — `attributeKeys`: `"keyId"` -> вариант. keyId — camelCase.
3. YAML, секция `attributes:` — правило. Обязательно `name` (имя по умолчанию для всех видов) **или** непустой `byKind`
   (переопределение для отдельных видов):

```yaml
attributes:
  format:
    byKind:
      SPECIFICATION: Формат   # атрибут есть только у этого вида
```

4. **Явный запрос — главная ловушка.** Атрибут грузится только если он в запросе. Загрузчик строит список имён из
   `attributeKeys` запроса и не вызывает список атрибутов объекта (`get-info-about-version`), если неключевой атрибут не
   запрошен. Симптом пропуска: в `attributes` узла нет ключа, ошибок нет. Места запроса:
    - серверный флоу: `reports/specification/.../SpecificationQuery.kt` -> `attributes = setOf(...)`;
    - ручная проверка / DSL: `pdmTree { attributeKeys = setOf(...) }`, `pdmNode { ... }` (report-loodsman `Main.kt`).
5. Если значение нужно в отчёте — добавить поле в домен вывода (`SpecificationItem`), прочитать в
   `ProductNodeSpecification` (`Text -> значение, Empty -> null/ошибка, NotLoaded -> ошибка`), объявить в
   `SpecificationDataContext` (билдер даёт схему и значения), забиндить в шаблоне (`gost-spec.yaml`).
6. Если атрибут является ключевым атрибутом типа в Loodsman — отдельного чтения не нужно: значение берётся из свойства
   `product` и попадает в `NodeKey` автоматически.

## Правила имён

- Сравнение имён типов и атрибутов — **строгое**, без trim и без учёта регистра не делается. `"Формат"` !=
  `"формат "` != `"Format"`. Точное имя смотреть в карточке типа на стенде Loodsman.
- Пустое значение атрибута = `AttributeValue.Empty` (не ошибка). Ключ отсутствует в `attributes` = атрибут не запрошен.
  Различай эти состояния при диагностике.
- Для вида без правила в `byKind` значение — `Empty`, не исключение.

## Проверка

```bash
./gradlew :report-domain:build :report-loodsman:build
./gradlew :reports:specification:build   # если трогал отчётную часть
```

- Ошибка валидации конфига на старте — первое, что читать: она называет точный ключ, которого нет в реестре.
- Golden-тесты `reports/specification/src/test/resources/golden` обновляются при изменении шаблона/данных.
- Вики: дополнить таблицы в `docs/wiki/loodsman-integration.md` (разделы «Виды объектов и связи», «Структура YAML
  конфига маппинга»).

## Рабочий пример

Добавлены тип «Спецификация» и атрибут «Формат» — см. git-историю: `PdmNodeKind.Specification`, `AttributeKey.Format`,
id `SPECIFICATION`/`format`, YAML `kinds` + `attributes.format.byKind`. Диагностика «вижу только Name, Формата нет» —
причина была в шаге 4 (атрибут не добавлен в `attributeKeys` запроса).

## Не трогать

- Модуль `mapping/` (khan366kos.mapping) — универсальный слой маппинга, изменений не требует.
- `AttributeRequests`/`NodeLoader` — работают для любых видов и ключей без правок.
- Секцию YAML `link.attributes` для ключа `position` — встроенный, в `linkAttributes` запроса указывать нельзя (будет
  `LoodsmanApiException`).
