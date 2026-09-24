# Требования к серверной части

## GET /v1/auth/xmpp?deviceId=<id>

```json
{
  "xmppHost": "192.168.0.10",
  "xmppPort": 5222,
  "xmppLogin": "tsd001@openfire",
  "xmppPassword": "secret",
  "apiHost": "192.168.0.10",
  "apiPort": 80
}
```

`xmppLogin` может быть как `user`, так и `user@domain`. Если домена нет,
клиент подставит `xmppHost`. Пароль в лог не пишется.

## POST /v1/wakeup/confirm?deviceId=<id>&taskId=<id>

Тело дублирует параметры:

```json
{ "deviceId": "0012", "taskId": "12345" }
```

Ответ 2xx = подтверждение принято. Любая ошибка не блокирует запуск WMS.

## GET /v1/tasks?deviceId=<id>

```json
[ { "taskId": "12345", "title": "Отбор зоны А" } ]
```

Допускается обёртка `{ "tasks": [ ... ] }`.

## Диагностические (п. 9.4 ТЗ)

* `GET /wakeup/info?device=<id>` → `{ "pingCount": 5, "oldPingCount": 0 }`
* `DELETE /wakeup/reset?device=<id>` → сброс счётчика, `oldPingCount` = прежнее значение

## Openfire

* Порт 5222 (TCP), TLS — `ifpossible`.
* Для каждого ТСД создаётся учётная запись; рекомендуется общая группа, чтобы устройства
  видели присутствие друг друга в ростере.
* Офлайн-сообщения должны быть включены (Server → Server Settings → Offline Messages → Store).

## Проверка вручную

```bash
curl "http://SERVER/v1/auth/xmpp?deviceId=0012"
curl -X POST "http://SERVER/v1/wakeup/confirm?deviceId=0012&taskId=12345"
curl "http://SERVER/v1/tasks?deviceId=0012"
curl "http://SERVER/wakeup/info?device=0012"
curl -X DELETE "http://SERVER/wakeup/reset?device=0012"
```
