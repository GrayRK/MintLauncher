package mint.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess
import mint.auth.Auth
import mint.auth.AuthException
import mint.auth.Skin
import mint.auth.Skins
import mint.core.Account
import mint.core.AccountType
import mint.core.Http
import mint.core.Hardware
import mint.core.HardwareInfo
import mint.core.LauncherRelease
import mint.core.LauncherSettings
import mint.core.Updater
import mint.core.SettingsStore
import mint.core.Theme
import mint.game.GameLauncher
import mint.game.Instance
import mint.game.Instances
import mint.game.JavaInfo
import mint.game.JavaRuntime
import mint.game.ServerAdmin
import mint.game.ServerLauncher
import mint.game.ServerPlayer
import java.awt.Desktop
import java.io.File

enum class Screen { Login, Main }

/** Вкладки боковой панели. */
enum class Tab { Home, Instances, Mods, Server, Account, Settings }

sealed interface LaunchState {
    data object Idle : LaunchState
    data class Preparing(val stage: String, val fraction: Float?) : LaunchState
    data object Running : LaunchState
    data class Failed(val message: String) : LaunchState
}

sealed interface ServerState {
    data object Idle : ServerState
    data class Preparing(val stage: String, val fraction: Float?) : ServerState

    /** Сервер работает; [ready] — мир загружен и пускает игроков. */
    data class Running(val ready: Boolean) : ServerState
    data object Stopping : ServerState
    data class Failed(val message: String) : ServerState
}

/** Разделы вкладки сервера. */
enum class ServerSection { Console, Players, Manage }

/** Обновление самого лаунчера. */
sealed interface UpdateState {
    data object None : UpdateState
    data object Checking : UpdateState
    data class Available(val release: LauncherRelease) : UpdateState
    data class Downloading(val release: LauncherRelease, val fraction: Float) : UpdateState

    /** Файлы скачаны, лаунчер закрывается и перезапускается уже новым. */
    data class Restarting(val release: LauncherRelease) : UpdateState
    data class Failed(val release: LauncherRelease?, val message: String) : UpdateState
}

/** Итог операции на вкладке сервера: копия сделана, мир удалён или что-то пошло не так. */
data class ServerNotice(val text: String, val error: Boolean = false)

class AppState(private val scope: CoroutineScope, private val onHideWindow: (Boolean) -> Unit) {
    var settings by mutableStateOf(SettingsStore.load())
        private set
    var screen by mutableStateOf(if (settings.account != null) Screen.Main else Screen.Login)
    var tab by mutableStateOf(Tab.Home)
    var skin by mutableStateOf<Skin?>(null)
        private set
    var skinLoading by mutableStateOf(false)
        private set
    var hardware by mutableStateOf<HardwareInfo?>(null)
        private set
    var instances by mutableStateOf(Instances.all())
        private set
    var launch by mutableStateOf<LaunchState>(LaunchState.Idle)
        private set
    var server by mutableStateOf<ServerState>(ServerState.Idle)
        private set
    var javaInfo by mutableStateOf<JavaInfo?>(null)
        private set
    val serverLines = mutableStateListOf<String>()

    // Состояние экрана входа
    var loginBusy by mutableStateOf(false)
        private set
    var loginError by mutableStateOf<String?>(null)
    var needsTotp by mutableStateOf(false)

    private var launchJob: Job? = null
    private var serverJob: Job? = null
    private var serverHandle: ServerLauncher.Handle? = null

    // Вкладка сервера
    var serverSection by mutableStateOf(ServerSection.Console)

    /** Кто сейчас на сервере — по строкам «joined/left the game» из вывода. */
    val onlinePlayers = mutableStateListOf<String>()

    /** Ник → UUID из строки входа: игрок может ещё не попасть в usercache.json. */
    private val loginUuids = HashMap<String, String>()

    /** Все, кого знает сервер (из его json-файлов). */
    var serverPlayers by mutableStateOf<List<ServerPlayer>>(emptyList())
        private set

    /** server.properties сервера выбранной сборки. */
    var serverProperties by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Головы игроков по UUID без дефисов; null — ещё грузится или скина нет. */
    private val playerSkins = mutableStateMapOf<String, Skin?>()

    /** Идёт долгая операция (копия мира, удаление) — кнопки на это время выключены. */
    var serverTask by mutableStateOf<String?>(null)
        private set
    var serverNotice by mutableStateOf<ServerNotice?>(null)

    /** Внешний адрес для друзей; null — ещё не узнали или нет сети. */
    var publicAddress by mutableStateOf<String?>(null)
        private set

    /** Обновление лаунчера: проверка, загрузка, перезапуск. */
    var update by mutableStateOf<UpdateState>(UpdateState.None)
        private set

    /** Автообновление при запуске: пока оно идёт, вместо лаунчера показывается экран обновления. */
    var updateOnStartup by mutableStateOf(false)
        private set

    val account: Account? get() = settings.account
    val selectedInstance: Instance
        get() = instances.firstOrNull { it.id == settings.selectedInstance } ?: instances.first()

    init {
        refreshJavaInfo()
        applyTheme()
        reloadSkin()
        scope.launch {
            val info = withContext(Dispatchers.IO) { Hardware.detect() }
            hardware = info
        }
        startUpdateChecks()
    }

    // ---- Обновление лаунчера ----

    private fun startUpdateChecks() {
        updateOnStartup = settings.autoUpdate && Updater.canUpdate
        scope.launch {
            if (updateOnStartup) {
                // Без сети не держим игрока на экране обновления дольше нескольких секунд
                withTimeoutOrNull(8_000) { checkUpdate() }
                if (update is UpdateState.Checking) update = UpdateState.None
                if (update is UpdateState.Available) installUpdate() else updateOnStartup = false
            } else {
                checkUpdate()
            }
            // Лаунчер бывает открыт часами — релиз мог выйти за это время
            while (true) {
                delay(3 * 60 * 60 * 1000L)
                if (update is UpdateState.None || update is UpdateState.Failed) checkUpdate()
            }
        }
    }

    /** Спрашивает GitHub о новом релизе; ошибки сети молча игнорирует. */
    suspend fun checkUpdate(): LauncherRelease? {
        update = UpdateState.Checking
        val release = runCatching { Updater.latest() }.getOrNull()
        update = when {
            release == null -> UpdateState.None
            Updater.alreadyTried(release) -> UpdateState.Failed(
                release, "Не удалось установить ${release.version}: лог в data/cache/update/update.log",
            )
            else -> UpdateState.Available(release)
        }
        return release
    }

    fun checkUpdateNow() {
        if (update is UpdateState.Checking || update is UpdateState.Downloading || update is UpdateState.Restarting) return
        scope.launch { checkUpdate() }
    }

    /** Почему нельзя обновиться прямо сейчас; null — можно. */
    val updateBlocker: String?
        get() = when {
            !Updater.canUpdate -> "Обновление работает только в собранном лаунчере"
            launch is LaunchState.Preparing -> "Дождитесь, пока игра установится"
            server is ServerState.Preparing -> "Дождитесь, пока сервер установится"
            else -> null
        }

    /** Скачать новую версию и перезапуститься. Работающий сервер штатно остановится при выходе. */
    fun installUpdate() {
        val release = when (val u = update) {
            is UpdateState.Available -> u.release
            is UpdateState.Failed -> u.release ?: return
            else -> return
        }
        updateBlocker?.let { update = UpdateState.Failed(release, it); return }
        scope.launch {
            try {
                update = UpdateState.Downloading(release, 0f)
                val staged = Updater.download(release) { f ->
                    scope.launch(Dispatchers.Main) {
                        if (update is UpdateState.Downloading) update = UpdateState.Downloading(release, f)
                    }
                }
                update = UpdateState.Restarting(release)
                withContext(Dispatchers.IO) { Updater.applyAndRestart(release, staged) }
                // Выход запускает shutdown hook: сервер получит stop и сохранит мир
                exitProcess(0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateOnStartup = false
                update = UpdateState.Failed(release, e.message ?: e.toString())
            }
        }
    }

    fun toggleAutoUpdate(on: Boolean) = updateSettings { it.copy(autoUpdate = on) }

    fun updateSettings(transform: (LauncherSettings) -> LauncherSettings) {
        val oldTheme = settings.theme
        settings = transform(settings)
        if (settings.theme != oldTheme) applyTheme()
        scope.launch(Dispatchers.IO) { SettingsStore.save(settings) }
        refreshJavaInfo()
    }

    /** Системная тема читается при старте и при каждой активации окна лаунчера. */
    fun applyTheme() {
        MintColors.dark = when (settings.theme) {
            Theme.LIGHT -> false
            Theme.DARK -> true
            Theme.SYSTEM -> Hardware.systemDarkTheme()
        }
    }

    fun refreshJavaInfo() {
        val s = settings
        javaInfo = if (s.javaAuto) JavaRuntime.managed() else JavaRuntime.inspect(File(s.javaPath))
    }

    fun reloadSkin() {
        val acc = account ?: run { skin = null; return }
        val minecraft = selectedInstance.minecraft
        skinLoading = true
        scope.launch {
            val loaded = Skins.load(acc, minecraft)
            if (account?.uuid == acc.uuid) skin = loaded
            skinLoading = false
        }
    }

    private fun enter() {
        tab = Tab.Home
        screen = Screen.Main
        reloadSkin()
    }

    fun playOffline(nick: String) {
        try {
            val acc = Auth.offline(nick)
            loginError = null
            updateSettings { it.copy(account = acc, lastLogin = acc.username) }
            enter()
        } catch (e: AuthException) {
            loginError = e.message
        }
    }

    fun login(server: String, login: String, password: String, totp: String, remember: Boolean) {
        if (loginBusy) return
        loginBusy = true
        loginError = null
        scope.launch {
            try {
                val acc = Auth.login(server, login, password, totp)
                updateSettings {
                    it.copy(account = acc, lastLogin = login.trim(), authServer = server.trim(), rememberMe = remember)
                }
                needsTotp = false
                enter()
            } catch (e: AuthException) {
                loginError = e.message
                if (e.needsTotp) needsTotp = true
            } catch (e: Exception) {
                loginError = e.message ?: e.toString()
            } finally {
                loginBusy = false
            }
        }
    }

    fun logout() {
        updateSettings { it.copy(account = null) }
        skin = null
        screen = Screen.Login
    }

    fun reloadInstances() {
        instances = Instances.all()
    }

    fun openInstanceFolder() {
        val dir = selectedInstance.dir.apply { mkdirs() }
        File(dir, "mods").mkdirs()
        runCatching { Desktop.getDesktop().open(dir) }
    }

    fun cancelLaunch() {
        launchJob?.cancel()
    }

    // ---- Локальный сервер сборки ----

    fun acceptEula() = updateSettings { it.copy(eulaAccepted = true) }

    /** Сервер запущен и принимает команды. */
    val serverAcceptsCommands get() = server is ServerState.Running && serverHandle != null

    /**
     * Отправляет команду в консоль сервера. Эхо пишем сами: сервер печатает только
     * результат, и без него непонятно, что именно было введено.
     */
    fun sendServerCommand(line: String) {
        val command = line.trim().removePrefix("/")
        if (command.isEmpty()) return
        val handle = serverHandle ?: return
        serverLines += "> $command"
        scope.launch(Dispatchers.IO) { handle.command(command) }
        if (command == "stop") server = ServerState.Stopping
    }

    /** Сервер запускается, работает или упал — на главной вместо «Сервер» кнопка «Управление». */
    val serverActive get() = server !is ServerState.Idle

    /**
     * Процесса сервера нет: можно трогать его файлы. Пока сервер жив, он держит списки
     * и настройки в памяти и перезапишет всё, что поменять на диске.
     */
    val serverStopped get() = serverHandle == null && server !is ServerState.Preparing && server !is ServerState.Stopping

    private fun serverIdle() {
        server = ServerState.Idle
        onlinePlayers.clear()
    }

    fun toggleServer() {
        when (server) {
            is ServerState.Running -> stopServer()
            is ServerState.Preparing -> serverJob?.cancel()
            else -> startServer()
        }
    }

    private fun stopServer() {
        val handle = serverHandle ?: run { serverIdle(); return }
        server = ServerState.Stopping
        scope.launch(Dispatchers.IO) { handle.stop() }
    }

    private fun startServer() {
        if (!settings.eulaAccepted) {
            tab = Tab.Server
            server = ServerState.Failed("Нужно принять EULA Minecraft")
            return
        }
        serverLines.clear()
        onlinePlayers.clear()
        serverNotice = null
        tab = Tab.Server
        serverJob = scope.launch {
            try {
                val progress = mint.game.ProgressSink { stage, fraction ->
                    scope.launch(Dispatchers.Main) { server = ServerState.Preparing(stage, fraction) }
                }
                server = ServerState.Preparing("Подготовка сервера", null)
                val java = GameLauncher.resolveJava(settings, progress)
                // Клиентская часть сборки нужна серверу целиком: моды и конфиги берутся из неё
                val (instance, _) = GameLauncher.prepare(selectedInstance, java, progress)
                withContext(Dispatchers.Main) { reloadInstances() }
                mint.game.ServerLauncher.prepare(instance, java, settings, progress)

                progress.report("Запуск сервера", null)
                serverHandle = mint.game.ServerLauncher.launch(
                    instance, java,
                    onLine = { line ->
                        scope.launch(Dispatchers.Main) {
                            serverLines += line
                            if (serverLines.size > 2000) serverLines.removeRange(0, serverLines.size - 2000)

                            // Падение видно по отчёту о крахе; дожидаться выхода процесса нельзя —
                            // после краха он может зависнуть, и сервер так и останется «работающим»
                            if (mint.game.ServerLauncher.isCrash(line)) {
                                if (server !is ServerState.Failed) {
                                    server = ServerState.Failed(
                                        "Сервер упал. Отчёт: instances/${selectedInstance.id}/server/crash-reports, лог: data/logs/server-latest.log"
                                    )
                                    serverHandle?.let { handle -> scope.launch(Dispatchers.IO) { handle.stop() } }
                                }
                                return@launch
                            }

                            trackPlayers(line)

                            val current = server as? ServerState.Running ?: return@launch
                            if (!current.ready && mint.game.ServerLauncher.isReady(line)) {
                                server = ServerState.Running(ready = true)
                                loadPublicAddress()
                                refreshServerData()
                            }
                        }
                    },
                    onExit = { code ->
                        scope.launch(Dispatchers.Main) {
                            serverHandle = null
                            onlinePlayers.clear()
                            refreshServerData()
                            when {
                                // Про падение уже сказали подробнее — не перетираем сообщение кодом выхода
                                server is ServerState.Failed -> {}
                                code == 0 -> serverIdle()
                                else -> server = ServerState.Failed("Сервер завершился с кодом $code. Лог: data/logs/server-latest.log")
                            }
                        }
                    },
                )
                withContext(Dispatchers.Main) { server = ServerState.Running(ready = false) }
            } catch (e: CancellationException) {
                serverIdle()
            } catch (e: Exception) {
                e.printStackTrace()
                server = ServerState.Failed(e.message ?: e.toString())
            }
        }
    }

    // ---- Игроки и обслуживание сервера ----

    private val loginLine = Regex("""UUID of player ([A-Za-z0-9_]{1,16}) is ([0-9a-fA-F-]{32,36})""")

    // Перед ником — только скобки лога: так «<Ник> Bob joined the game» в чате не считается входом
    private val joinLine = Regex("""^(?:\[[^\]]*] ?)+: ([A-Za-z0-9_]{1,16}) joined the game$""")
    private val leaveLine = Regex("""^(?:\[[^\]]*] ?)+: ([A-Za-z0-9_]{1,16}) left the game$""")

    private fun trackPlayers(line: String) {
        loginLine.find(line)?.let { loginUuids[it.groupValues[1]] = it.groupValues[2] }
        joinLine.find(line)?.let {
            val name = it.groupValues[1]
            if (name !in onlinePlayers) onlinePlayers += name
            refreshServerData(delayMs = 1500)
        }
        leaveLine.find(line)?.let { onlinePlayers -= it.groupValues[1] }
    }

    /**
     * Все игроки для списка: известные серверу плюс только что вошедшие,
     * которых он ещё не успел записать в usercache.json.
     */
    val playerList: List<ServerPlayer>
        get() {
            val known = serverPlayers
            val fresh = onlinePlayers
                .filter { name -> known.none { it.name.equals(name, ignoreCase = true) } }
                .map { ServerPlayer(it, loginUuids[it].orEmpty(), op = false, whitelisted = false, banned = false) }
            return known + fresh
        }

    fun isOnline(player: ServerPlayer) = onlinePlayers.any { it.equals(player.name, ignoreCase = true) }

    /** Перечитывает списки игроков и server.properties; [delayMs] — дать серверу дописать файл. */
    fun refreshServerData(delayMs: Long = 0) {
        val instance = selectedInstance
        scope.launch(Dispatchers.IO) {
            if (delayMs > 0) delay(delayMs)
            val players = ServerAdmin.players(instance)
            val properties = ServerAdmin.properties(instance)
            withContext(Dispatchers.Main) {
                if (selectedInstance.id != instance.id) return@withContext
                serverPlayers = players
                serverProperties = properties
            }
        }
    }

    /**
     * Скин для головы в списке. Сервер пускает игроков через тот же сервер входа,
     * что и аккаунт хоста; у офлайн-сервера скинов нет — у всех Стив.
     */
    fun loadPlayerSkin(uuid: String) {
        val key = uuid.replace("-", "").lowercase()
        if (key.isEmpty() || key in playerSkins) return
        playerSkins[key] = null
        val acc = account
        val authServer = if (acc?.type == AccountType.YGGDRASIL) acc.authServer else null
        val minecraft = selectedInstance.minecraft
        scope.launch { playerSkins[key] = Skins.player(key, authServer, minecraft) }
    }

    fun playerSkin(uuid: String) = playerSkins[uuid.replace("-", "").lowercase()]

    private fun loadPublicAddress() {
        if (publicAddress != null) return
        val instance = selectedInstance
        scope.launch {
            val ip = runCatching { Http.getString("https://api.ipify.org").trim() }.getOrNull()
                ?.takeIf { Regex("""[0-9a-fA-F.:]+""").matches(it) } ?: return@launch
            publicAddress = "$ip:${ServerLauncher.port(instance)}"
        }
    }

    /**
     * Действие над игроком: у живого сервера — командой (он сам запишет файл),
     * у выключенного — правкой json-файла.
     */
    private fun playerAction(command: String, offline: () -> Unit) {
        when {
            serverAcceptsCommands -> {
                sendServerCommand(command)
                refreshServerData(delayMs = 1000)
            }
            serverStopped -> scope.launch(Dispatchers.IO) {
                runCatching(offline).onFailure {
                    withContext(Dispatchers.Main) { serverNotice = ServerNotice(it.message ?: it.toString(), error = true) }
                }
                refreshServerData()
            }
            else -> serverNotice = ServerNotice("Подождите, пока сервер запустится или выключится", error = true)
        }
    }

    fun setOp(player: ServerPlayer, op: Boolean) {
        val instance = selectedInstance
        playerAction(if (op) "op ${player.name}" else "deop ${player.name}") { ServerAdmin.setOp(instance, player, op) }
    }

    fun setWhitelisted(player: ServerPlayer, on: Boolean) {
        val instance = selectedInstance
        playerAction("whitelist ${if (on) "add" else "remove"} ${player.name}") { ServerAdmin.setWhitelisted(instance, player, on) }
    }

    fun setBanned(player: ServerPlayer, banned: Boolean) {
        val instance = selectedInstance
        playerAction(if (banned) "ban ${player.name}" else "pardon ${player.name}") { ServerAdmin.setBanned(instance, player, banned) }
    }

    fun kick(player: ServerPlayer) {
        if (serverAcceptsCommands) sendServerCommand("kick ${player.name}")
    }

    /** Новый ник без UUID выключенный сервер не узнает — добавлять можно только в работающий. */
    fun addPlayer(name: String, op: Boolean): Boolean {
        val nick = name.trim()
        if (!Regex("""[A-Za-z0-9_]{1,16}""").matches(nick) || !serverAcceptsCommands) return false
        sendServerCommand(if (op) "op $nick" else "whitelist add $nick")
        refreshServerData(delayMs = 1000)
        return true
    }

    val whitelistEnabled get() = serverProperties["white-list"] == "true"

    fun setWhitelistEnabled(on: Boolean) {
        val instance = selectedInstance
        playerAction("whitelist ${if (on) "on" else "off"}") {
            ServerAdmin.setProperties(instance, mapOf("white-list" to on.toString()))
        }
    }

    /** Правка server.properties — только у выключенного сервера, иначе он перезапишет файл своим. */
    fun saveServerProperties(values: Map<String, String>) {
        val instance = selectedInstance
        serverTask("Сохранение настроек") {
            ServerAdmin.setProperties(instance, values)
            "Настройки сохранены — применятся при запуске сервера"
        }
    }

    fun backupWorld() {
        val instance = selectedInstance
        serverTask("Резервная копия мира") {
            val file = ServerAdmin.backupWorld(instance)
            "Копия мира сохранена: ${file.name}"
        }
    }

    /** Удаляет мир и сразу запускает сервер: новый мир создаётся уже с пресетом генерации сборки. */
    fun resetWorld(seed: String, backup: Boolean) {
        val instance = selectedInstance
        serverTask("Пересоздание мира", then = {
            serverSection = ServerSection.Console
            startServer()
        }) {
            if (backup && ServerAdmin.worldDir(instance).isDirectory) {
                withContext(Dispatchers.Main) { serverTask = "Резервная копия мира" }
                ServerAdmin.backupWorld(instance)
            }
            withContext(Dispatchers.Main) { serverTask = "Удаление мира" }
            ServerAdmin.resetWorld(instance, seed)
            "Старый мир удалён, создаётся новый"
        }
    }

    /** Стирает сервер целиком; следующий запуск поставит его с нуля. */
    fun deleteServer(backup: Boolean) {
        val instance = selectedInstance
        serverTask("Удаление сервера", then = { server = ServerState.Idle }) {
            if (backup && ServerAdmin.worldDir(instance).isDirectory) {
                withContext(Dispatchers.Main) { serverTask = "Резервная копия мира" }
                ServerAdmin.backupWorld(instance)
            }
            withContext(Dispatchers.Main) { serverTask = "Удаление сервера" }
            ServerAdmin.deleteServer(instance)
            if (backup) "Сервер удалён. Копия мира — в папке server-backups" else "Сервер удалён"
        }
    }

    fun openServerFolder() = openFolder(ServerLauncher.dir(selectedInstance))

    fun openBackupsFolder() = openFolder(ServerAdmin.backupsDir(selectedInstance))

    private fun openFolder(dir: File) {
        dir.mkdirs()
        runCatching { Desktop.getDesktop().open(dir) }
    }

    /** Долгая операция над файлами выключенного сервера: ход и итог видны на вкладке. */
    private fun serverTask(label: String, then: (() -> Unit)? = null, block: suspend () -> String) {
        if (!serverStopped || serverTask != null) return
        serverTask = label
        serverNotice = null
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            serverTask = null
            refreshServerData()
            result
                .onSuccess {
                    serverNotice = ServerNotice(it)
                    then?.invoke()
                }
                .onFailure { serverNotice = ServerNotice(it.message ?: it.toString(), error = true) }
        }
    }

    fun play() {
        val acc = account ?: run { screen = Screen.Login; return }
        if (launch is LaunchState.Preparing || launch is LaunchState.Running) return
        launchJob = scope.launch {
            try {
                val progress = mint.game.ProgressSink { stage, fraction ->
                    scope.launch(Dispatchers.Main) { launch = LaunchState.Preparing(stage, fraction) }
                }
                launch = LaunchState.Preparing("Подготовка", null)
                val java = GameLauncher.resolveJava(settings, progress)
                withContext(Dispatchers.Main) { refreshJavaInfo() }
                val (instance, version) = GameLauncher.prepare(selectedInstance, java, progress)
                withContext(Dispatchers.Main) { reloadInstances() }

                progress.report("Вход в аккаунт", null)
                val freshAccount = Auth.refreshIfNeeded(acc)
                if (freshAccount != acc) withContext(Dispatchers.Main) { updateSettings { it.copy(account = freshAccount) } }

                progress.report("Запуск игры", null)
                GameLauncher.launch(
                    instance, version, java, freshAccount, settings,
                    // Вывод игры пишется в data/logs/game-latest.log; своя консоль появится отдельно
                    onLine = {},
                    onExit = { code ->
                        scope.launch(Dispatchers.Main) {
                            onHideWindow(false)
                            launch = if (code == 0) LaunchState.Idle
                            else LaunchState.Failed("Игра завершилась с кодом $code. Лог: data/logs/game-latest.log")
                        }
                    },
                )
                withContext(Dispatchers.Main) {
                    launch = LaunchState.Running
                    if (settings.closeOnLaunch) onHideWindow(true)
                }
            } catch (e: CancellationException) {
                launch = LaunchState.Idle
            } catch (e: AuthException) {
                launch = LaunchState.Failed(e.message ?: "Ошибка авторизации")
            } catch (e: Exception) {
                e.printStackTrace()
                launch = LaunchState.Failed(e.message ?: e.toString())
            }
        }
    }
}
