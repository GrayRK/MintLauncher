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

@Serializable
data class LauncherSettings(
    val theme: Theme = Theme.LIGHT,
    val memoryMb: Int = 6144,
    val javaAuto: Boolean = true,
    val javaPath: String = "",
    val closeOnLaunch: Boolean = false,
    val showConsole: Boolean = false,
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
