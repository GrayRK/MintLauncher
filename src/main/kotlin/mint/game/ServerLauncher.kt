package mint.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mint.auth.AuthlibInjector
import mint.core.AccountType
import mint.core.DownloadTask
import mint.core.Http
import mint.core.LauncherSettings
import mint.core.MintJson
import mint.core.MintPaths
import mint.core.sha1
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
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

    /** Что из конфигов сборки лаунчер положил на сервер: путь → sha1. */
    private const val CONFIG_STATE = ".mint-configs.json"

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

        // Сервер логинит игроков через Ely.by: без этого у профилей нет текстур и все ходят Стивами
        val agent = if (settings.account?.type == AccountType.YGGDRASIL) {
            progress.report("Проверка входа Ely.by", null)
            AuthlibInjector.ensure()
            AuthlibInjector.agentArg(settings.account)
        } else null

        withContext(Dispatchers.IO) {
            writeEula(root)
            writeProperties(root, instance)
            enforceAuthMode(root, agent != null)
            writeJvmArgs(root, settings, agent)
            copyConfigs(instance, root)
            seedWorldDatapacks(instance, root)
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
            # online-mode и enforce-secure-profile выставляет лаунчер при каждом запуске
            online-mode=false
            enforce-secure-profile=false
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

    private fun writeJvmArgs(root: File, settings: LauncherSettings, agent: String?) {
        val memory = settings.serverMemoryMb
        File(root, "user_jvm_args.txt").writeText(
            """
            # Создано лаунчером Mint — правится в настройках лаунчера
            -Xms1024M
            -Xmx${memory}M
            -XX:+UseG1GC
            -XX:+UnlockExperimentalVMOptions
            -XX:MaxGCPauseMillis=50
            """.trimIndent() + "\n" + (agent?.let { "$it\n" } ?: "")
        )
    }

    /**
     * Скины берутся из профиля игрока, а профиль сервер получает только в онлайн-режиме.
     * Поэтому при входе через Ely.by поднимаем online-mode и цепляем тот же authlib-injector,
     * что и у игры; с офлайн-аккаунтом оставляем офлайн — иначе на сервер не пустит никого.
     *
     * enforce-secure-profile выключен всегда: Ely.by не выдаёт подписи ключей Mojang,
     * и с проверкой сервер выкидывает игроков при входе.
     *
     * Эти два ключа — забота лаунчера, остальной server.properties остаётся файлом игрока.
     */
    private fun enforceAuthMode(root: File, online: Boolean) {
        val file = File(root, "server.properties")
        if (!file.isFile) return
        ServerAdmin.setProperties(file, mapOf("online-mode" to online.toString(), "enforce-secure-profile" to "false"))
    }

    /**
     * Датапаки сборки (пресет генерации ReTerraForged) кладутся внутрь мира — только оттуда
     * сервер их читает. Работает это лишь при создании мира: у готового мира генератор уже
     * записан в level.dat, и подкладывать пресет поздно.
     */
    private fun seedWorldDatapacks(instance: Instance, root: File) {
        val packs = packDatapacks(instance)
        if (packs.isEmpty()) return
        val world = File(root, levelName(root))
        if (world.isDirectory) return
        val target = File(world, "datapacks").apply { mkdirs() }
        packs.forEach { it.copyTo(File(target, it.name), overwrite = true) }
    }

    private fun packDatapacks(instance: Instance) =
        File(instance.dir, "datapacks").listFiles { f -> f.isFile && f.extension == "zip" }.orEmpty().toList()

    /** Мир уже есть, но создан без пресета генерации — рельеф в нём ванильный. */
    fun worldMissesDatapacks(instance: Instance): Boolean {
        val packs = packDatapacks(instance)
        if (packs.isEmpty()) return false
        val root = dir(instance)
        val world = File(root, levelName(root))
        if (!world.isDirectory) return false
        return packs.any { !File(world, "datapacks/${it.name}").isFile }
    }

    private fun levelName(root: File): String {
        val file = File(root, "server.properties")
        if (!file.isFile) return "world"
        return file.readLines().firstOrNull { it.startsWith("level-name=") }
            ?.substringAfter('=')?.trim()?.ifBlank { null }
            ?: "world"
    }

    /**
     * Конфиги сборки общие для клиента и сервера: серверу нужны те же настройки Create,
     * генерации мира и Distant Horizons.
     *
     * Правки игрока на сервере сохраняются — но только пока он их действительно делал.
     * Что положил лаунчер, то он и обновляет: иначе обновление сборки никогда не доезжает
     * до уже установленного сервера (так на нём остался старый Terralith и сломал генерацию).
     */
    private fun copyConfigs(instance: Instance, root: File) {
        val stateFile = File(root, CONFIG_STATE)
        val copied = runCatching {
            MintJson.decodeFromString<Map<String, String>>(stateFile.readText())
        }.getOrElse { emptyMap() }
        val next = linkedMapOf<String, String>()

        for (name in listOf("config", "defaultconfigs", "kubejs")) {
            val src = File(instance.dir, name)
            if (!src.isDirectory) continue
            src.walkTopDown().filter { it.isFile }.forEach { file ->
                val path = "$name/${file.relativeTo(src).invariantSeparatorsPath}"
                val target = File(root, path)
                val hash = sha1(file)
                next[path] = hash
                when {
                    !target.isFile -> {}
                    // Файл сборки, а не игрока: содержимое сборки он править не должен
                    packOwned(path) -> if (sha1(target) == hash) return@forEach
                    // Игрок правил его сам — не трогаем
                    sha1(target) != copied[path] -> return@forEach
                    else -> if (sha1(target) == hash) return@forEach
                }
                target.parentFile.mkdirs()
                file.copyTo(target, overwrite = true)
            }
        }
        // Файл ушёл из сборки — убираем и с сервера, иначе останется лежать рядом с заменой
        // и будет спорить с ней (так старый Terralith пережил бы обновление сборки).
        for ((path, hash) in copied) {
            if (path in next) continue
            val target = File(root, path)
            if (!target.isFile) continue
            // Игрок мог его править — тогда это уже его файл, не трогаем
            if (sha1(target) != hash) continue
            target.delete()
        }

        runCatching { stateFile.writeText(MintJson.encodeToString(next)) }
    }

    /** Что лаунчер обновляет всегда: это содержимое сборки, а не настройки игрока. */
    private fun packOwned(path: String) =
        path.startsWith("config/paxi/datapacks/") ||
            path.startsWith("config/paxi/resourcepacks/") ||
            path.startsWith("defaultconfigs/")

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
            thread(name = "mint-server-stop", isDaemon = true) { stopAndWait() }
        }

        /** Синхронная остановка: stop, до минуты на сохранение мира, затем принудительно. */
        internal fun stopAndWait() {
            if (!process.isAlive) return
            if (command("stop").isFailure) process.destroy()
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    /**
     * Процесс сервера не привязан к лаунчеру: Windows не завершает дочерние процессы
     * вместе с родителем. Без этого закрытый лаунчер оставлял сервер-сироту, который
     * держал порт и мир, а управлять им было уже нечем.
     */
    private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<Handle>()

    private val shutdownHook by lazy {
        Runtime.getRuntime().addShutdownHook(thread(start = false, name = "mint-server-shutdown") {
            // Останавливаем параллельно, чтобы выход не ждал минуту на каждый сервер
            running.map { thread(name = "mint-server-shutdown-one") { it.stopAndWait() } }.forEach { it.join() }
        })
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

        // Выход сообщаем ровно один раз, кто бы ни заметил его первым
        val exitReported = AtomicBoolean(false)
        val fireExit = { code: Int -> if (exitReported.compareAndSet(false, true)) onExit(code) }

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
            fireExit(process.waitFor())
        }

        // Поток вывода закрывается не всегда: после падения сервер может зависнуть
        // на своих не-демонах (потоки DH, нативный Rapier у Sable), и тогда лаунчер
        // так и будет показывать сервер работающим. Ждём сам процесс отдельно.
        val handle = Handle(process)
        shutdownHook
        running += handle
        thread(name = "mint-server-watchdog", isDaemon = true) {
            val code = process.waitFor()
            running -= handle
            fireExit(code)
        }
        handle
    }

    /**
     * Адрес сервера в локальной сети: по нему заходят с других машин дома.
     * Снаружи сервер пока недоступен — способ пускать друзей из интернета ещё выбирается.
     */
    fun lanAddress(instance: Instance): String {
        val host = runCatching {
            java.net.DatagramSocket().use { probe ->
                // Ядро само выберет сетевой интерфейс с маршрутом наружу; пакетов не шлём
                probe.connect(java.net.InetAddress.getByName("192.168.0.1"), 9)
                probe.localAddress.hostAddress
            }
        }.getOrNull()?.takeIf { it != "0.0.0.0" } ?: "localhost"
        return "$host:${port(instance)}"
    }

    /** Сервер закончил загрузку мира. */
    fun isReady(line: String) = Regex("""Done \([^)]*\)! For help""").containsMatchIn(line)

    /**
     * Сервер упал. Ждать выхода процесса нельзя: после отчёта о падении JVM может
     * зависнуть на чужих потоках, и лаунчер будет показывать живой сервер.
     */
    fun isCrash(line: String) =
        line.contains("Preparing crash report", ignoreCase = true) ||
            line.contains("Failed to start the minecraft server", ignoreCase = true) ||
            line.contains("This crash report has been saved to", ignoreCase = true)
}
