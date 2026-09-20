package mint.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mint.core.DownloadTask
import mint.core.Http
import mint.core.LauncherSettings
import mint.core.MintPaths
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.OutputStreamWriter
import kotlin.concurrent.thread

/**
 * Локальный сервер для сборки: instances/<id>/server.
 *
 * Мир и конфиги сервера живут отдельно от клиентской папки, а моды переиспользуются —
 * в server/mods попадает только то, что помечено в mint-pack.json как BOTH или SERVER.
 * NeoForge ставится официальным инсталлером (--installServer): свой код процессоров
 * здесь не нужен, серверная установка самодостаточна.
 */
object ServerLauncher {
    /** Порт по умолчанию; меняется в server/server.properties. */
    const val DEFAULT_PORT = 25565

    fun dir(instance: Instance) = File(instance.dir, "server")

    fun modsDir(instance: Instance) = File(dir(instance), "mods")

    private fun markerFile(instance: Instance) = File(dir(instance), ".mint-server")

    fun isInstalled(instance: Instance) =
        markerFile(instance).takeIf { it.isFile }?.readText()?.trim() == instance.loaderVersion &&
            argsFile(instance).isFile

    /** Файл с аргументами запуска, который создаёт инсталлер NeoForge. */
    private fun argsFile(instance: Instance): File {
        val base = File(dir(instance), "libraries/net/neoforged/neoforge/${instance.loaderVersion}")
        val windows = File(base, "win_args.txt")
        return if (windows.isFile || isWindows) windows else File(base, "unix_args.txt")
    }

    private val isWindows get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    /** Игру запускает javaw (без консольного окна), а серверу нужен обычный java. */
    private fun console(java: JavaInfo): File =
        File(java.executable.parentFile, "java.exe").takeIf { it.isFile } ?: java.executable

    /** Порт из server.properties; файла ещё нет — значит будет [DEFAULT_PORT]. */
    fun port(instance: Instance): Int {
        val file = File(dir(instance), "server.properties")
        if (!file.isFile) return DEFAULT_PORT
        return file.readLines()
            .firstOrNull { it.startsWith("server-port=") }
            ?.substringAfter('=')?.trim()?.toIntOrNull()
            ?: DEFAULT_PORT
    }

    /**
     * Сервер, который не смог занять порт, падает с невнятным «Failed to initialize server».
     * Проверяем заранее — чаще всего это уже запущенный сервер этой же сборки.
     */
    private fun checkPortFree(port: Int) {
        val busy = runCatching { java.net.ServerSocket(port).close() }.isFailure
        if (busy) throw IOException("Порт $port уже занят — возможно, сервер этой сборки уже запущен")
    }

    /**
     * Готовит папку сервера: установка NeoForge, EULA, server.properties, моды и конфиги.
     * Клиентская часть сборки к этому моменту уже синхронизирована ([GameLauncher.prepare]).
     */
    suspend fun prepare(instance: Instance, java: JavaInfo, settings: LauncherSettings, progress: ProgressSink) {
        // Спрашиваем до установки: качать 200 МБ ради отказа на последнем шаге незачем
        if (!settings.eulaAccepted) throw IOException("Нужно принять EULA Minecraft — ссылка под кнопкой «Сервер»")
        val root = dir(instance).apply { mkdirs() }

        if (!isInstalled(instance)) {
            progress.report("Установка сервера NeoForge ${instance.loaderVersion}", null)
            installNeoForge(instance, java, root)
            markerFile(instance).writeText(instance.loaderVersion)
        }

        withContext(Dispatchers.IO) {
            writeEula(root)
            writeProperties(root, instance)
            writeJvmArgs(root, settings)
            copyConfigs(instance, root)
        }

        progress.report("Моды сервера", null)
        syncMods(instance, progress)
    }

    /** Инсталлер сам качает библиотеки и собирает server/libraries — нам остаётся проверить итог. */
    private suspend fun installNeoForge(instance: Instance, java: JavaInfo, root: File) {
        val installer = NeoForgeInstaller.installerJar(instance.loaderVersion, ProgressSink { _, _ -> })
        val log = File(MintPaths.logs, "server-install.log").apply { parentFile.mkdirs() }
        val code = withContext(Dispatchers.IO) {
            ProcessBuilder(console(java).absolutePath, "-jar", installer.absolutePath, "--installServer", root.absolutePath)
                .directory(root)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log))
                .start()
                .waitFor()
        }
        if (code != 0 || !argsFile(instance).isFile) {
            throw IOException("Не удалось установить сервер NeoForge (код $code). Лог: ${log.path}")
        }
    }

    /**
     * eula.txt пишется только после того, как игрок принял EULA в лаунчере:
     * согласие даёт человек, а не программа за него.
     */
    private fun writeEula(root: File) {
        File(root, "eula.txt").writeText("# https://aka.ms/MinecraftEULA\neula=true\n")
    }

    /** Создаётся один раз: дальше это файл игрока, лаунчер его не трогает. */
    private fun writeProperties(root: File, instance: Instance) {
        val file = File(root, "server.properties")
        if (file.isFile) return
        file.writeText(
            """
            # Создано лаунчером Mint для сборки ${instance.name}
            motd=${instance.name}
            server-port=$DEFAULT_PORT
            # Офлайн-режим: в сборку заходят и по офлайн-нику, и через Ely.by
            online-mode=false
            # Прогрузкой дальних чанков занимается Distant Horizons, ванильная дальность умеренная
            view-distance=12
            simulation-distance=8
            max-players=8
            difficulty=normal
            allow-flight=true
            spawn-protection=0
            sync-chunk-writes=false
            """.trimIndent() + "\n"
        )
    }

    private fun writeJvmArgs(root: File, settings: LauncherSettings) {
        val memory = settings.serverMemoryMb
        File(root, "user_jvm_args.txt").writeText(
            """
            # Создано лаунчером Mint — правится в настройках лаунчера
            -Xms1024M
            -Xmx${memory}M
            -XX:+UseG1GC
            -XX:+UnlockExperimentalVMOptions
            -XX:MaxGCPauseMillis=50
            """.trimIndent() + "\n"
        )
    }

    /**
     * Конфиги сборки общие для клиента и сервера: серверу нужны те же настройки Create,
     * генерации мира и Distant Horizons. Уже существующие файлы не трогаем — на сервере
     * их могли поправить отдельно.
     */
    private fun copyConfigs(instance: Instance, root: File) {
        for (name in listOf("config", "defaultconfigs", "datapacks", "kubejs")) {
            val src = File(instance.dir, name)
            if (!src.isDirectory) continue
            src.walkTopDown().filter { it.isFile }.forEach { file ->
                val target = File(root, "$name/${file.relativeTo(src).invariantSeparatorsPath}")
                if (target.isFile) return@forEach
                target.parentFile.mkdirs()
                file.copyTo(target, overwrite = true)
            }
        }
    }

    /**
     * Наполняет server/mods: клиентские моды на сервер не едут, серверные докачиваются.
     * Общие моды подшиваются жёсткой ссылкой — второй копии 1,5 ГБ на диске не появляется.
     */
    private suspend fun syncMods(instance: Instance, progress: ProgressSink) {
        val manifest = Packs.manifest(instance)
        val sideOf = manifest.files.associate { it.path.substringAfterLast('/') to it.side }
        val mods = modsDir(instance).apply { mkdirs() }

        // Без манифеста стороны неизвестны, и на сервер уедут клиентские моды — он упадёт
        // на первом же из них. Сказать об этом честно полезнее, чем молча собрать битый сервер.
        if (sideOf.isEmpty()) {
            progress.report("Внимание: в сборке нет mint-pack.json, моды не разделены на клиент и сервер", null)
        }

        val wanted = instance.modsDir.listFiles { f -> f.isFile && f.extension == "jar" }
            .orEmpty()
            .filter { sideOf[it.name] != PackSide.CLIENT }

        withContext(Dispatchers.IO) {
            val keep = wanted.map { it.name }.toMutableSet()
            manifest.files.filter { it.side == PackSide.SERVER }.forEach { keep += it.path.substringAfterLast('/') }

            // Лишнее (клиентские моды, остатки прошлых версий) с сервера убираем
            mods.listFiles { f -> f.isFile && f.extension == "jar" }.orEmpty()
                .filter { it.name !in keep }
                .forEach { it.delete() }

            for (mod in wanted) {
                val target = File(mods, mod.name)
                if (target.isFile && target.length() == mod.length()) continue
                target.delete()
                runCatching { java.nio.file.Files.createLink(target.toPath(), mod.toPath()) }
                    .onFailure { mod.copyTo(target, overwrite = true) }
            }
        }

        // Моды только для сервера (например, туннель) в клиентской папке отсутствуют
        val serverOnly = manifest.files.filter { it.side == PackSide.SERVER }
            .map { DownloadTask(it.url, File(instance.dir, it.path), it.sha1, it.size) }
        if (serverOnly.isNotEmpty()) {
            Http.downloadAll(serverOnly) { done, total ->
                progress.report("Моды сервера $done/$total", if (total == 0) 1f else done.toFloat() / total)
            }
        }
    }

    /** Запущенный сервер: процесс и корректная остановка командой stop. */
    class Handle(private val process: Process) {
        private val stdin = OutputStreamWriter(process.outputStream)

        val isAlive get() = process.isAlive

        fun command(line: String) = runCatching {
            stdin.write(line + "\n")
            stdin.flush()
        }

        /** Сервер должен сохранить мир сам — убивать процесс можно только если он завис. */
        fun stop() {
            if (command("stop").isFailure) process.destroy()
            thread(name = "mint-server-stop", isDaemon = true) {
                if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
    }

    suspend fun launch(
        instance: Instance,
        java: JavaInfo,
        onLine: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): Handle = withContext(Dispatchers.IO) {
        val root = dir(instance)
        val args = argsFile(instance)
        if (!args.isFile) throw IOException("Сервер не установлен: нет ${args.name}")

        checkPortFree(port(instance))

        val command = listOf(
            console(java).absolutePath,
            "@user_jvm_args.txt",
            "@${args.relativeTo(root).invariantSeparatorsPath}",
            "nogui",
        )

        val log = File(MintPaths.logs, "server-latest.log").apply { parentFile.mkdirs() }
        log.writeText("Mint ${GameLauncher.LAUNCHER_VERSION} · сервер ${instance.name} · ${java.label}\n" +
            command.joinToString(" ") + "\n\n")

        val process = ProcessBuilder(command)
            .directory(root)
            .redirectErrorStream(true)
            .start()

        thread(name = "mint-server-output", isDaemon = true) {
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
        Handle(process)
    }

    /**
     * ProximaTunnel не выдаёт отдельный адрес: друзья заходят на общий хаб и выбирают
     * сервер по MOTD. Поэтому ловим сам факт подключения туннеля.
     */
    const val TUNNEL_HUB = "proximamp.com"

    fun isTunnelUp(line: String) = line.contains("Connection to ProximaMP server established", ignoreCase = true)

    /** Сервер закончил загрузку мира. */
    fun isReady(line: String) = Regex("""Done \([^)]*\)! For help""").containsMatchIn(line)
}
