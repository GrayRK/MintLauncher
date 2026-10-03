package mint.core

import kotlinx.serialization.Serializable

@Serializable
enum class AccountType { OFFLINE, YGGDRASIL }

@Serializable
data class Account(
    val type: AccountType,
    val username: String,
    val uuid: String,
    val accessToken: String = "0",
    val clientToken: String = "",
    /** Для YGGDRASIL: адрес API (например, "ely.by" или https://your.server/api/yggdrasil). */
    val authServer: String = "",
)

@Serializable
enum class Theme { LIGHT, DARK, SYSTEM }

/**
 * Сборщик мусора игры.
 *
 * По умолчанию поколенческий ZGC (Java 21): замер 03.10 на Vanilla+ дал 94 паузы по 0 мс
 * против 6–12 мс у G1 при тех же кадрах и без рывков. Платит он памятью — отсюда «минимум 6 ГБ,
 * рекомендуем 8» в настройках. G1 оставлен на случай слабой машины: он экономнее по памяти.
 */
@Serializable
enum class Gc { G1, ZGC }

@Serializable
data class LauncherSettings(
    val theme: Theme = Theme.LIGHT,
    val memoryMb: Int = 6144,
    /** Память локального сервера сборки. */
    val serverMemoryMb: Int = 4096,
    /** EULA Minecraft принимает игрок — без этого локальный сервер не поднимается. */
    val eulaAccepted: Boolean = false,
    val javaAuto: Boolean = true,
    val gc: Gc = Gc.ZGC,
    val javaPath: String = "",
    val closeOnLaunch: Boolean = false,
    /** Проверять обновления лаунчера при запуске и ставить их до открытия окна. */
    val autoUpdate: Boolean = true,
    val rememberMe: Boolean = true,
    val lastLogin: String = "",
    val authServer: String = "ely.by",
    val account: Account? = null,
    val selectedInstance: String = "main",
)

object SettingsStore {
    fun load(): LauncherSettings = runCatching {
        MintJson.decodeFromString<LauncherSettings>(MintPaths.settingsFile.readText())
    }.getOrElse { LauncherSettings() }

    fun save(settings: LauncherSettings) {
        val toSave = if (settings.rememberMe) settings else settings.copy(account = null)
        MintPaths.settingsFile.writeText(MintJson.encodeToString(LauncherSettings.serializer(), toSave))
    }
}
