package com.littlecodesauce.ggnext.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局运行日志（v0.9.1 增强）：
 * - 分级 D/I/W/E/FATAL，毫秒时间戳 + 线程名；
 * - 内存环形缓冲 + 本地文件持久化（自动轮转），进程重启后仍可导出完整历史；
 * - 注册全局未捕获异常处理器，Java 层崩溃写入 FATAL 日志后再交给原处理器；
 * - 提供导出到文件 / 复制全文，供用户反馈问题时附带。
 */
object AppLog {
    private const val MAX_LINES = 2000
    private const val MAX_FILE_BYTES = 1024L * 1024L

    enum class Level { D, I, W, E, F }

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<String>()
    private val _flow = MutableStateFlow<List<String>>(emptyList())
    val flow: StateFlow<List<String>> = _flow.asStateFlow()

    @Volatile
    private var logFile: File? = null
    @Volatile
    private var appContext: Context? = null

    /** 在 Session.init 中尽早调用，启用文件持久化与全局崩溃捕获。 */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        // 配置统一到 Download/GGNext；日志落盘到其 logs 子目录
        val dir = File(AppPaths.LOGS_DIR)
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            logFile = File(dir, "ggnext.log")
        }
        installCrashHandler()
        add("App", "GGNext v0.9.4 日志已启用 (pid=${android.os.Process.myPid()}) path=${dir.absolutePath}")
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previous))
    }

    private class CrashHandler(private val previous: Thread.UncaughtExceptionHandler?) :
        Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching { add("CRASH", "线程 ${t.name} 未捕获异常：${e.javaClass.simpleName}: ${e.message}", e) }
            runCatching { flush() }
            if (previous != null) {
                previous.uncaughtException(t, e)
            } else {
                runCatching { Thread.currentThread().threadGroup.uncaughtException(t, e) }
            }
        }
    }

    @JvmOverloads
    @Synchronized
    fun add(tag: String, message: String, throwable: Throwable? = null, level: Level = Level.I) {
        val head = "${fmt.format(Date())}  [${Thread.currentThread().name}]  ${level.name}/$tag: "
        val body = if (throwable == null) message else {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            "$message\n${sw.toString().take(2400)}"
        }
        val line = head + body.replace('\r', ' ')
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        _flow.value = buffer.toList()
        appendToFile(line)
    }

    fun d(tag: String, message: String) = add(tag, message, level = Level.D)
    fun i(tag: String, message: String) = add(tag, message, level = Level.I)
    fun w(tag: String, message: String) = add(tag, message, level = Level.W)
    fun e(tag: String, message: String, t: Throwable? = null) = add(tag, message, t, Level.E)
    fun fatal(tag: String, message: String, t: Throwable? = null) = add(tag, message, t, Level.F)

    @Synchronized
    private fun appendToFile(line: String) {
        val f = logFile ?: return
        try {
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                runCatching { f.renameTo(File(f.parentFile, "ggnext.log.old")) }
            }
            FileOutputStream(f, true).use { it.write((line + "\n").toByteArray(Charsets.UTF_8)) }
        } catch (_: Throwable) {
            // 日志失败绝不影响业务
        }
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _flow.value = emptyList()
        runCatching { logFile?.let { FileOutputStream(it).use { out -> out.write(ByteArray(0)) } } }
        add("App", "日志已清空")
    }

    /** 内存缓冲文本（UI 实时显示）。 */
    @Synchronized
    fun text(): String = buffer.joinToString("\n")

    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    /** 完整日志文本：优先读持久化文件（含崩溃前的历史），失败时回退到内存缓冲。 */
    @Synchronized
    fun fullText(): String {
        val f = logFile
        if (f != null && f.isFile) {
            val disk = runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
            if (!disk.isNullOrBlank()) return disk
        }
        return text()
    }

    /** 导出到文件：默认导出到统一配置路径 Download/GGNext，失败时回退应用外部私有目录。返回绝对路径。 */
    @Synchronized
    fun exportToFile(): String? {
        val ctx = appContext ?: return null
        val name = "ggnext_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        val candidates = ArrayList<File>()
        runCatching {
            val pub = File(AppPaths.LOG_EXPORT_DIR)
            if (!pub.exists()) pub.mkdirs()
            candidates.add(File(pub, name))
        }
        ctx.getExternalFilesDir(null)?.let { candidates.add(File(it, name)) }
        val content = fullText()
        for (out in candidates) {
            try {
                out.writeText(content, Charsets.UTF_8)
                return out.absolutePath
            } catch (_: Throwable) {
                // 尝试下一个位置
            }
        }
        return null
    }

    @Synchronized
    fun flush() {
        // 每次 add 已同步落盘，保留此方法供崩溃处理器显式调用
    }
}
