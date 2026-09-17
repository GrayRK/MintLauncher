package mint.core

import java.io.File
import java.util.concurrent.TimeUnit

data class HardwareInfo(val gpu: String?, val cpu: String?, val os: String)

/** Определение конфигурации ПК средствами ОС: реестр Windows, sysctl/system_profiler на macOS, /proc и lspci на Linux. */
object Hardware {
    private val osName = System.getProperty("os.name").orEmpty()
    private val isWindows = osName.startsWith("Windows")
    private val isMac = osName.startsWith("Mac")

    fun detect(): HardwareInfo = HardwareInfo(
        gpu = runCatching { gpu() }.getOrNull()?.takeIf { it.isNotBlank() },
        cpu = runCatching { cpu() }.getOrNull()?.takeIf { it.isNotBlank() },
        os = os(),
    )

    /** Тёмная ли тема приложений в ОС. На Linux единого способа нет — считаем светлой. */
    fun systemDarkTheme(): Boolean = runCatching {
        when {
            isWindows -> run("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme")
                ?.lines()?.firstOrNull { it.trim().startsWith("AppsUseLightTheme") }
                ?.trim()?.endsWith("0x0") == true
            isMac -> run("defaults", "read", "-g", "AppleInterfaceStyle")?.trim() == "Dark"
            else -> false
        }
    }.getOrDefault(false)

    private fun os(): String {
        val arch = when (System.getProperty("os.arch")) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "ARM64"
            else -> System.getProperty("os.arch").orEmpty()
        }
        val name = when {
            isMac -> "macOS ${System.getProperty("os.version")}"
            isWindows -> osName
            else -> linuxName() ?: osName
        }
        return "$name · $arch"
    }

    private fun linuxName(): String? = File("/etc/os-release").takeIf { it.isFile }?.readLines()
        ?.firstOrNull { it.startsWith("PRETTY_NAME=") }?.substringAfter('=')?.trim('"')

    private fun cpu(): String? = when {
        isWindows -> regValues("HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "ProcessorNameString").firstOrNull()
        isMac -> run("sysctl", "-n", "machdep.cpu.brand_string")?.trim()
        else -> File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("model name") }?.substringAfter(':')
    }?.let(::cleanCpu)

    private fun cleanCpu(name: String) = name
        .replace(Regex("\\((R|TM)\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+\\d+-Core Processor"), "")
        .replace(Regex("\\s+CPU\\s+@.*"), "")
        .replace(Regex("\\s+"), " ").trim()

    private fun gpu(): String? {
        val all = when {
            isWindows -> regValues("HKLM\\SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}", "DriverDesc", recursive = true)
            isMac -> run("system_profiler", "SPDisplaysDataType")?.lines()
                ?.filter { it.trim().startsWith("Chipset Model:") }?.map { it.substringAfter(':').trim() }.orEmpty()
            else -> run("lspci")?.lines()
                ?.filter { it.contains("VGA compatible controller") || it.contains("3D controller") }
                ?.map { it.substringAfter(": ").replace(Regex("\\s*\\(rev .*\\)"), "") }.orEmpty()
        }.map { it.trim() }.distinct()
            .filterNot { it.contains("Basic Display", true) || it.contains("Virtual", true) || it.contains("Remote", true) }
        // Если есть дискретная видеокарта, показываем её
        return all.firstOrNull { Regex("NVIDIA|GeForce|Radeon RX|Radeon Pro|Arc ", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?: all.firstOrNull()
    }

    private fun regValues(key: String, value: String, recursive: Boolean = false): List<String> {
        val args = mutableListOf("reg", "query", key)
        if (recursive) args += "/s"
        args += listOf("/v", value)
        return run(*args.toTypedArray())?.lines().orEmpty()
            .map { it.trim() }
            .filter { it.startsWith("$value ") }
            .map { it.substringAfter("REG_SZ").trim() }
    }

    private fun run(vararg command: String): String? {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        return output
    }
}
