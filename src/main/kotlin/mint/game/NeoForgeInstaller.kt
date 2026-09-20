package mint.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mint.core.DownloadTask
import mint.core.Http
import mint.core.MintJson
import mint.core.MintPaths
import java.io.File
import java.io.IOException
import java.util.jar.JarFile
import java.util.zip.ZipFile

/**
 * Самостоятельная установка NeoForge без запуска GUI-инсталлера:
 * читаем install_profile.json, качаем библиотеки и прогоняем процессоры (client-side).
 */
object NeoForgeInstaller {
    private const val MAVEN = "https://maven.neoforged.net/releases"

    /** Последняя стабильная NeoForge для версии Minecraft 1.X.Y → X.Y.* */
    suspend fun latestFor(minecraft: String): String {
        val prefix = minecraft.removePrefix("1.").let { if (it.count { c -> c == '.' } == 0) "$it.0" else it } + "."
        val xml = Http.getString("$MAVEN/net/neoforged/neoforge/maven-metadata.xml")
        return Regex("<version>([^<]+)</version>").findAll(xml)
            .map { it.groupValues[1] }
            .filter { it.startsWith(prefix) && !it.contains("beta") }
            .maxByOrNull { v -> v.split('.').map { it.toIntOrNull() ?: 0 }.fold(0L) { acc, n -> acc * 10_000 + n } }
            ?: throw IOException("Не найдена NeoForge для Minecraft $minecraft")
    }

    private fun markerFile(version: String) = File(MintPaths.versions, "neoforge-$version/.mint-installed")

    fun isInstalled(version: String) = markerFile(version).isFile && Versions.jsonFile("neoforge-$version").isFile

    /** Официальный инсталлер NeoForge: нужен и клиенту (процессоры), и серверу (--installServer). */
    suspend fun installerJar(version: String, progress: ProgressSink): File {
        val installer = File(MintPaths.cache, "neoforge-$version-installer.jar")
        if (installer.isFile) return installer
        progress.report("Загрузка NeoForge $version", null)
        Http.download(DownloadTask("$MAVEN/net/neoforged/neoforge/$version/neoforge-$version-installer.jar", installer))
        return installer
    }

    suspend fun install(minecraft: String, version: String, java: JavaInfo, progress: ProgressSink) {
        if (isInstalled(version)) return
        val installer = installerJar(version, progress)

        val (profile, versionJson) = withContext(Dispatchers.IO) {
            ZipFile(installer).use { zip ->
                fun read(name: String) = zip.getInputStream(zip.getEntry(name) ?: throw IOException("$name нет в инсталлере"))
                    .use { it.readBytes().decodeToString() }
                MintJson.parseToJsonElement(read("install_profile.json")).jsonObject to read("version.json")
            }
        }
        val versionId = profile.str("version")!!

        // 1. Ванильная база: json + client.jar
        VanillaInstaller.ensureVersionJson(minecraft, progress)
        val vanilla = Versions.resolve(minecraft)
        Http.download(VanillaInstaller.clientJarTask(vanilla))

        // 2. Библиотеки инсталлера (процессоры) и самой версии
        withContext(Dispatchers.IO) {
            Versions.jsonFile(versionId).apply { parentFile.mkdirs() }.writeText(versionJson)
        }
        val libs = profile.arr("libraries").map { Library.from(it.jsonObject) } +
            MintJson.parseToJsonElement(versionJson).jsonObject.arr("libraries").map { Library.from(it.jsonObject) }
        extractBundledMaven(installer, libs)
        Http.downloadAll(libs.mapNotNull { it.downloadTask() }) { done, total ->
            progress.report("Загрузка библиотек NeoForge · $done из $total", if (total == 0) 1f else done.toFloat() / total)
        }

        // 3. Процессоры
        val data = buildData(profile, installer, minecraft)
        val processors = profile.arr("processors").map { it.jsonObject }.filter { p ->
            val sides = p["sides"]?.let { s -> (s as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content } }
            sides == null || "client" in sides
        }
        val log = File(MintPaths.logs, "neoforge-install.log").apply { parentFile.mkdirs(); writeText("") }
        processors.forEachIndexed { i, p ->
            ensureActive()
            progress.report("Установка NeoForge · шаг ${i + 1} из ${processors.size}", i.toFloat() / processors.size)
            runProcessor(p, data, java, log)
        }

        val patched = data["PATCHED"]?.let { File(it) }
        if (patched != null && !patched.isFile) throw IOException("NeoForge не установлена: нет ${patched.name}. См. ${log.path}")
        markerFile(version).writeText(System.currentTimeMillis().toString())
    }

    private suspend fun ensureActive() = kotlin.coroutines.coroutineContext.ensureActive()

    /** Библиотеки без url лежат внутри инсталлера в каталоге maven/. */
    private suspend fun extractBundledMaven(installer: File, libs: List<Library>) = withContext(Dispatchers.IO) {
        val bundled = libs.filter { it.url.isNullOrBlank() && !it.file.isFile }
        if (bundled.isEmpty()) return@withContext
        ZipFile(installer).use { zip ->
            for (lib in bundled) {
                val entry = zip.getEntry("maven/${lib.path}") ?: continue
                lib.file.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> lib.file.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private suspend fun buildData(profile: JsonObject, installer: File, minecraft: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val data = mutableMapOf(
                "SIDE" to "client",
                "MINECRAFT_JAR" to Versions.clientJar(minecraft).absolutePath,
                "MINECRAFT_VERSION" to minecraft,
                "ROOT" to MintPaths.game.absolutePath,
                "INSTALLER" to installer.absolutePath,
                "LIBRARY_DIR" to MintPaths.libraries.absolutePath,
            )
            val extractDir = File(MintPaths.cache, "neoforge-data").apply { deleteRecursively(); mkdirs() }
            ZipFile(installer).use { zip ->
                profile.objOrNull("data")?.forEach { (key, value) ->
                    val raw = value.jsonObject.str("client") ?: return@forEach
                    data[key] = when {
                        raw.startsWith("[") && raw.endsWith("]") ->
                            MavenCoord.parse(raw.substring(1, raw.length - 1)).file().absolutePath
                        raw.startsWith("'") && raw.endsWith("'") -> raw.substring(1, raw.length - 1)
                        raw.startsWith("/") -> {
                            val out = File(extractDir, raw.removePrefix("/"))
                            out.parentFile.mkdirs()
                            val entry = zip.getEntry(raw.removePrefix("/")) ?: throw IOException("$raw нет в инсталлере")
                            zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                            out.absolutePath
                        }
                        else -> raw
                    }
                }
            }
            data
        }

    private suspend fun runProcessor(p: JsonObject, data: Map<String, String>, java: JavaInfo, log: File) =
        withContext(Dispatchers.IO) {
            val jar = MavenCoord.parse(p.str("jar")!!).file()
            val mainClass = JarFile(jar).use { it.manifest.mainAttributes.getValue("Main-Class") }
                ?: throw IOException("В ${jar.name} нет Main-Class")
            val classpath = (p.arr("classpath").map { MavenCoord.parse(it.jsonPrimitive.content).file() } + jar)
                .distinct().joinToString(File.pathSeparator) { it.absolutePath }
            val args = p.arr("args").map { a ->
                val s = a.jsonPrimitive.content
                when {
                    s.startsWith("[") && s.endsWith("]") -> MavenCoord.parse(s.substring(1, s.length - 1)).file().absolutePath
                    else -> Regex("""\{([A-Z_]+)}""").replace(s) { m ->
                        data[m.groupValues[1]] ?: throw IOException("Неизвестная переменная ${m.value}")
                    }
                }
            }
            val command = listOf(java.executable.absolutePath, "-cp", classpath, mainClass) + args
            log.appendText("\n> ${mainClass} ${args.joinToString(" ")}\n")
            val process = ProcessBuilder(command)
                .directory(MintPaths.game.apply { mkdirs() })
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .start()
            val code = process.waitFor()
            if (code != 0) throw IOException("Процессор NeoForge ($mainClass) завершился с кодом $code. Лог: ${log.path}")
        }
}
