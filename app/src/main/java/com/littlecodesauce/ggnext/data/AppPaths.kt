package com.littlecodesauce.ggnext.data

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 统一配置/数据路径（v0.9.4）。
 *
 * 所有用户可见的文件路径（脚本、文件管理、dump、字体、asm 包、日志导出）
 * 都收敛到系统 Download 下的单一根目录 [ROOT]，避免 /sdcard/GGNext、
 * /sdcard/Download、应用私有目录混用带来的不一致。
 */
object AppPaths {
    /** 公有 Download 目录的绝对路径，如 /storage/emulated/0/Download */
    val DOWNLOAD: String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    /** 统一配置根目录：Download/GGNext */
    val ROOT: String = "$DOWNLOAD/GGNext"

    val LOGS_DIR: String = "$ROOT/logs"
    val SCRIPTS_DIR: String = "$ROOT/scripts"
    val DUMP_DIR: String = "$ROOT/dump"
    val SAVED_DIR: String = "$ROOT/saved"

    val SCRIPT_FILE: String = "$SCRIPTS_DIR/main.lua"
    val FILE_MANAGER_FILE: String = "$SAVED_DIR/saved_list.txt"
    val LOG_EXPORT_DIR: String = ROOT

    /** 确保根目录及常用子目录存在（忽略失败）。 */
    fun ensureDirs() {
        runCatching {
            listOf(ROOT, LOGS_DIR, SCRIPTS_DIR, DUMP_DIR, SAVED_DIR).forEach { File(it).mkdirs() }
        }
    }

    /** 供 Context 调用：优先公有 Download，失败回退应用外部私有目录。 */
    fun rootDir(context: Context): File {
        val pub = File(ROOT)
        return if (pub.exists() || pub.mkdirs()) pub else context.getExternalFilesDir(null) ?: context.filesDir
    }
}
