---
name: loodsman-rest-api
description: Работа с REST API Loodsman
---

## Получение данных

- Приоритет - использовать mcp `loodsman`
- Если mcp не установлен, то можно использовать чтение `swagger.json` - (STRICT) обязательно подтверждение использования этой опции от пользователя

## Поиск в mcp loodsman

1. `search_loodsman_api` - запрос формулировать как ОПИСАНИЕ действия на русском ("Возвращает список всех типов"), а не как путь (`get-type-list`): поиск по пути возвращает шум
2. В результате смотреть `path`, `method`, `response_dtos`/`request_dto`.
3. `search_loodsman_dto` - искать по ТОЧНОМУ имени DTO из `response_dtos`/`request_dto`. Поиск по части имени или общему слову ("Type") возвращает пусто
4. Эндпоинт не найден после 2-3 разных формулировок - спросить пользователя, путь не угадывать

## Http client

- Http Client - Ktor client
- Для каждой группы методов REST API Loodsman использовать отдельный класс внутри http-client
  - Каждый такой класс должен иметь название из пути метода
  <example>
    path: `/api/v4/ObjectInfo/get-prop-objects`
    className: `ObjectInfo`
  </example>
