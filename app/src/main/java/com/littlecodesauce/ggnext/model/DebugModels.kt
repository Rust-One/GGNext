package com.littlecodesauce.ggnext.model

import org.json.JSONArray

enum class BpType(val code: Int, val label: String, val shortLabel: String) {
    READ(1, "只读", "只读"),
    WRITE(2, "只写", "只写"),
    RW(3, "读写", "读写"),
    EXEC(4, "执行", "执行");

    companion object {
        fun fromCode(code: Int): BpType = entries.firstOrNull { it.code == code } ?: RW
    }
}

enum class BpMode(val code: Int, val label: String, val badge: String, val desc: String) {
    PERF(0, "Perf 硬件断点", "perf", "基于内核 perf_event_open，低侵入无停顿（需 Root 与内核支持）"),
    PTRACE(1, "Ptrace 断点", "pwatch", "基于 ptrace 硬件调试寄存器，支持完整浮点寄存器与免 Root 同 UID 模式");

    companion object {
        fun fromCode(code: Int): BpMode = entries.firstOrNull { it.code == code } ?: PERF
    }
}

enum class AsmArch(
    val code: Int,
    val shortCode: String,
    val displayName: String,
    val ggPrefix: String,
    val fixedStep: Int
) {
    ARM64(0, "A8", "ARM64 (AArch64)", "~A8", 4),
    ARM(1, "A", "ARM (32-bit)", "~A", 4),
    THUMB(2, "T", "Thumb / Thumb-2", "~T", 2),
    X86(3, "x86", "x86 (32-bit)", "~X86", 1),
    X86_64(4, "x64", "x86_64 (64-bit)", "~X64", 1);

    companion object {
        fun fromCode(code: Int): AsmArch = entries.firstOrNull { it.code == code } ?: ARM64
    }
}

data class BpRegEntry(
    val name: String,
    val value: Long
) {
    val hex: String
        get() = java.lang.Long.toUnsignedString(value, 16).uppercase()
}

data class BpFpRegEntry(
    val name: String,
    val hexValue: String
)

data class BpCallFrame(
    val address: Long,
    val moduleName: String,
    val moduleOffset: Long
) {
    val addressHex: String
        get() = java.lang.Long.toUnsignedString(address, 16).uppercase()

    val offsetHex: String
        get() = "0x" + java.lang.Long.toUnsignedString(moduleOffset, 16).uppercase()
}

data class BpHitGroup(
    val pc: Long,
    val hitType: BpType,
    val tid: Int,
    val arch: String,
    val moduleName: String,
    val moduleOffset: Long,
    val insnHex: String,
    val disasmText: String,
    val count: Long,
    val sp: Long,
    val lr: Long,
    val gprs: List<BpRegEntry>,
    val fprs: List<BpFpRegEntry>,
    val callstack: List<BpCallFrame>
) {
    val pcHex: String get() = java.lang.Long.toUnsignedString(pc, 16).uppercase()
    val spHex: String get() = java.lang.Long.toUnsignedString(sp, 16).uppercase()
    val lrHex: String get() = java.lang.Long.toUnsignedString(lr, 16).uppercase()
}

data class BpSession(
    val id: Int,
    val pid: Int,
    val address: Long,
    val type: BpType,
    val size: Int,
    val mode: BpMode,
    val captureCallstack: Boolean,
    val running: Boolean,
    val paused: Boolean,
    val attachedThreads: Int,
    val totalThreads: Int,
    val totalHits: Long,
    val tracerPid: Int,
    val sampleTids: List<Int>,
    val lastError: String,
    val hits: List<BpHitGroup>
) {
    val addressHex: String
        get() = java.lang.Long.toUnsignedString(address, 16).uppercase()

    val isActive: Boolean
        get() = running && !paused
}

data class AsmPackStatus(
    val installed: Boolean = false,
    val keystoneReady: Boolean = false,
    val capstoneReady: Boolean = false,
    val abi: String = "",
    val libDir: String = "",
    val versionInfo: String = "",
    val error: String = ""
) {
    val fullyReady: Boolean get() = keystoneReady && capstoneReady
}

object BpSnapshotParser {

    fun parse(
        json: String,
        disassembleHex: (arch: String, pc: Long, insnHex: String) -> String
    ): List<BpSession> {
        if (json.isBlank() || json.trim() == "[]") return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val out = ArrayList<BpSession>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val tidsArr = obj.optJSONArray("sampleTids")
                val sampleTids = buildList {
                    if (tidsArr != null) {
                        for (t in 0 until tidsArr.length()) add(tidsArr.optInt(t))
                    }
                }
                val hitsArr = obj.optJSONArray("hits")
                val hits = buildList {
                    if (hitsArr != null) {
                        for (h in 0 until hitsArr.length()) {
                            val ho = hitsArr.getJSONObject(h)
                            val pc = ho.optLong("pc", 0L)
                            val arch = ho.optString("arch", "aarch64")
                            val insnHex = ho.optString("insnHex", "")
                            val disasm = if (insnHex.isNotEmpty()) {
                                disassembleHex(arch, pc, insnHex)
                            } else ""

                            val gprsArr = ho.optJSONArray("gprs")
                            val gprs = buildList {
                                if (gprsArr != null) {
                                    for (r in 0 until gprsArr.length()) {
                                        val ro = gprsArr.getJSONObject(r)
                                        add(BpRegEntry(ro.optString("n"), ro.optLong("v")))
                                    }
                                }
                            }
                            val fprsArr = ho.optJSONArray("fprs")
                            val fprs = buildList {
                                if (fprsArr != null) {
                                    for (r in 0 until fprsArr.length()) {
                                        val ro = fprsArr.getJSONObject(r)
                                        add(BpFpRegEntry(ro.optString("n"), ro.optString("v")))
                                    }
                                }
                            }
                            val stackArr = ho.optJSONArray("callstack")
                            val callstack = buildList {
                                if (stackArr != null) {
                                    for (c in 0 until stackArr.length()) {
                                        val co = stackArr.getJSONObject(c)
                                        add(
                                            BpCallFrame(
                                                address = co.optLong("addr"),
                                                moduleName = co.optString("mod", "[anon]"),
                                                moduleOffset = co.optLong("off", 0L)
                                            )
                                        )
                                    }
                                }
                            }
                            add(
                                BpHitGroup(
                                    pc = pc,
                                    hitType = BpType.fromCode(ho.optInt("hitType", 3)),
                                    tid = ho.optInt("tid", 0),
                                    arch = arch,
                                    moduleName = ho.optString("module", "[anon]"),
                                    moduleOffset = ho.optLong("offset", 0L),
                                    insnHex = insnHex,
                                    disasmText = disasm,
                                    count = ho.optLong("count", 0L),
                                    sp = ho.optLong("sp", 0L),
                                    lr = ho.optLong("lr", 0L),
                                    gprs = gprs,
                                    fprs = fprs,
                                    callstack = callstack
                                )
                            )
                        }
                    }
                }
                out.add(
                    BpSession(
                        id = obj.optInt("id"),
                        pid = obj.optInt("pid"),
                        address = obj.optLong("address"),
                        type = BpType.fromCode(obj.optInt("type", 3)),
                        size = obj.optInt("size", 4),
                        mode = BpMode.fromCode(obj.optInt("mode", 0)),
                        captureCallstack = obj.optBoolean("captureCallstack", true),
                        running = obj.optBoolean("running", false),
                        paused = obj.optBoolean("paused", false),
                        attachedThreads = obj.optInt("attachedThreads", 0),
                        totalThreads = obj.optInt("totalThreads", 0),
                        totalHits = obj.optLong("totalHits", 0L),
                        tracerPid = obj.optInt("tracerPid", 0),
                        sampleTids = sampleTids,
                        lastError = obj.optString("lastError", ""),
                        hits = hits
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }
}
