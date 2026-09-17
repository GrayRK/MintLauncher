package mint.game

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mint.core.DownloadTask
import mint.core.Http
import mint.core.MintJson
import mint.core.MintPaths
import mint.core.sha1
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Сборки, которые публикуются в отдельных GitHub-репозиториях.
 *
 * Репозиторий сборки = содержимое папки instances/<id>:
 *   instance.json     — описание сборки
 *   mint-pack.json    — ссылки и sha1 модов/шейдеров: файлы, которых нет в репозитории, лаунчер качает сам
 *   mods/, config/ …  — всё остальное лежит в git как есть
 *
 * Игроки получают последний GitHub-релиз. Если в папке сборки есть .git — это рабочая копия
 * разработчика, и лаунчер её не трогает.
 */
@Serializable
data class PackSource(
    val id: String,
    /** owner/name на GitHub. */
    val repo: String,
    /** Заглушка до первой синхронизации — потом всё берётся из instance.json репозитория. */
    val name: String,
    val minecraft: String,
    val loader: Loader,
)

/** mint-pack.json в репозитории сборки. */
@Serializable
data class PackManifest(val files: List<PackFile> = emptyList())

@Serializable
data class PackFile(
    /** Путь внутри папки сборки, через «/». */
    val path: String,
    val url: String,
    val sha1: String,
    val size: Long = -1,
)

/** .mint-pack.json в папке сборки: что и из какого релиза установлено. */
@Serializable
private data class PackState(
    val repo: String,
    val tag: String,
    /** путь → sha1 файла в том виде, в каком его положил лаунчер. */
    val files: Map<String, String> = emptyMap(),
    /** Внешние файлы релиза — чтобы докачивать их без повторной загрузки архива. */
    val external: List<PackFile> = emptyList(),
)

object Packs {
    /** Официальные сборки: появляются у игрока сразу после установки лаунчера. */
    val official = listOf(
        PackSource("vanillamint", "GrayRK/VanillaMint", "VanillaMint", "1.21.1", Loader.NEOFORGE),
    )

    const val MANIFEST = "mint-pack.json"
    private const val STATE = ".mint-pack.json"

    /** Файлы репозитория, которые не копируются в папку сборки. */
    private val repoOnly = setOf(MANIFEST, ".gitignore", ".gitattributes", "README.md", "LICENSE")

    fun sourceFor(instance: Instance): String? =
        instance.repo.ifBlank { null } ?: official.firstOrNull { it.id == instance.id }?.repo

    fun isDevCopy(instance: Instance) = File(instance.dir, ".git").exists()

    /**
     * Приводит папку сборки к последнему релизу репозитория.
     * Изменённые игроком файлы (конфиги) не перезаписываются, пока их не поменяет сама сборка.
     * Возвращает актуальную сборку (instance.json мог обновиться).
     */
    suspend fun sync(instance: Instance, progress: ProgressSink): Instance = withContext(Dispatchers.IO) {
        val repo = sourceFor(instance) ?: return@withContext instance
        if (isDevCopy(instance)) return@withContext instance

        val dir = instance.dir
        val stateFile = File(dir, STATE)
        val state = runCatching { MintJson.decodeFromString<PackState>(stateFile.readText()) }.getOrNull()

        progress.report("Проверка обновлений сборки", null)
        val tag = runCatching {
            Http.getJson("https://api.github.com/repos/$repo/releases/latest").jsonObject["tag_name"]!!.jsonPrimitive.content
        }.getOrElse { e ->
            // Нет сети или лимит GitHub API — играем тем, что уже установлено
            if (state != null) return@withContext instance
            throw IOException("Не удалось получить сборку $repo: ${e.message}", e)
        }

        if (state?.repo == repo && state.tag == tag) {
            // Релиз тот же — только докачиваем недостающие/повреждённые внешние файлы
            downloadExternal(dir, PackManifest(state.external), progress)
            return@withContext reload(instance)
        }

        progress.report("Загрузка сборки $tag", null)
        val cacheDir = File(MintPaths.cache, "packs/${instance.id}")
        cacheDir.deleteRecursively()
        val zip = File(MintPaths.cache, "packs/${instance.id}.zip").apply { delete() }
        Http.download(DownloadTask("https://github.com/$repo/archive/refs/tags/$tag.zip", zip))
        unzipStripRoot(zip, cacheDir)
        zip.delete()

        val manifest = readManifest(File(cacheDir, MANIFEST)) ?: PackManifest()
        val oldFiles = if (state?.repo == repo) state.files else emptyMap()
        val newFiles = linkedMapOf<String, String>()

        progress.report("Установка сборки $tag", null)
        cacheDir.walkTopDown().filter { it.isFile }.forEach { src ->
            val path = src.relativeTo(cacheDir).invariantSeparatorsPath
            if (path in repoOnly || path.startsWith(".github/")) return@forEach
            val hash = sha1(src)
            newFiles[path] = hash
            val target = File(dir, path)
            // Файл не менялся в сборке — оставляем правки игрока
            if (target.isFile && oldFiles[path] == hash) return@forEach
            target.parentFile.mkdirs()
            src.copyTo(target, overwrite = true)
        }
        manifest.files.forEach { newFiles[it.path] = it.sha1.lowercase() }

        // Убираем то, что сборка больше не содержит (если игрок это не менял)
        (oldFiles.keys - newFiles.keys).forEach { path ->
            val target = File(dir, path)
            if (target.isFile && sha1(target) == oldFiles[path]) target.delete()
        }

        downloadExternal(dir, manifest, progress)
        stateFile.writeText(MintJson.encodeToString(PackState.serializer(), PackState(repo, tag, newFiles, manifest.files)))
        cacheDir.deleteRecursively()
        reload(instance)
    }

    private suspend fun downloadExternal(dir: File, manifest: PackManifest, progress: ProgressSink) {
        if (manifest.files.isEmpty()) return
        val tasks = manifest.files.map { DownloadTask(it.url, File(dir, it.path), it.sha1, it.size) }
        Http.downloadAll(tasks) { done, total ->
            progress.report("Файлы сборки $done/$total", if (total == 0) 1f else done.toFloat() / total)
        }
    }

    private fun reload(instance: Instance): Instance =
        Instances.load(instance.dir) ?: instance

    private fun readManifest(file: File): PackManifest? =
        runCatching { MintJson.decodeFromString<PackManifest>(file.readText()) }.getOrNull()

    /** Архив GitHub содержит корневую папку <repo>-<tag>/ — её отбрасываем. */
    private fun unzipStripRoot(zip: File, target: File) {
        val root = target.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val rel = entry.name.substringAfter('/', "")
                if (rel.isEmpty() || entry.isDirectory) continue
                val out = File(root, rel).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) throw IOException("Недопустимый путь в архиве: ${entry.name}")
                out.parentFile.mkdirs()
                out.outputStream().use { input.copyTo(it) }
            }
        }
    }

    // ---- Для разработчика сборки ----

    /** Папки, файлы из которых по возможности подтягиваются по ссылке, а не лежат в git. */
    private val externalDirs = listOf("mods", "shaderpacks", "resourcepacks")

    /**
     * Пересобирает mint-pack.json по содержимому mods/shaderpacks/resourcepacks:
     * файлы ищутся на Modrinth по sha1; ссылки, вписанные вручную, сохраняются, пока совпадает хеш.
     * Возвращает пути файлов, для которых ссылка не найдена.
     */
    suspend fun writeManifest(instance: Instance): List<String> = withContext(Dispatchers.IO) {
        val dir = instance.dir
        val manifestFile = File(dir, MANIFEST)
        val previous = readManifest(manifestFile)?.files.orEmpty().associateBy { it.sha1.lowercase() }

        val local = externalDirs.flatMap { sub ->
            File(dir, sub).listFiles { f -> f.isFile && (f.extension == "jar" || f.extension == "zip") }.orEmpty().toList()
        }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
        val hashes = local.associateWith { sha1(it) }

        val modrinth = lookupModrinth(hashes.values.filter { it !in previous })

        val entries = mutableListOf<PackFile>()
        val missing = mutableListOf<String>()
        for (file in local) {
            val path = file.relativeTo(dir).invariantSeparatorsPath
            val hash = hashes.getValue(file)
            val url = previous[hash]?.url ?: modrinth[hash]
            if (url == null) missing += path else entries += PackFile(path, url, hash, file.length())
        }

        manifestFile.writeText(MintJson.encodeToString(PackManifest.serializer(), PackManifest(entries)) + "\n")
        missing
    }

    /** sha1 → прямая ссылка на файл в Modrinth CDN. */
    private suspend fun lookupModrinth(hashes: List<String>): Map<String, String> {
        if (hashes.isEmpty()) return emptyMap()
        val body = buildJsonObject {
            put("hashes", buildJsonArray { hashes.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("algorithm", "sha1")
        }
        val (code, text) = Http.postJson("https://api.modrinth.com/v2/version_files", body.toString())
        if (code !in 200..299) throw IOException("Modrinth ответил HTTP $code")
        val result = MintJson.parseToJsonElement(text).jsonObject
        return hashes.mapNotNull { hash ->
            val version = result[hash] as? JsonObject ?: return@mapNotNull null
            val file = version["files"]!!.jsonArray.map { it.jsonObject }
                .firstOrNull { it["hashes"]?.jsonObject?.get("sha1")?.jsonPrimitive?.content == hash }
                ?: return@mapNotNull null
            hash to file["url"]!!.jsonPrimitive.content
        }.toMap()
    }
}
