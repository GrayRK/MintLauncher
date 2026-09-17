package mint.game

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mint.core.DownloadTask
import mint.core.MintJson
import mint.core.MintPaths
import java.io.File

/** Отчёт о ходе длительной операции. fraction == null — неопределённый прогресс. */
fun interface ProgressSink {
    fun report(stage: String, fraction: Float?)
}

// ---------- helpers for JsonElement ----------

val JsonElement.obj: JsonObject get() = jsonObject
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())

// ---------- Maven coordinates ----------

data class MavenCoord(
    val group: String,
    val artifact: String,
    val version: String,
    val classifier: String?,
    val extension: String,
) {
    val path: String
        get() = buildString {
            append(group.replace('.', '/')).append('/').append(artifact).append('/').append(version).append('/')
            append(artifact).append('-').append(version)
            if (classifier != null) append('-').append(classifier)
            append('.').append(extension)
        }

    /** Ключ для устранения дублей: одна и та же библиотека разных версий. */
    val dedupeKey get() = "$group:$artifact:${classifier.orEmpty()}"

    fun file(root: File = MintPaths.libraries) = File(root, path)

    companion object {
        fun parse(coord: String): MavenCoord {
            var base = coord
            var ext = "jar"
            val at = coord.indexOf('@')
            if (at >= 0) {
                ext = coord.substring(at + 1)
                base = coord.substring(0, at)
            }
            val parts = base.split(':')
            require(parts.size >= 3) { "Некорректная координата: $coord" }
            return MavenCoord(parts[0], parts[1], parts[2], parts.getOrNull(3), ext)
        }
    }
}

// ---------- Rules ----------

object Rules {
    private val osName = "windows"
    private val arch = System.getProperty("os.arch").lowercase()

    /** Возвращает true, если элемент разрешён для текущей ОС и без дополнительных features. */
    fun allowed(rules: JsonArray?): Boolean {
        if (rules == null || rules.isEmpty()) return true
        var result = false
        for (r in rules) {
            val rule = r.jsonObject
            val allow = rule.str("action") == "allow"
            if (matches(rule)) result = allow
        }
        return result
    }

    private fun matches(rule: JsonObject): Boolean {
        rule.objOrNull("os")?.let { os ->
            os.str("name")?.let { if (it != osName) return false }
            os.str("arch")?.let { if (it == "x86" && arch.contains("64")) return false }
        }
        rule.objOrNull("features")?.let { features ->
            // Никакие опциональные features (demo, quick play, custom resolution) не включены
            if (features.values.any { (it as? JsonPrimitive)?.booleanOrNull == true }) return false
        }
        return true
    }
}

// ---------- Library ----------

data class Library(
    val coord: MavenCoord,
    /** Путь относительно каталога libraries. */
    val path: String,
    val url: String?,
    val sha1: String?,
    val size: Long,
) {
    val file get() = File(MintPaths.libraries, path)
    fun downloadTask(): DownloadTask? = url?.takeIf { it.isNotBlank() }?.let { DownloadTask(it, file, sha1, size) }

    companion object {
        fun from(json: JsonObject): Library {
            val coord = MavenCoord.parse(json.str("name")!!)
            val artifact = json.objOrNull("downloads")?.objOrNull("artifact")
            return if (artifact != null) {
                Library(
                    coord = coord,
                    path = artifact.str("path") ?: coord.path,
                    url = artifact.str("url"),
                    sha1 = artifact.str("sha1"),
                    size = artifact.long("size") ?: -1,
                )
            } else {
                val base = json.str("url") ?: "https://libraries.minecraft.net/"
                Library(coord, coord.path, base.trimEnd('/') + "/" + coord.path, null, -1)
            }
        }
    }
}

// ---------- Resolved version (после слияния inheritsFrom) ----------

class ResolvedVersion(
    val id: String,
    val mainClass: String,
    val libraries: List<Library>,
    val gameArgs: List<JsonElement>,
    val jvmArgs: List<JsonElement>,
    val assetIndex: JsonObject,
    val clientDownload: JsonObject?,
    val logging: JsonObject?,
    val javaMajor: Int,
    /** id базовой (ванильной) версии, чей client.jar используется. */
    val baseId: String,
    val isModded: Boolean,
)

object Versions {
    fun jsonFile(id: String) = File(MintPaths.versions, "$id/$id.json")
    fun clientJar(id: String) = File(MintPaths.versions, "$id/$id.jar")

    fun read(id: String): JsonObject = MintJson.parseToJsonElement(jsonFile(id).readText()).jsonObject

    fun resolve(id: String): ResolvedVersion {
        val chain = generateSequence(read(id)) { v -> v.str("inheritsFrom")?.let { read(it) } }.toList()
        // chain[0] — самая «дочерняя» версия, last — ванилла
        val vanilla = chain.last()
        val libs = LinkedHashMap<String, Library>()
        for (v in chain) {
            for (l in v.arr("libraries")) {
                val o = l.jsonObject
                if (!Rules.allowed(o["rules"] as? JsonArray)) continue
                val lib = Library.from(o)
                libs.putIfAbsent(lib.coord.dedupeKey, lib)
            }
        }
        val game = chain.reversed().flatMap { it.objOrNull("arguments")?.arr("game").orEmpty() }
        val jvm = chain.reversed().flatMap { it.objOrNull("arguments")?.arr("jvm").orEmpty() }
        return ResolvedVersion(
            id = id,
            mainClass = chain.firstNotNullOf { it.str("mainClass") },
            libraries = libs.values.toList(),
            gameArgs = game,
            jvmArgs = jvm,
            assetIndex = chain.firstNotNullOf { it.objOrNull("assetIndex") },
            clientDownload = vanilla.objOrNull("downloads")?.objOrNull("client"),
            logging = vanilla.objOrNull("logging")?.objOrNull("client"),
            javaMajor = chain.firstNotNullOfOrNull { it.objOrNull("javaVersion")?.long("majorVersion") }?.toInt() ?: 21,
            baseId = vanilla.str("id")!!,
            isModded = chain.size > 1,
        )
    }
}

/** Разворачивает список аргументов с правилами в плоский список строк. */
fun expandArgs(args: List<JsonElement>, vars: Map<String, String>): List<String> {
    val out = mutableListOf<String>()
    for (a in args) {
        when (a) {
            is JsonPrimitive -> out += substitute(a.content, vars)
            is JsonObject -> {
                if (!Rules.allowed(a["rules"] as? JsonArray)) continue
                when (val value = a["value"]) {
                    is JsonPrimitive -> out += substitute(value.content, vars)
                    is JsonArray -> value.forEach { out += substitute(it.jsonPrimitive.content, vars) }
                    else -> {}
                }
            }
            else -> {}
        }
    }
    return out
}

private val placeholder = Regex("""\$\{([a-zA-Z0-9_]+)}""")

fun substitute(s: String, vars: Map<String, String>): String =
    placeholder.replace(s) { m -> vars[m.groupValues[1]] ?: m.value }
