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
    /** Одна строка под названием на карточке каталога. */
    val tagline: String = "",
    /** Абзацы описания для вкладки «Сборки». */
    val description: List<String> = emptyList(),
    /** Главное, что есть в сборке, — короткими пунктами. */
    val highlights: List<String> = emptyList(),
)

/** Сторона, которой нужен файл сборки. */
@Serializable
enum class PackSide {
    /** Нужен и клиенту, и серверу: мод с мировой логикой, библиотека, общий конфиг. */
    BOTH,

    /** Только клиенту: интерфейс, звуки, шейдеры, клиентские оптимизации. */
    CLIENT,

    /** Только серверу: то, чего в клиентской папке mods/ вообще быть не должно. */
    SERVER,
}

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
    /** Кому нужен файл. Правится вручную и переживает пересборку манифеста. */
    val side: PackSide = PackSide.BOTH,
) {
    val forClient get() = side != PackSide.SERVER
    val forServer get() = side != PackSide.CLIENT
}

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
    /**
     * Официальные сборки: появляются у игрока сразу после установки лаунчера.
     * Иконка и превью вшиты в лаунчер (resources/packs/<id>/icon.png, banner.jpg),
     * чтобы каталог выглядел целым ещё до скачивания сборки.
     */
    val official = listOf(
        PackSource(
            id = "createmint",
            repo = "GrayRK/CreateMint",
            name = "CreateMint",
            minecraft = "1.21.1",
            loader = Loader.NEOFORGE,
            tagline = "Create, паровые механизмы и живописный мир",
            description = listOf(
                "Сборка вокруг Create: шестерни, валы, конвейеры и поезда, из которых собираются целые заводы. " +
                    "Аддоны добавляют новые механизмы, декор и способы автоматизации, а MineColonies — " +
                    "колонию жителей, которые строят и работают вместе с вами.",
                "Мир генерирует Terralith: около сотни биомов из ванильных блоков, высокие горы и новые структуры. " +
                    "Distant Horizons дорисовывает ландшафт до горизонта, а Sodium и Lithium держат FPS.",
                "Играть можно одному или с друзьями: кнопка «Сервер» на главной поднимает сервер этой сборки прямо с вашего компьютера.",
            ),
            highlights = listOf(
                "Create и аддоны",
                "Колония MineColonies",
                "Биомы Terralith",
                "Дальняя прорисовка Distant Horizons",
                "Sodium + Lithium",
                "Свой сервер в один клик",
            ),
        ),
        PackSource(
            id = "testmint",
            repo = "GrayRK/TestMint",
            name = "TestMint",
            minecraft = "1.21.1",
            loader = Loader.NEOFORGE,
            tagline = "Полигон для проверки модов и лаунчера",
            description = listOf(
                "Тестовая сборка: здесь проверяются новые моды, настройки и функции лаунчера, прежде чем попасть в основные сборки.",
                "Состав может меняться в любой момент, а миры — ломаться. Для обычной игры выбирайте CreateMint.",
            ),
            highlights = listOf("Новые моды раньше основных сборок", "Проверка функций лаунчера"),
        ),
    )

    fun info(id: String): PackSource? = official.firstOrNull { it.id == id }

    const val MANIFEST = "mint-pack.json"
    private const val STATE = ".mint-pack.json"

    /**
     * Файлы репозитория, которые не копируются в папку сборки.
     * Манифест копируется: по нему локальный сервер понимает, какие моды ему нужны.
     */
    private val repoOnly = setOf(".gitignore", ".gitattributes", "README.md", "LICENSE")

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
        manifest.files.filter { it.forClient }.forEach { newFiles[it.path] = it.sha1.lowercase() }

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
        val files = manifest.files.filter { it.forClient }
        if (files.isEmpty()) return
        val tasks = files.map { DownloadTask(it.url, File(dir, it.path), it.sha1, it.size) }
        Http.downloadAll(tasks) { done, total ->
            progress.report("Файлы сборки $done/$total", if (total == 0) 1f else done.toFloat() / total)
        }
    }

    private fun reload(instance: Instance): Instance =
        Instances.load(instance.dir) ?: instance

    private fun readManifest(file: File): PackManifest? =
        runCatching { MintJson.decodeFromString<PackManifest>(file.readText()) }.getOrNull()

    /**
     * Манифест сборки. У разработчика он лежит в папке сборки, а у игрока, который
     * ставил сборку старым лаунчером, — только внутри .mint-pack.json. Читаем оба места,
     * иначе сторона модов неизвестна и на сервер уезжает клиентский Sodium.
     */
    fun manifest(instance: Instance): PackManifest {
        readManifest(File(instance.dir, MANIFEST))?.let { if (it.files.isNotEmpty()) return it }
        val state = runCatching {
            MintJson.decodeFromString<PackState>(File(instance.dir, STATE).readText())
        }.getOrNull()
        return PackManifest(state?.external.orEmpty())
    }

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
            val known = previous[hash]
            val url = known?.url ?: modrinth[hash]?.url
            if (url == null) {
                missing += path
                continue
            }
            // Сторона, выставленная вручную, важнее подсказки Modrinth: там почти всё «optional»
            val side = known?.side ?: modrinth[hash]?.side ?: PackSide.BOTH
            entries += PackFile(path, url, hash, file.length(), side)
        }
        // Серверные файлы (их нет в mods/ клиента) переносим из прошлого манифеста как есть
        entries += previous.values.filter { it.side == PackSide.SERVER && entries.none { e -> e.path == it.path } }

        manifestFile.writeText(MintJson.encodeToString(PackManifest.serializer(), PackManifest(entries)) + "\n")
        missing
    }

    private data class ModrinthFile(val url: String, val side: PackSide)

    /** sha1 → ссылка на файл в Modrinth CDN и сторона по метаданным проекта. */
    private suspend fun lookupModrinth(hashes: List<String>): Map<String, ModrinthFile> {
        if (hashes.isEmpty()) return emptyMap()
        val body = buildJsonObject {
            put("hashes", buildJsonArray { hashes.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("algorithm", "sha1")
        }
        val (code, text) = Http.postJson("https://api.modrinth.com/v2/version_files", body.toString())
        if (code !in 200..299) throw IOException("Modrinth ответил HTTP $code")
        val result = MintJson.parseToJsonElement(text).jsonObject

        val urls = linkedMapOf<String, String>()
        val projectOf = linkedMapOf<String, String>()
        for (hash in hashes) {
            val version = result[hash] as? JsonObject ?: continue
            val file = version["files"]!!.jsonArray.map { it.jsonObject }
                .firstOrNull { it["hashes"]?.jsonObject?.get("sha1")?.jsonPrimitive?.content == hash }
                ?: continue
            urls[hash] = file["url"]!!.jsonPrimitive.content
            version["project_id"]?.jsonPrimitive?.content?.let { projectOf[hash] = it }
        }

        val sides = projectSides(projectOf.values.distinct())
        return urls.mapValues { (hash, url) ->
            ModrinthFile(url, sides[projectOf[hash]] ?: PackSide.BOTH)
        }
    }

    /** id проекта → сторона. Modrinth почти всё помечает «optional», поэтому ловим только явный «unsupported». */
    private suspend fun projectSides(ids: List<String>): Map<String, PackSide> {
        if (ids.isEmpty()) return emptyMap()
        val query = ids.joinToString(",", "[", "]") { "\"$it\"" }
        val text = runCatching {
            Http.getString("https://api.modrinth.com/v2/projects?ids=" + java.net.URLEncoder.encode(query, "UTF-8"))
        }.getOrElse { return emptyMap() }
        return MintJson.parseToJsonElement(text).jsonArray.mapNotNull { element ->
            val project = element.jsonObject
            val id = project["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val client = project["client_side"]?.jsonPrimitive?.content
            val server = project["server_side"]?.jsonPrimitive?.content
            val side = when {
                server == "unsupported" && client != "unsupported" -> PackSide.CLIENT
                client == "unsupported" && server != "unsupported" -> PackSide.SERVER
                else -> PackSide.BOTH
            }
            id to side
        }.toMap()
    }
}
