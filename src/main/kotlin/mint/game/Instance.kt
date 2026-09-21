package mint.game

import kotlinx.serialization.Serializable
import mint.core.MintJson
import mint.core.MintPaths
import java.io.File

@Serializable
enum class Loader { VANILLA, NEOFORGE }

/** Сборка: отдельная игровая папка с конкретной версией игры и загрузчика. */
@Serializable
data class Instance(
    val id: String,
    val name: String,
    val minecraft: String,
    val loader: Loader,
    /** Пусто — при первой установке берём последнюю подходящую и фиксируем. */
    val loaderVersion: String = "",
    /** Переопределение памяти для сборки, 0 — из общих настроек. */
    val memoryMb: Int = 0,
    /** GitHub-репозиторий сборки (owner/name); пусто — локальная сборка или официальная из [Packs.official]. */
    val repo: String = "",
) {
    val dir get() = File(MintPaths.instances, id)
    val modsDir get() = File(dir, "mods")

    /** Сборка скачана: в папке есть что-то кроме заглушки instance.json. */
    val installed: Boolean
        get() = dir.listFiles().orEmpty().any { it.name != "instance.json" && it.name != "mods" } ||
            modsDir.listFiles().orEmpty().isNotEmpty()

    /** id версии в каталоге versions/, которую нужно запускать. */
    val versionId: String
        get() = when (loader) {
            Loader.VANILLA -> minecraft
            Loader.NEOFORGE -> "neoforge-$loaderVersion"
        }

    val modCount: Int get() = modsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.size ?: 0

    val subtitle: String
        get() = buildString {
            append(minecraft).append(" · ")
            append(if (loader == Loader.NEOFORGE) "NeoForge" else "Vanilla")
            // До скачивания число модов неизвестно — «0 модов» только сбивало бы с толку
            if (loader != Loader.VANILLA && installed) append(" · ").append(pluralMods(modCount))
        }
}

fun pluralMods(n: Int): String {
    val mod10 = n % 10
    val mod100 = n % 100
    val word = when {
        mod10 == 1 && mod100 != 11 -> "мод"
        mod10 in 2..4 && mod100 !in 12..14 -> "мода"
        else -> "модов"
    }
    return "$n $word"
}

object Instances {
    private val default = Instance(
        id = "createmint",
        name = "CreateMint",
        minecraft = "1.21.1",
        loader = Loader.NEOFORGE,
        repo = "GrayRK/CreateMint",
    )

    fun all(): List<Instance> {
        // Официальные сборки, которых ещё нет на диске, создаются заглушками —
        // содержимое скачается из репозитория при первом запуске
        Packs.official.forEach { pack ->
            if (!File(MintPaths.instances, "${pack.id}/instance.json").isFile) {
                save(Instance(pack.id, pack.name, pack.minecraft, pack.loader, repo = pack.repo))
            }
        }
        return MintPaths.instances.listFiles { f -> f.isDirectory }
            ?.mapNotNull { load(it) }
            ?.sortedBy { dir -> Packs.official.indexOfFirst { it.id == dir.id }.let { if (it < 0) Int.MAX_VALUE else it } }
            .orEmpty()
            .ifEmpty { listOf(default.also { save(it) }) }
    }

    /** id — всегда имя папки: instance.json мог прийти из репозитория с другим id. */
    fun load(dir: File): Instance? = runCatching {
        MintJson.decodeFromString<Instance>(File(dir, "instance.json").readText()).copy(id = dir.name)
    }.getOrNull()

    fun save(instance: Instance) {
        instance.dir.mkdirs()
        instance.modsDir.mkdirs()
        File(instance.dir, "instance.json").writeText(MintJson.encodeToString(Instance.serializer(), instance))
    }
}
