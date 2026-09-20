package mint.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mint.auth.Auth
import mint.auth.AuthException
import mint.auth.Skin
import mint.auth.Skins
import mint.core.Account
import mint.core.Hardware
import mint.core.HardwareInfo
import mint.core.LauncherSettings
import mint.core.SettingsStore
import mint.core.Theme
import mint.game.GameLauncher
import mint.game.Instance
import mint.game.Instances
import mint.game.JavaInfo
import mint.game.JavaRuntime
import java.awt.Desktop
import java.io.File

enum class Screen { Login, Main }

/** Вкладки боковой панели. */
enum class Tab { Home, Instances, Mods, Account, Settings }

sealed interface LaunchState {
    data object Idle : LaunchState
    data class Preparing(val stage: String, val fraction: Float?) : LaunchState
    data object Running : LaunchState
    data class Failed(val message: String) : LaunchState
}

sealed interface ServerState {
    data object Idle : ServerState
    data class Preparing(val stage: String, val fraction: Float?) : ServerState

    /** Сервер работает; [tunnel] — поднялся ли ProximaTunnel для друзей извне. */
    data class Running(val tunnel: Boolean, val ready: Boolean) : ServerState
    data object Stopping : ServerState
    data class Failed(val message: String) : ServerState
}

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
    val consoleLines = mutableStateListOf<String>()
    val serverLines = mutableStateListOf<String>()

    // Состояние экрана входа
    var loginBusy by mutableStateOf(false)
        private set
    var loginError by mutableStateOf<String?>(null)
    var needsTotp by mutableStateOf(false)

    private var launchJob: Job? = null
    private var serverJob: Job? = null
    private var serverHandle: mint.game.ServerLauncher.Handle? = null

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
    }

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

    fun toggleServer() {
        when (server) {
            is ServerState.Running -> stopServer()
            is ServerState.Preparing -> serverJob?.cancel()
            else -> startServer()
        }
    }

    private fun stopServer() {
        val handle = serverHandle ?: run { server = ServerState.Idle; return }
        server = ServerState.Stopping
        scope.launch(Dispatchers.IO) { handle.stop() }
    }

    private fun startServer() {
        if (!settings.eulaAccepted) {
            server = ServerState.Failed("Нужно принять EULA Minecraft")
            return
        }
        serverLines.clear()
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
                            val current = server as? ServerState.Running ?: return@launch
                            val tunnel = current.tunnel || mint.game.ServerLauncher.isTunnelUp(line)
                            val ready = current.ready || mint.game.ServerLauncher.isReady(line)
                            if (tunnel != current.tunnel || ready != current.ready) {
                                server = ServerState.Running(tunnel, ready)
                            }
                        }
                    },
                    onExit = { code ->
                        scope.launch(Dispatchers.Main) {
                            serverHandle = null
                            server = if (code == 0) ServerState.Idle
                            else ServerState.Failed("Сервер завершился с кодом $code. Лог: data/logs/server-latest.log")
                        }
                    },
                )
                withContext(Dispatchers.Main) { server = ServerState.Running(tunnel = false, ready = false) }
            } catch (e: CancellationException) {
                server = ServerState.Idle
            } catch (e: Exception) {
                e.printStackTrace()
                server = ServerState.Failed(e.message ?: e.toString())
            }
        }
    }

    fun play() {
        val acc = account ?: run { screen = Screen.Login; return }
        if (launch is LaunchState.Preparing || launch is LaunchState.Running) return
        consoleLines.clear()
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
                    onLine = { line ->
                        if (settings.showConsole) scope.launch(Dispatchers.Main) {
                            consoleLines += line
                            if (consoleLines.size > 2000) consoleLines.removeRange(0, consoleLines.size - 2000)
                        }
                    },
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
