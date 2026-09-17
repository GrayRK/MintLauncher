package mint.auth

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mint.core.Account
import mint.core.AccountType
import mint.core.DownloadTask
import mint.core.Http
import mint.core.MintJson
import mint.core.MintPaths
import java.io.File
import java.io.IOException
import java.util.UUID

class AuthException(message: String, val needsTotp: Boolean = false) : Exception(message)

object Auth {
    private val nickRegex = Regex("^[A-Za-z0-9_]{3,16}$")

    fun offline(nick: String): Account {
        val name = nick.trim()
        if (!nickRegex.matches(name)) throw AuthException("Ник: 3–16 символов, латиница, цифры и _")
        val uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray()).toString().replace("-", "")
        return Account(AccountType.OFFLINE, name, uuid)
    }

    /** "ely.by" → официальный authserver Ely.by, иначе URL API в формате authlib-injector. */
    private fun authBase(server: String): String = when (server.trim().lowercase()) {
        "ely.by", "" -> "https://authserver.ely.by/auth"
        else -> server.trim().trimEnd('/') + "/authserver"
    }

    suspend fun login(server: String, login: String, password: String, totp: String = ""): Account {
        val clientToken = UUID.randomUUID().toString().replace("-", "")
        val body = buildJsonObject {
            put("username", login.trim())
            put("password", if (totp.isBlank()) password else "$password:${totp.trim()}")
            put("clientToken", clientToken)
            put("requestUser", true)
            putJsonObject("agent") { put("name", "Minecraft"); put("version", 1) }
        }
        val (code, text) = try {
            Http.postJson("${authBase(server)}/authenticate", body.toString())
        } catch (e: IOException) {
            throw AuthException("Сервер авторизации недоступен: ${e.message}")
        }
        val json = runCatching { MintJson.parseToJsonElement(text).jsonObject }.getOrNull()
        if (code !in 200..299 || json == null) {
            val message = json?.get("errorMessage")?.jsonPrimitive?.content.orEmpty()
            if (message.contains("two factor", ignoreCase = true)) {
                throw AuthException("Введите код двухфакторной аутентификации", needsTotp = true)
            }
            throw AuthException(
                when {
                    message.contains("Invalid credentials", true) -> "Неверный логин или пароль"
                    message.isNotBlank() -> message
                    else -> "Ошибка авторизации (HTTP $code)"
                }
            )
        }
        val profile = json["selectedProfile"]?.jsonObject ?: throw AuthException("У аккаунта нет игрового профиля")
        return Account(
            type = AccountType.YGGDRASIL,
            username = profile["name"]!!.jsonPrimitive.content,
            uuid = profile["id"]!!.jsonPrimitive.content,
            accessToken = json["accessToken"]!!.jsonPrimitive.content,
            clientToken = json["clientToken"]?.jsonPrimitive?.content ?: clientToken,
            authServer = server.trim().ifBlank { "ely.by" },
        )
    }

    /** Перед запуском: проверить токен и при необходимости обновить. */
    suspend fun refreshIfNeeded(account: Account): Account {
        if (account.type != AccountType.YGGDRASIL) return account
        val base = authBase(account.authServer)
        val tokens = buildJsonObject {
            put("accessToken", account.accessToken)
            put("clientToken", account.clientToken)
        }
        val (validCode, _) = Http.postJson("$base/validate", tokens.toString())
        if (validCode in 200..299) return account
        val (code, text) = Http.postJson("$base/refresh", tokens.toString())
        if (code !in 200..299) throw AuthException("Сессия истекла — войдите заново")
        val json = MintJson.parseToJsonElement(text).jsonObject
        return account.copy(accessToken = json["accessToken"]!!.jsonPrimitive.content)
    }
}

/** authlib-injector подменяет сервер сессий Mojang на Ely.by / свой Yggdrasil. */
object AuthlibInjector {
    val jar get() = File(MintPaths.runtime, "authlib-injector.jar")

    suspend fun ensure(): File {
        if (jar.isFile) return jar
        val latest = Http.getJson("https://authlib-injector.yushi.moe/artifact/latest.json").jsonObject
        Http.download(DownloadTask(latest["download_url"]!!.jsonPrimitive.content, jar))
        return jar
    }

    fun agentArg(account: Account): String {
        val target = when (account.authServer.trim().lowercase()) {
            "", "ely.by" -> "ely.by"
            else -> account.authServer.trim()
        }
        return "-javaagent:${jar.absolutePath}=$target"
    }
}
