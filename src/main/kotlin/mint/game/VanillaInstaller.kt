package mint.game

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mint.core.DownloadTask
import mint.core.Http
import mint.core.MintJson
import mint.core.MintPaths
import java.io.File
import java.io.IOException

/** Установка ванильной версии: json, client.jar, библиотеки, ассеты, конфиг логов. */
object VanillaInstaller {
    private const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private const val RESOURCES = "https://resources.download.minecraft.net"

    /** Скачивает json версии, если его ещё нет. */
    suspend fun ensureVersionJson(id: String, progress: ProgressSink) {
        val file = Versions.jsonFile(id)
        if (file.isFile) return
        progress.report("Получение данных Minecraft $id", null)
        val manifest = Http.getJson(MANIFEST).jsonObject
        val entry = manifest.arr("versions").map { it.jsonObject }.firstOrNull { it.str("id") == id }
            ?: throw IOException("Версия $id не найдена в манифесте Mojang")
        Http.download(DownloadTask(entry.str("url")!!, file, entry.str("sha1")))
    }

    fun clientJarTask(version: ResolvedVersion): DownloadTask {
        val client = version.clientDownload ?: throw IOException("В версии ${version.baseId} нет client.jar")
        return DownloadTask(client.str("url")!!, Versions.clientJar(version.baseId), client.str("sha1"), client.long("size") ?: -1)
    }

    fun loggingConfigFile(version: ResolvedVersion): File? =
        version.logging?.objOrNull("file")?.str("id")?.let { File(MintPaths.assets, "log_configs/$it") }

    /** Все файлы, нужные для запуска разрешённой версии (включая модифицированную). */
    suspend fun ensureFiles(version: ResolvedVersion, progress: ProgressSink) {
        val tasks = mutableListOf<DownloadTask>()
        tasks += clientJarTask(version)
        version.libraries.mapNotNullTo(tasks) { it.downloadTask() }
        version.logging?.objOrNull("file")?.let { f ->
            tasks += DownloadTask(f.str("url")!!, loggingConfigFile(version)!!, f.str("sha1"), f.long("size") ?: -1)
        }
        progress.report("Проверка библиотек", null)
        Http.downloadAll(tasks) { done, total ->
            progress.report("Загрузка библиотек · $done из $total", if (total == 0) 1f else done.toFloat() / total)
        }

        val missing = version.libraries.filter { !it.file.isFile }
        if (missing.isNotEmpty()) {
            throw IOException("Отсутствуют библиотеки: " + missing.joinToString { it.coord.toString() })
        }

        ensureAssets(version, progress)
    }

    private suspend fun ensureAssets(version: ResolvedVersion, progress: ProgressSink) {
        val index = version.assetIndex
        val indexId = index.str("id")!!
        val indexFile = File(MintPaths.assets, "indexes/$indexId.json")
        Http.download(DownloadTask(index.str("url")!!, indexFile, index.str("sha1"), index.long("size") ?: -1))

        progress.report("Проверка ресурсов игры", null)
        val objects = MintJson.parseToJsonElement(indexFile.readText()).jsonObject.objOrNull("objects")!!
        val tasks = objects.values.map { it.jsonObject }.map { o ->
            val hash = o.str("hash")!!
            val prefix = hash.substring(0, 2)
            DownloadTask(
                url = "$RESOURCES/$prefix/$hash",
                target = File(MintPaths.assets, "objects/$prefix/$hash"),
                sha1 = null, // хеши проверяются при загрузке по размеру; полная проверка тысяч файлов слишком медленная
                size = o.long("size") ?: -1,
            )
        }
        Http.downloadAll(tasks, parallelism = 16) { done, total ->
            progress.report("Загрузка ресурсов · $done из $total", if (total == 0) 1f else done.toFloat() / total)
        }
    }
}
