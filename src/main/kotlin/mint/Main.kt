package mint

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import mint.ui.AppState
import mint.ui.LoginScreen
import mint.ui.MainScreen
import mint.ui.MintColors
import mint.ui.Screen
import mint.ui.WindowActions
import java.awt.Dimension

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--headless") return headless(args.getOrElse(1) { "Steve" })
    if (args.firstOrNull() == "--pack-manifest") return packManifest(args.getOrElse(1) { "createmint" })
    if (args.firstOrNull() == "--pack-sync") return packSync(args.getOrElse(1) { "createmint" })
    if (args.firstOrNull() == "--server") return server(args.getOrElse(1) { "createmint" })
    gui()
}

/** Служебный режим: установка и запуск сборки без UI, вывод в консоль. */
private fun headless(nick: String) = kotlinx.coroutines.runBlocking {
    val settings = mint.core.SettingsStore.load()
    val progress = mint.game.ProgressSink { stage, f -> println("[mint] $stage ${f?.let { "%.0f%%".format(it * 100) } ?: ""}") }
    val java = mint.game.GameLauncher.resolveJava(settings, progress)
    println("[mint] ${java.label} -> ${java.executable}")
    val (instance, version) = mint.game.GameLauncher.prepare(mint.game.Instances.all().first(), java, progress)
    val done = kotlinx.coroutines.CompletableDeferred<Int>()
    mint.game.GameLauncher.launch(
        instance, version, java, mint.auth.Auth.offline(nick), settings,
        onLine = { println("[game] $it") }, onExit = { done.complete(it) },
    )
    println("[mint] game exited with ${done.await()}")
}

/** Для разработчика сборки: обновить mint-pack.json и .gitignore в папке сборки. */
private fun packManifest(id: String) = kotlinx.coroutines.runBlocking {
    val instance = mint.game.Instances.all().firstOrNull { it.id == id }
        ?: return@runBlocking println("[mint] сборка «$id» не найдена в ${mint.core.MintPaths.instances}")
    val missing = mint.game.Packs.writeManifest(instance)
    println("[mint] ${java.io.File(instance.dir, mint.game.Packs.MANIFEST)} обновлён")
    if (missing.isNotEmpty()) {
        println("[mint] Не найдены на Modrinth — будут лежать в git (или впишите url в манифест вручную):")
        missing.forEach { println("  $it") }
    }
}

/** Скачать/обновить сборку из её GitHub-релиза так же, как при нажатии «Играть». */
private fun packSync(id: String) = kotlinx.coroutines.runBlocking {
    val instance = mint.game.Instances.all().firstOrNull { it.id == id }
        ?: return@runBlocking println("[mint] сборка «$id» не найдена в ${mint.core.MintPaths.instances}")
    val progress = mint.game.ProgressSink { stage, f -> println("[mint] $stage ${f?.let { "%.0f%%".format(it * 100) } ?: ""}") }
    if (mint.game.Packs.isDevCopy(instance)) println("[mint] в папке сборки есть .git — это рабочая копия, синхронизация пропущена")
    val synced = mint.game.Packs.sync(instance, progress)
    println("[mint] ${synced.name} · ${synced.minecraft} · ${synced.dir}")
}

/** Служебный режим: поднять локальный сервер сборки без UI. */
private fun server(id: String) = kotlinx.coroutines.runBlocking {
    val settings = mint.core.SettingsStore.load()
    val instance = mint.game.Instances.all().firstOrNull { it.id == id }
        ?: return@runBlocking println("[mint] сборка «$id» не найдена в ${mint.core.MintPaths.instances}")
    val progress = mint.game.ProgressSink { stage, f -> println("[mint] $stage ${f?.let { "%.0f%%".format(it * 100) } ?: ""}") }
    val java = mint.game.GameLauncher.resolveJava(settings, progress)
    val (synced, _) = mint.game.GameLauncher.prepare(instance, java, progress)
    try {
        mint.game.ServerLauncher.prepare(synced, java, settings, progress)
    } catch (e: java.io.IOException) {
        return@runBlocking println("[mint] ${e.message}")
    }
    val done = kotlinx.coroutines.CompletableDeferred<Int>()
    val handle = mint.game.ServerLauncher.launch(
        synced, java,
        onLine = { line ->
            println("[server] $line")
            if (mint.game.ServerLauncher.isTunnelUp(line)) {
                println("[mint] туннель поднят: друзьям зайти на ${mint.game.ServerLauncher.TUNNEL_HUB} и выбрать сервер по названию")
            }
        },
        onExit = { done.complete(it) },
    )
    // Консоль лаунчера становится консолью сервера: stop завершает его штатно
    kotlin.concurrent.thread(isDaemon = true) {
        generateSequence(::readLine).forEach { handle.command(it) }
    }
    println("[mint] сервер завершился с кодом ${done.await()}")
}

private fun gui() = application {
    val windowState = rememberWindowState(
        size = DpSize(1120.dp, 700.dp),
        position = WindowPosition.PlatformDefault,
    )
    var visible by remember { mutableStateOf(true) }

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        visible = visible,
        title = "Mint",
        undecorated = true,
        transparent = true,
        resizable = true,
    ) {
        window.minimumSize = Dimension(960, 620)
        val scope = rememberCoroutineScope()
        val app = remember { AppState(scope) { hide -> visible = !hide } }
        DisposableEffect(window) {
            // Пользователь мог сменить тему Windows/macOS, пока лаунчер был в фоне
            val listener = object : java.awt.event.WindowAdapter() {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent) {
                    if (app.settings.theme == mint.core.Theme.SYSTEM) app.applyTheme()
                }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        val actions = remember {
            WindowActions(windowState, minimize = { windowState.isMinimized = true }, close = ::exitApplication)
        }
        val maximized = windowState.placement == WindowPlacement.Maximized
        val shape = RoundedCornerShape(if (maximized) 0.dp else 16.dp)

        Box(
            Modifier.fillMaxSize()
                .clip(shape)
                .background(MintColors.Window)
                .border(1.dp, MintColors.ink(0.08f), shape)
        ) {
            when (app.screen) {
                Screen.Login -> LoginScreen(app, actions)
                Screen.Main -> MainScreen(app, actions)
            }
        }
    }
}
