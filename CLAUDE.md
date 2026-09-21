# Mint — лаунчер Minecraft

Десктопный лаунчер на Kotlin + Compose Multiplatform (Desktop). Портативный: все данные лежат рядом с программой.
Общение с пользователем и комментарии в коде — на русском.

## Стек и команды

- Kotlin 2.4.20, Compose 1.12.0, kotlinx-serialization-json, kotlinx-coroutines-swing. JVM 21. Сторонних UI-библиотек нет (Material не используется).
- `./gradlew compileKotlin` — быстрая проверка сборки.
- `./gradlew run` — запуск; в dev-режиме данные лежат в `./run` (`-Dmint.home`, см. `build.gradle.kts`).
- `./gradlew packageExe` / `packageMsi` — установщик Windows (jpackage).
- `./gradlew packagePortable` → `build/release/Mint-<версия>-windows-portable.zip` — архив для релиза лаунчера.
- Релиз лаунчера: поднять `version` в `build.gradle.kts` и `GameLauncher.LAUNCHER_VERSION` → `packagePortable` → `gh release create v<версия> build/release/Mint-<версия>-windows-portable.zip -R GrayRK/MintLauncher`. Установленные лаунчеры увидят его и обновятся (`core/Updater.kt`).
- `java -jar … --headless <ник>` — служебный режим без UI: установить и запустить первую сборку офлайн (`Main.kt`).
- `./gradlew run --args="--pack-manifest <id>"` — обновить `mint-pack.json` и `.gitignore` сборки; `--pack-sync <id>` — скачать сборку из релиза, как это делает игрок.
- Git: приватный репозиторий https://github.com/GrayRK/MintLauncher, ветка `main`.

## Раскладка данных (`core/MintPaths.kt`)

```
data/ (в dev — run/)
  launcher.json      настройки + аккаунт (LauncherSettings)
  runtime/java-21/   Java, скачанная автоматически (Adoptium)
  runtime/authlib-injector.jar
  game/              общие versions / libraries / assets
  instances/<id>/    папка сборки: instance.json, mint-pack.json, .mint-pack.json (что установлено), mods, saves, config …
  instances/<id>/server/  локальный сервер сборки: NeoForge, world, свои mods и config
  cache/skins/       <uuid>.png + <uuid>.model (slim|classic)
  logs/              game-latest.log
```

## Структура кода (`src/main/kotlin/mint`)

- `Main.kt` — окно без рамки (undecorated, transparent, скругления 16dp), выбор экрана, слушатель фокуса для системной темы, headless-режим.
- `core/`
  - `Settings.kt` — `LauncherSettings` (память, Java, тема, аккаунт, выбранная сборка…), `Account`, `Theme { LIGHT, DARK, SYSTEM }`, `SettingsStore`.
  - `Http.kt` — `java.net.http` клиент, скачивание с sha1 и ретраями, параллельный `downloadAll`.
  - `Updater.kt` — самообновление: последний релиз `GrayRK/MintLauncher` (версия из `jpackage.app-version`), zip качается и распаковывается в `data/cache/update/staged`, затем скрытый `update.ps1` ждёт выхода лаунчера, меняет `app/`, `runtime/`, `Mint.exe` (с откатом) и запускает Mint снова; лог — `data/cache/update/update.log`. `applied.txt` не даёт зациклиться, если замена не удалась. Работает только в портативной установке с правом записи (не из `gradlew run`).
  - `Hardware.kt` — GPU/CPU/ОС (реестр Windows через `reg query`, `sysctl`/`system_profiler` на macOS, `/proc` + `lspci` на Linux) и `systemDarkTheme()`.
- `auth/`
  - `Auth.kt` — офлайн-аккаунт (UUID `OfflinePlayer:<ник>`), Yggdrasil-вход (Ely.by по умолчанию или свой authlib-injector сервер), 2FA через `пароль:код`, validate/refresh перед запуском. `AuthlibInjector` — javaagent для игры.
  - `Skins.kt` — скин через sessionserver (`https://authserver.ely.by/api/authlib-injector/sessionserver/session/minecraft/profile/<uuid>` → base64 textures → URL + model). Кэш на диске; офлайн — Стив из клиентского jar.
- `game/`
  - `Instance.kt` — модель сборки (`id, name, minecraft, loader VANILLA|NEOFORGE, loaderVersion, memoryMb, repo`) и `Instances` (сканирует `instances/*/instance.json`, создаёт заглушки официальных сборок; если сборок нет — локальную `vanillamint`).
  - `Packs.kt` — сборки из GitHub-репозиториев (`Packs.official`). `sync` перед запуском ставит последний релиз: файлы репозитория копируются (правки игрока сохраняются, пока файл не изменился в сборке), внешние файлы из `mint-pack.json` качаются по url+sha1. Папка с `.git` — рабочая копия разработчика, не синхронизируется. `writeManifest` ищет моды/шейдеры/ресурспаки на Modrinth по sha1. `PackSide { BOTH, CLIENT, SERVER }` в манифесте делит моды между клиентом и сервером; выставленная вручную сторона переживает пересборку манифеста.
  - `ServerLauncher.kt` — локальный сервер сборки в `instances/<id>/server`: NeoForge ставится официальным инсталлером (`--installServer`), конфиги копируются из сборки, `server/mods` набирается по `PackSide` (общие моды — жёсткими ссылками, серверные качаются отдельно). Остановка командой `stop` в stdin.
  - `ServerAdmin.kt` — обслуживание выключенного сервера: `server.properties` (построчная правка), игроки из `usercache/ops/whitelist/banned-players.json` и их правка, резервная копия мира в `instances/<id>/server-backups/`, пересоздание мира (сброс `level-seed`), удаление папки `server` целиком.
  - `VanillaInstaller.kt` — манифест Mojang, клиент, библиотеки, ассеты.
  - `NeoForgeInstaller.kt` — последняя NeoForge под версию MC с maven.neoforged.net, запуск процессоров инсталлера, маркер `.mint-installed`.
  - `VersionModel.kt` — разбор version json, наследование (`inheritsFrom`), rules, подстановка аргументов.
  - `JavaRuntime.kt` — проверка Java, авто-установка Java 21 (сейчас только Windows x64 JRE с Adoptium).
  - `GameLauncher.kt` — `resolveJava` → `prepare` (установка) → `launch` (процесс игры, вывод строк, код выхода). `LAUNCHER_VERSION`.
- `ui/`
  - `AppState.kt` — всё состояние UI: `Screen { Login, Main }`, `Tab { Home, Instances, Mods, Server, Account, Settings }`, `ServerState`, `ServerSection`, `LaunchState`, скин, железо, тема, логика входа/запуска.
  - `ServerTab.kt` — вкладка сервера (в доке всегда): шапка с состоянием, адресами (локальный и внешний через api.ipify.org) и счётчиком игроков; разделы «Консоль» (быстрые команды), «Игроки» (головы скинов, админ/белый список/кик/бан, переключатель белого списка), «Управление» (копия и пересоздание мира, основные настройки сервера, удаление сервера с подтверждением).
  - `MainScreen.kt` — каркас после входа: `TitleBar` + чип аккаунта (голова скина, клик → вкладка «Аккаунт») + боковая панель (`SideDock`) + содержимое вкладки.
  - `HomeTab.kt` — hero с кнопками «Играть» и «Сервер» и прогрессом (консоли игры пока нет — лог в `logs/game-latest.log`), карточки «Мои сборки», «Что нового», «Ресурсы» (память, Java, GPU, CPU, ОС). Если в папке сборки есть `banner.png` — он становится фоном hero (затемнение снизу + светлые цвета `OnArt*`), иначе рисуется мятный градиент.
  - `AccountTab.kt` — 3D-скин, профиль, выход, «Обновить скин».
  - `SettingsTab.kt` — одна прокручиваемая страница с разделами: Память, Java, Запуск, Внешний вид (тема), О лаунчере (версия, обновления, автообновление).
  - `UpdateUi.kt` — экран автообновления при запуске, плашка «Обновить» в заголовке, карточка «О лаунчере».
  - `LoginScreen.kt` — вход Ely.by / свой сервер / офлайн.
  - `SkinView.kt` — `SkinHead` (лицо + шляпа) и `SkinModelView`: собственный программный растеризатор с z-буфером (ортографическая проекция, slim/legacy/HD-скины), вращение перетаскиванием.
  - `Components.kt` — `Txt`, `Card`, `PrimaryButton`, `OutlineButton`, `LinkText`, `MintTextField`, `Checkbox`, `Toggle`, `Segmented`, `ProgressBar`, `Logo`, `clickableNoRipple`, `rememberHover`.
  - `Theme.kt` — `MintColors` (светлая/тёмная пары, `MintColors.dark` — Compose-состояние), шрифты Nunito (заголовки) и Manrope (текст), `nunito()`/`manrope()`.
  - `Icons.kt` — `MintIcon` → SVG в `resources/icons` (Phosphor regular; `rotate-3d.svg` нарисован вручную).
  - `TitleBar.kt` — перетаскиваемый заголовок 46dp и кнопки окна.

## Сборки

- Основная сборка — **CreateMint** (`run/instances/createmint`, NeoForge 1.21.1, Create и аддоны). Папка сборки — отдельный git-репозиторий `GrayRK/CreateMint`; она же значится в `Packs.official`.
- Журнал сборки — `run/instances/createmint/MODPACK.md`: моды, стороны клиент/сервер, настройки, совместимость с чеклистами, безвредные сообщения логов, оптимизация, журнал изменений. Любое изменение сборки сначала отражать там.
- Раздача игрокам: `--pack-manifest createmint` → коммит → `gh release create`. Лаунчер ставит последний релиз, моды качает с Modrinth. Папка сборки с `.git` не синхронизируется — у разработчика она остаётся исходником.
- Локальный сервер поднимается кнопкой «Сервер» рядом с «Играть» (или `--server <id>`). Управление — на вкладке сервера. Пока сервер жив, игроки меняются командами в его консоли (он сам пишет json); у выключенного лаунчер правит файлы напрямую. Мир, настройки и удаление — только у выключенного сервера, иначе он перезапишет файлы своими. EULA Minecraft принимает игрок — без этого сервер не ставится. Друзья подключаются по внешнему IP хоста (проброс порта 25565).

## Правила и договорённости

- **Цвета только через `MintColors`**, никаких `Color(0x…)` в экранах — иначе сломается тёмная тема. Новый цвет = новая пара light/dark в `Theme.kt`. Текст/иконки полупрозрачные — через `MintColors.ink(alpha)`.
- Семантика токенов арта: `ArtScrim` — затемнение под текстом на баннере; `OnArt`/`onArt(alpha)` — текст и иконки поверх него; `OnArtAccent` — мятный акцент на арте; `OnArtDanger` — ошибка на арте. Эти цвета одинаковы в обеих темах: они лежат на картинке, а не на фоне окна.
- Семантика мятных токенов: `Mint` — заливка; `MintInk`/`MintDarker` — текст/иконки **на** заливке; `MintDeep` — акцент на фоне окна; `LinkHover` — ссылка при наведении; `Knob` — бегунки.
- Боковая панель видна всегда. Вкладки «Сборки» и «Моды» пока ничего не делают (запланированы: каталог сборок с описанием; управление модами/ресурспаками).
- Во вкладке «Аккаунт» — только аккаунт; в «Настройках» — только настройки, без кнопки «На главную».
- Дизайн-референсы: `src/main/resources/mint_design_ref/` (`claude_design/project/Mint Launcher.dc.html`, скриншоты с пояснениями пользователя в `scrin/`). Макета тёмной темы нет — палитра подобрана вручную.
- Пользователь проверяет результат визуально. Хорошая практика — после UI-правок запускать `./gradlew run` и снимать скриншот окна (PowerShell: `GetWindowRect` + `CopyFromScreen`; клики по доку через `SetCursorPos`/`mouse_event`). Не менять `run/launcher.json` пользователя без восстановления.
- Внешние API, от которых зависит лаунчер: piston-meta.mojang.com, libraries.minecraft.net, resources.download.minecraft.net, maven.neoforged.net, api.adoptium.net, authserver.ely.by, authlib-injector.yushi.moe, api.modrinth.com + cdn.modrinth.com (моды сборок), api.github.com (релизы сборок), api.ipify.org (внешний IP для адреса сервера).
