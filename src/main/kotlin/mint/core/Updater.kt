package mint.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mint.game.GameLauncher
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** Релиз лаунчера на GitHub, новее установленного. */
data class LauncherRelease(val version: String, val notes: String, val url: String, val size: Long)

/**
 * Самообновление портативного лаунчера из релизов GitHub.
 *
 * Работающий лаунчер не может заменить свои файлы: Windows держит открытыми jar, dll и сам Mint.exe.
 * Поэтому новая версия распаковывается в data/cache/update, а подменяет её скрипт PowerShell,
 * который дожидается выхода лаунчера, меняет app/, runtime/ и Mint.exe и запускает Mint снова.
 * Папка data/ (настройки, сборки, миры) при этом не трогается.
 */
object Updater {
    const val REPO = "GrayRK/MintLauncher"
    private const val ASSET_SUFFIX = "-windows-portable.zip"

    /** Версия из Mint.cfg (packageVersion) надёжнее константы: её не забудешь поднять. */
    val currentVersion: String =
        System.getProperty("jpackage.app-version") ?: GameLauncher.LAUNCHER_VERSION

    private val updateDir get() = File(MintPaths.cache, "update")

    /** Версия, которую лаунчер уже пытался поставить: защита от бесконечного цикла обновлений. */
    private val appliedMarker get() = File(updateDir, "applied.txt")

    /**
     * Папка портативной установки (рядом Mint.exe, app/, runtime/, data/).
     * null — лаунчер запущен из IDE/gradle или папка недоступна для записи: обновлять нечего и некуда.
     */
    val installDir: File? by lazy {
        val jar = runCatching { File(Updater::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
        val app = jar?.parentFile?.takeIf { jar.isFile && it.name.equals("app", ignoreCase = true) }
        val dir = app?.parentFile?.absoluteFile ?: return@lazy null
        if (!File(dir, "Mint.exe").isFile || !isWritable(dir)) return@lazy null
        dir
    }

    val canUpdate get() = installDir != null

    /** Последний релиз, если он новее установленной версии. */
    suspend fun latest(): LauncherRelease? {
        val json = Http.getJson("https://api.github.com/repos/$REPO/releases/latest").jsonObject
        val version = json["tag_name"]!!.jsonPrimitive.content.removePrefix("v")
        if (!isNewer(version, currentVersion)) return null
        val asset = json["assets"]!!.jsonArray.map { it.jsonObject }
            .firstOrNull { it["name"]!!.jsonPrimitive.content.endsWith(ASSET_SUFFIX) } ?: return null
        return LauncherRelease(
            version = version,
            notes = json["body"]?.jsonPrimitive?.content.orEmpty(),
            url = asset["browser_download_url"]!!.jsonPrimitive.content,
            size = asset["size"]!!.jsonPrimitive.long,
        )
    }

    /** Эту версию уже ставили, а запустилась старая — значит, замена не удалась. */
    fun alreadyTried(release: LauncherRelease) =
        runCatching { appliedMarker.readText().trim() == release.version }.getOrDefault(false)

    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Скачивает архив релиза и распаковывает его; возвращает папку с готовой новой версией. */
    suspend fun download(release: LauncherRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        updateDir.mkdirs()
        // Архивы прошлых версий больше не нужны
        updateDir.listFiles { f -> f.name.endsWith(".zip") && f.name != "Mint-${release.version}.zip" }
            .orEmpty().forEach { it.delete() }

        val zip = File(updateDir, "Mint-${release.version}.zip")
        var received = 0L
        Http.download(DownloadTask(release.url, zip, size = release.size)) { n ->
            received += n
            onProgress((received.toFloat() / release.size).coerceIn(0f, 1f))
        }
        onProgress(1f)

        val staged = File(updateDir, "staged")
        staged.deleteRecursively()
        unzip(zip, staged)
        if (!File(staged, "Mint.exe").isFile || !File(staged, "app/Mint.cfg").isFile) {
            zip.delete()
            throw IOException("Архив обновления повреждён: нет Mint.exe или app/Mint.cfg")
        }
        staged
    }

    /** Архив содержит папку Mint/ — её содержимое кладём прямо в [target]. */
    private fun unzip(zip: File, target: File) {
        val root = target.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                // Compress-Archive из PowerShell 5 пишет пути через обратную косую черту
                val path = entry.name.replace('\\', '/').substringAfter('/', "")
                if (path.isEmpty()) continue
                val out = File(root, path).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) throw IOException("Недопустимый путь в архиве: ${entry.name}")
                if (entry.isDirectory || entry.name.endsWith('/') || entry.name.endsWith('\\')) {
                    out.mkdirs()
                } else {
                    out.parentFile.mkdirs()
                    out.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    /**
     * Запускает скрипт замены и возвращает управление: после этого лаунчер должен сразу выйти.
     * Скрипт ждёт выхода процесса, меняет файлы (с откатом при ошибке) и запускает Mint заново.
     */
    fun applyAndRestart(release: LauncherRelease, staged: File) {
        val install = installDir ?: throw IOException("Обновление работает только в установленном лаунчере")
        val script = File(updateDir, "update.ps1")
        // BOM: без него Windows PowerShell 5 читает скрипт в ANSI и портит кириллицу
        script.writeText("﻿" + SCRIPT, Charsets.UTF_8)
        appliedMarker.writeText(release.version)

        ProcessBuilder(
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
            "-File", script.absolutePath,
            "-ParentPid", ProcessHandle.current().pid().toString(),
            "-Install", install.absolutePath,
            "-Staged", staged.absolutePath,
        )
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    private fun isWritable(dir: File) = runCatching {
        val probe = File(dir, ".mint-write-test")
        probe.writeText("")
        probe.delete()
    }.isSuccess

    private val SCRIPT = """
        param([int]${'$'}ParentPid, [string]${'$'}Install, [string]${'$'}Staged)
        ${'$'}ErrorActionPreference = 'Stop'
        ${'$'}log = Join-Path ${'$'}PSScriptRoot 'update.log'
        function Log(${'$'}m) { Add-Content -Path ${'$'}log -Value "${'$'}(Get-Date -Format s) ${'$'}m" -Encoding UTF8 }

        Log "Ожидание выхода лаунчера (${'$'}ParentPid)"
        # Лаунчер при выходе останавливает сервер — на это уходит до минуты
        try { Wait-Process -Id ${'$'}ParentPid -Timeout 180 -ErrorAction Stop } catch { }

        ${'$'}items = @(Get-ChildItem -LiteralPath ${'$'}Staged | ForEach-Object Name)
        ${'$'}moved = @()
        try {
            foreach (${'$'}i in ${'$'}items) {
                ${'$'}cur = Join-Path ${'$'}Install ${'$'}i
                ${'$'}old = "${'$'}cur.old"
                if (Test-Path -LiteralPath ${'$'}old) { Remove-Item -LiteralPath ${'$'}old -Recurse -Force }
                if (-not (Test-Path -LiteralPath ${'$'}cur)) { continue }
                # Файлы освобождаются не мгновенно после выхода процесса
                for (${'$'}t = 0; ; ${'$'}t++) {
                    try { Rename-Item -LiteralPath ${'$'}cur -NewName "${'$'}i.old"; break }
                    catch { if (${'$'}t -ge 40) { throw }; Start-Sleep -Milliseconds 500 }
                }
                ${'$'}moved += ${'$'}i
            }
            foreach (${'$'}i in ${'$'}items) { Move-Item -LiteralPath (Join-Path ${'$'}Staged ${'$'}i) -Destination (Join-Path ${'$'}Install ${'$'}i) }
            Log "Файлы заменены"
            Remove-Item -Path (Join-Path ${'$'}PSScriptRoot '*.zip') -Force -ErrorAction SilentlyContinue
        } catch {
            Log "Ошибка: ${'$'}_ — откат"
            foreach (${'$'}i in ${'$'}items) {
                ${'$'}cur = Join-Path ${'$'}Install ${'$'}i
                if (${'$'}moved -contains ${'$'}i) {
                    if (Test-Path -LiteralPath ${'$'}cur) { Remove-Item -LiteralPath ${'$'}cur -Recurse -Force }
                    Rename-Item -LiteralPath "${'$'}cur.old" -NewName ${'$'}i
                }
            }
        }

        Start-Process -FilePath (Join-Path ${'$'}Install 'Mint.exe') -WorkingDirectory ${'$'}Install
        foreach (${'$'}i in ${'$'}items) {
            ${'$'}old = Join-Path ${'$'}Install "${'$'}i.old"
            if (Test-Path -LiteralPath ${'$'}old) { Remove-Item -LiteralPath ${'$'}old -Recurse -Force -ErrorAction SilentlyContinue }
        }
        Remove-Item -LiteralPath ${'$'}Staged -Recurse -Force -ErrorAction SilentlyContinue
        Log "Готово"
    """.trimIndent()
}
