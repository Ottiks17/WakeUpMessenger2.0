# WakeUp Messenger

Лёгкий фоновый сервис для ТСД на Android: постоянное XMPP-соединение с Openfire,
обмен текстовыми сообщениями и пробуждение WMS-приложения по команде.
Реализовано строго по ТЗ v1.0 от 21.09.2026.

## Стек

| Слой | Технология |
|---|---|
| UI | Jetpack Compose + Material 3 + Navigation Compose |
| Фон | Foreground Service (`specialUse`), `START_STICKY`, BootReceiver |
| XMPP | Smack 4.4.8 (`smack-android-extensions`), порт 5222, автопереподключение |
| HTTP | OkHttp, таймауты 3/3/3 сек, только `enqueue` (асинхронно) |
| БД | Room: `chat_messages`, `chat_users`, `chat_settings` |
| Язык | Kotlin 2.0.21, minSdk 24, targetSdk 34 |

## Сборка

```bash
# Android Studio Ladybug+ : File -> Open -> папка проекта -> Sync Gradle
# либо из консоли (нужен Android SDK и ANDROID_HOME):
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # подписанный релиз
```

Wrapper-jar в архив не входит — при первом открытии Android Studio создаст его сама,
либо выполните `gradle wrapper --gradle-version 8.9`.

## Первый запуск

1. Установить APK на ТСД.
2. Открыть приложение → откроется экран «Настройка энергосбережения» (если проверки не пройдены).
3. Нажать «Открыть настройки» → разрешить работу без ограничений батареи.
4. На китайских устройствах — «Автозапуск» и добавить приложение в белый список.
5. «Проверить снова» → экран закроется, запустится сервис, в шторке появится
   уведомление «WakeUp Messenger — Активен, ожидание команд».
6. Настройки → указать хост/порт REST API, `deviceId`, package и action WMS → «Сохранить настройки».
7. «Переподключить».

## Структура кода

```
core/          Const, FileLogger (ротация 10 МБ), PowerSaveChecker, DeviceIdProvider
data/local/    Room: сущности, DAO, база
data/remote/   ApiClient (OkHttp + WakeLock), модели, CredentialsStore (шифрованный)
data/repo/     SettingsRepository, AuthRepository (5 попыток/10 сек/fallback), ChatRepository
xmpp/          XmppManager (Smack), CommandParser, Command
service/       WakeUpService, BootReceiver, CommandExecutor, WmsLauncher,
               DeviceRebooter, NotificationHelper
ui/            MainActivity (Navigation), BatterySetupActivity, экраны Compose, ViewModel'и
```

## Соответствие ТЗ

| Пункт ТЗ | Где реализовано |
|---|---|
| 3.1 Foreground Service, WakeLock ≤5 сек | `WakeUpService`, `ApiClient.execute` |
| 3.2 Экраны 1–4 | `ChatListScreen`, `ChatScreen`, `SettingsScreen`, `BatterySetupActivity` |
| 3.3 Команды task/update/restart/ping/notification | `CommandParser`, `CommandExecutor` |
| 3.4 Room | `AppDatabase`, `Entities.kt` |
| 4.1 Порядок запуска | `WakeUpService.bootstrap` |
| 4.2 Обработка сообщения | `XmppManager.handleIncoming` → `CommandExecutor` |
| 4.3 Ретраи, fallback, офлайн-очередь | `AuthRepository`, `XmppManager.flushOfflineQueue` |
| 5.1 Smack 4.4.6+ | `app/build.gradle.kts`, `XmppManager` |
| 5.2 OkHttp, таймауты 3 сек | `ApiClient` |
| 5.3 Лог + ротация + маскирование пароля | `FileLogger` |
| 5.4 Автозапуск | `BootReceiver`, manifest |
| 5.5 Безопасность | `CredentialsStore`, `FileLogger.mask` |
| 6 REST API | `ApiClient` |
| 8 Исключения энергосбережения | `PowerSaveChecker`, `BatterySetupActivity` |
| 9.4 Счётчик | Настройки → «Диагностика счётчика» |

## Расхождения в ТЗ и принятые решения

1. **Пароль**: п. 5.5 запрещает хранение в SharedPreferences, п. 4.3 требует fallback на
   последние креды. Решение: рабочий пароль — в памяти; резервная копия — в
   `EncryptedSharedPreferences` (AES-256-GCM, ключ в Android Keystore). Обычные
   SharedPreferences не используются, в лог пароль не попадает.
2. **Команда `restart`**: `PowerManager.reboot()` требует системного разрешения `REBOOT`.
   Приложение должно быть системным/привилегированным на ТСД; предусмотрен fallback через `su`,
   иначе ошибка пишется в лог.
3. **«Невидимка»**: стандартной невидимости в XMPP нет, реализовано как `presence unavailable`
   при живом соединении — команды и сообщения продолжают приходить.
4. **Автозапуск на китайских прошивках** программно не проверяется (API нет) — экран даёт
   прямые переходы в меню вендоров, факт настройки подтверждает администратор.
