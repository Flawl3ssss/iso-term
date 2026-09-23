# IsoTerm — изолированный proot-Ubuntu терминал
APK под Android 16 (API 36), Xiaomi 13T (arm64).

## Идея изоляции
- Только app-private папка: `getFilesDir()` / `getDataDir()`. Никаких `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_*`.
- proot `-r $ROOTFS` + бинды ТОЛЬКО внутри `files/`: `home:/root`, `tmp:/tmp`, `$ROOTFS/tmp:/dev/shm`.
- Никаких `-b /sdcard /storage /system /vendor`. Чистый `env -i`.
- Ubuntu rootfs качается при первом запуске в `files/ubuntu-noble-arm64`, наружу не торчит.

## Фон (чтобы не закрывалось)
- `ForegroundService` с `foregroundServiceType="specialUse"` (для API 34-36, без 6-часового лимита `dataSync`).
- `START_STICKY`, `stopWithTask=false`, отдельный процесс `:terminal`.
- `BOOT_COMPLETED` рестарт (если юзер включил).
- Запрос `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + инструкция для Xiaomi:
  1. Автозапуск ON (com.miui.securitycenter)
  2. Батарея -> Без ограничений
  3. Замок в шторке Recents
- WakeLock `PARTIAL` только по тумблеру, иначе жор батареи.
- Уведомление `IMPORTANCE_LOW` с кнопкой Стоп.

## Сборка через GitHub
1. Создай пустой репозиторий на GitHub, НЕ коммить токены.
2. Залей эту папку `iso-term/` в корень репозитория:
```bash
cd iso-term
git init -b main
git add .
git commit -m "iso term"
git remote add origin git@github.com:USER/REPO.git
git push -u origin main
```
3. Во вкладке Actions -> `Build debug APK` -> APK появится в Artifacts -> `isoterm-debug-apk`.
4. Скачай на Xiaomi 13T, поставь, открой, нажми `Установить Ubuntu` (нужен интернет ~30МБ), затем `Запустить`.

> Твой PAT из чата засвечен — отзови его в GitHub Settings -> Developer settings -> Revoke. Для push используй SSH или новый fine-grained token с scope `contents:write`, в чат его не кидай.

## Структура
```
app/src/main/java/com/example/isoterm/
  MainActivity.kt      — UI терминала (Termux TerminalView)
  TerminalService.kt   — ForegroundService specialUse, STICKY
  BootReceiver.kt      — BOOT_COMPLETED + рестарт при свайпе
  ProotManager.kt      — установка proot + ubuntu-base, построение команды
  XiaomiHelper.kt      — battery-exemption + autostart intents для MIUI/HyperOS
```
