package mint.core

import java.io.File

/**
 * Портативная раскладка: все данные лежат рядом с лаунчером.
 *
 * home/
 *   launcher.json      — настройки и аккаунт
 *   runtime/java-21/   — скачанная Java
 *   game/              — общие versions / libraries / assets
 *   instances/<id>/    — папка конкретной сборки (mods, saves, config ...)
 *   logs/
 */
object MintPaths {
    val home: File by lazy { resolveHome().also { it.mkdirs() } }

    val settingsFile get() = File(home, "launcher.json")
    val runtime get() = File(home, "runtime")
    val game get() = File(home, "game")
    val versions get() = File(game, "versions")
    val libraries get() = File(game, "libraries")
    val assets get() = File(game, "assets")
    val instances get() = File(home, "instances")
    val logs get() = File(home, "logs")
    val cache get() = File(home, "cache")

    private fun resolveHome(): File {
        System.getProperty("mint.home")?.let { return File(it).absoluteFile }
        // Путь к jar-файлу лаунчера. В сборке jpackage это <install>/app/Mint.jar
        val location = runCatching {
            File(MintPaths::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        val dir = when {
            location == null -> File(".")
            location.isFile -> location.parentFile
            else -> location
        }.absoluteFile
        val base = if (dir.name.equals("app", ignoreCase = true)) dir.parentFile else dir
        return File(base, "data")
    }
}
