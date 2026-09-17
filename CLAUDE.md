# Mint — лаунчер Minecraft

Десктопный лаунчер на Kotlin + Compose Multiplatform (Desktop). Портативный: все данные лежат рядом с программой.
Общение с пользователем и комментарии в коде — на русском.

## Стек и команды

- Kotlin 2.4.20, Compose 1.12.0, kotlinx-serialization-json, kotlinx-coroutines-swing. JVM 21. Сторонних UI-библиотек нет (Material не используется).
- `./gradlew compileKotlin` — быстрая проверка сборки.
- `./gradlew run` — запуск; в dev-режиме данные лежат в `./run` (`-Dmint.home`, см. `build.gradle.kts`).
- `./gradlew packageExe` / `packageMsi` — установщик Windows (jpackage).
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
  cache/skins/       <uuid>.png + <uuid>.model (slim|classic)
  logs/              game-latest.log
```

## Структура кода (`src/main/kotlin/mint`)

- `Main.kt` — окно без рамки (undecorated, transparent, скругления 16dp), выбор экрана, слушатель фокуса для системной темы, headless-режим.
- `core/`
  - `Settings.kt` — `LauncherSettings` (память, Java, тема, аккаунт, выбранная сборка…), `Account`, `Theme { LIGHT, DARK, SYSTEM }`, `SettingsStore`.
  - `Http.kt` — `java.net.http` клиент, скачивание с sha1 и ретраями, параллельный `downloadAll`.
  - `Hardware.kt` — GPU/CPU/ОС (реестр Windows через `reg query`, `sysctl`/`system_profiler` на macOS, `/proc` + `lspci` на Linux) и `systemDarkTheme()`.
- `auth/`
  - `Auth.kt` — офлайн-аккаунт (UUID `OfflinePlayer:<ник>`), Yggdrasil-вход (Ely.by по умолчанию или свой authlib-injector сервер), 2FA через `пароль:код`, validate/refresh перед запуском. `AuthlibInjector` — javaagent для игры.
  - `Skins.kt` — скин через sessionserver (`https://authserver.ely.by/api/authlib-injector/sessionserver/session/minecraft/profile/<uuid>` → base64 textures → URL + model). Кэш на диске; офлайн — Стив из клиентского jar.
- `game/`
  - `Instance.kt` — модель сборки (`id, name, minecraft, loader VANILLA|NEOFORGE, loaderVersion, memoryMb, repo`) и `Instances` (сканирует `instances/*/instance.json`, создаёт заглушки официальных сборок).
  - `Packs.kt` — сборки из GitHub-репозиториев (`Packs.official`). `sync` перед запуском ставит последний релиз: файлы репозитория копируются (правки игрока сохраняются, пока файл не изменился в сборке), внешние файлы из `mint-pack.json` качаются по url+sha1. Папка с `.git` — рабочая копия разработчика, не синхронизируется. `writeManifest` ищет моды/шейдеры/ресурспаки на Modrinth по sha1.
  - `VanillaInstaller.kt` — манифест Mojang, клиент, библиотеки, ассеты.
  - `NeoForgeInstaller.kt` — последняя NeoForge под версию MC с maven.neoforged.net, запуск процессоров инсталлера, маркер `.mint-installed`.
  - `VersionModel.kt` — разбор version json, наследование (`inheritsFrom`), rules, подстановка аргументов.
  - `JavaRuntime.kt` — проверка Java, авто-установка Java 21 (сейчас только Windows x64 JRE с Adoptium).
  - `GameLauncher.kt` — `resolveJava` → `prepare` (установка) → `launch` (процесс игры, вывод строк, код выхода). `LAUNCHER_VERSION`.
- `ui/`
  - `AppState.kt` — всё состояние UI: `Screen { Login, Main }`, `Tab { Home, Instances, Mods, Account, Settings }`, `LaunchState`, скин, железо, тема, логика входа/запуска.
  - `MainScreen.kt` — каркас после входа: `TitleBar` + чип аккаунта (голова скина, клик → вкладка «Аккаунт») + боковая панель (`SideDock`) + содержимое вкладки.
  - `HomeTab.kt` — hero с кнопкой «Играть»/прогрессом/консолью, карточки «Мои сборки», «Что нового», «Ресурсы» (память, Java, GPU, CPU, ОС).
  - `AccountTab.kt` — 3D-скин, профиль, выход, «Обновить скин».
  - `SettingsTab.kt` — одна прокручиваемая страница с разделами: Память, Java, Запуск, Внешний вид (тема), О лаунчере.
  - `LoginScreen.kt` — вход Ely.by / свой сервер / офлайн.
  - `SkinView.kt` — `SkinHead` (лицо + шляпа) и `SkinModelView`: собственный программный растеризатор с z-буфером (ортографическая проекция, slim/legacy/HD-скины), вращение перетаскиванием.
  - `Components.kt` — `Txt`, `Card`, `PrimaryButton`, `OutlineButton`, `LinkText`, `MintTextField`, `Checkbox`, `Toggle`, `Segmented`, `ProgressBar`, `Logo`, `clickableNoRipple`, `rememberHover`.
  - `Theme.kt` — `MintColors` (светлая/тёмная пары, `MintColors.dark` — Compose-состояние), шрифты Nunito (заголовки) и Manrope (текст), `nunito()`/`manrope()`.
  - `Icons.kt` — `MintIcon` → SVG в `resources/icons` (Phosphor regular; `rotate-3d.svg` нарисован вручную).
  - `TitleBar.kt` — перетаскиваемый заголовок 46dp и кнопки окна.

## Сборки

- Каждая сборка — отдельный **публичный** репозиторий (иначе игроки не скачают). `vanillamint` → https://github.com/GrayRK/VanillaMint.
- Разработка прямо в `run/instances/<id>` (это git-репозиторий сборки, лаунчерный `.gitignore` исключает `run/`).
- Цикл: правки → `--pack-manifest <id>` → коммит → `gh release create vX.Y.Z` в репозитории сборки. Игроки получают только релизы.
- Моды без Modrinth лежат в git сборки (проверять лицензию) или получают `url` в манифесте вручную.
- Личные данные (saves, options.txt, journeymap, logs…) в `.gitignore` сборки.

## Правила и договорённости

- **Цвета только через `MintColors`**, никаких `Color(0x…)` в экранах — иначе сломается тёмная тема. Новый цвет = новая пара light/dark в `Theme.kt`. Текст/иконки полупрозрачные — через `MintColors.ink(alpha)`.
- Семантика мятных токенов: `Mint` — заливка; `MintInk`/`MintDarker` — текст/иконки **на** заливке; `MintDeep` — акцент на фоне окна; `LinkHover` — ссылка при наведении; `Knob` — бегунки.
- Боковая панель видна всегда. Вкладки «Сборки» и «Моды» пока ничего не делают (запланированы: каталог сборок с описанием; управление модами/ресурспаками).
- Во вкладке «Аккаунт» — только аккаунт; в «Настройках» — только настройки, без кнопки «На главную».
- Дизайн-референсы: `src/main/resources/mint_design_ref/` (`claude_design/project/Mint Launcher.dc.html`, скриншоты с пояснениями пользователя в `scrin/`). Макета тёмной темы нет — палитра подобрана вручную.
- Пользователь проверяет результат визуально. Хорошая практика — после UI-правок запускать `./gradlew run` и снимать скриншот окна (PowerShell: `GetWindowRect` + `CopyFromScreen`; клики по доку через `SetCursorPos`/`mouse_event`). Не менять `run/launcher.json` пользователя без восстановления.
- Внешние API, от которых зависит лаунчер: piston-meta.mojang.com, libraries.minecraft.net, resources.download.minecraft.net, maven.neoforged.net, api.adoptium.net, authserver.ely.by, authlib-injector.yushi.moe.
