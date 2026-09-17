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
) {
    val dir get() = File(MintPaths.instances, id)
    val modsDir get() = File(dir, "mods")

    /** id версии в каталоге versions/, которую нужно запускать. */
    val versionId: String
        get() = when (loader) {
            Loader.VANILLA -> minecraft
            Loader.NEOFORGE -> "neoforge-$loaderVersion"
        }

    val subtitle: String
        get() = buildString {
            append(minecraft).append(" · ")
            append(if (loader == Loader.NEOFORGE) "NeoForge" else "Vanilla")
            val mods = modsDir.listFiles { f -> f.isFile && f.name.endsWith(".jar") }?.size ?: 0
            if (loader != Loader.VANILLA) append(" · ").append(pluralMods(mods))
        }
}

private fun pluralMods(n: Int): String {
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
        id = "main",
        name = "Mint NeoForge",
        minecraft = "1.21.1",
        loader = Loader.NEOFORGE,
    )

    fun all(): List<Instance> {
        val found = MintPaths.instances.listFiles { f -> f.isDirectory }
            ?.mapNotNull { load(it) }
            .orEmpty()
        return found.ifEmpty { listOf(default.also { save(it) }) }
    }

    fun load(dir: File): Instance? = runCatching {
        MintJson.decodeFromString<Instance>(File(dir, "instance.json").readText())
    }.getOrNull()

    fun save(instance: Instance) {
        instance.dir.mkdirs()
        instance.modsDir.mkdirs()
        File(instance.dir, "instance.json").writeText(MintJson.encodeToString(Instance.serializer(), instance))
    }
}
