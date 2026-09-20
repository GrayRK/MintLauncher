package mint.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mint.auth.AuthlibInjector
import mint.core.Account
import mint.core.AccountType
import mint.core.LauncherSettings
import mint.core.MintPaths
import java.io.File
import java.io.FileWriter
import java.io.IOException
import kotlin.concurrent.thread

object GameLauncher {
    const val LAUNCHER_NAME = "Mint"
    const val LAUNCHER_VERSION = "0.2.3"

    suspend fun resolveJava(settings: LauncherSettings, progress: ProgressSink): JavaInfo =
        if (settings.javaAuto) {
            JavaRuntime.ensureManaged(progress)
        } else {
            JavaRuntime.inspect(File(settings.javaPath))
                ?: throw IOException("Java не найдена по пути «${settings.javaPath}»")
        }

    /**
     * Проверяет и докачивает всё необходимое для сборки.
     * Возвращает сборку (с зафиксированной версией загрузчика) и разрешённую версию.
     */
    suspend fun prepare(
        source: Instance,
        java: JavaInfo,
        progress: ProgressSink,
    ): Pair<Instance, ResolvedVersion> {
        var instance = Packs.sync(source, progress)
        if (instance.loader == Loader.NEOFORGE) {
            if (instance.loaderVersion.isBlank()) {
                progress.report("Поиск последней NeoForge", null)
                instance = instance.copy(loaderVersion = NeoForgeInstaller.latestFor(instance.minecraft))
                Instances.save(instance)
            }
            NeoForgeInstaller.install(instance.minecraft, instance.loaderVersion, java, progress)
        }
        VanillaInstaller.ensureVersionJson(instance.minecraft, progress)
        val version = withContext(Dispatchers.IO) { Versions.resolve(instance.versionId) }
        VanillaInstaller.ensureFiles(version, progress)
        return instance to version
    }

    suspend fun launch(
        instance: Instance,
        version: ResolvedVersion,
        java: JavaInfo,
        account: Account,
        settings: LauncherSettings,
        onLine: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): Process = withContext(Dispatchers.IO) {
        if (account.type == AccountType.YGGDRASIL) AuthlibInjector.ensure()

        instance.dir.mkdirs()
        instance.modsDir.mkdirs()
        val natives = File(MintPaths.versions, "${version.id}/natives").apply { mkdirs() }

        val classpath = buildList {
            version.libraries.forEach { add(it.file.absolutePath) }
            if (!version.isModded) add(Versions.clientJar(version.baseId).absolutePath)
        }.distinct().joinToString(File.pathSeparator)

        val vars = mapOf(
            "auth_player_name" to account.username,
            "version_name" to version.id,
            "game_directory" to instance.dir.absolutePath,
            "assets_root" to MintPaths.assets.absolutePath,
            "assets_index_name" to version.assetIndex.str("id")!!,
            "auth_uuid" to account.uuid,
            "auth_access_token" to account.accessToken,
            "clientid" to "",
            "auth_xuid" to "",
            "user_type" to if (account.type == AccountType.YGGDRASIL) "mojang" else "legacy",
            "version_type" to "release",
            "natives_directory" to natives.absolutePath,
            "launcher_name" to LAUNCHER_NAME,
            "launcher_version" to LAUNCHER_VERSION,
            "classpath" to classpath,
            "classpath_separator" to File.pathSeparator,
            "library_directory" to MintPaths.libraries.absolutePath,
        )

        val memory = if (instance.memoryMb > 0) instance.memoryMb else settings.memoryMb
        val jvm = buildList {
            if (account.type == AccountType.YGGDRASIL) add(AuthlibInjector.agentArg(account))
            add("-Xms512M")
            add("-Xmx${memory}M")
            add("-XX:+UseG1GC")
            add("-XX:+UnlockExperimentalVMOptions")
            add("-XX:G1NewSizePercent=20")
            add("-XX:G1ReservePercent=20")
            add("-XX:MaxGCPauseMillis=50")
            VanillaInstaller.loggingConfigFile(version)?.takeIf { it.isFile }?.let { cfg ->
                version.logging?.str("argument")?.let { add(it.replace("\${path}", cfg.absolutePath)) }
            }
            addAll(expandArgs(version.jvmArgs, vars))
        }
        val game = expandArgs(version.gameArgs, vars)

        val javaw = File(java.executable.parentFile, "javaw.exe").takeIf { it.isFile } ?: java.executable
        val command = listOf(javaw.absolutePath) + jvm + version.mainClass + game

        val log = File(MintPaths.logs, "game-latest.log").apply { parentFile.mkdirs() }
        log.writeText(
            "Mint $LAUNCHER_VERSION · ${version.id} · ${java.label}\n" +
                command.joinToString(" ") { if (it == account.accessToken && it != "0") "<token>" else it } + "\n\n"
        )

        val process = ProcessBuilder(command)
            .directory(instance.dir)
            .redirectErrorStream(true)
            .start()

        thread(name = "mint-game-output", isDaemon = true) {
            FileWriter(log, true).buffered().use { writer ->
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        writer.appendLine(line)
                        writer.flush()
                        onLine(line)
                    }
                }
            }
            onExit(process.waitFor())
        }
        process
    }
}
