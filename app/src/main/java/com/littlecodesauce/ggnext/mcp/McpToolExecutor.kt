package com.littlecodesauce.ggnext.mcp

import android.content.Context
import com.littlecodesauce.ggnext.core.Session
import com.littlecodesauce.ggnext.data.ProcessScanner
import com.littlecodesauce.ggnext.engine.AsmEngineManager
import com.littlecodesauce.ggnext.engine.Formatter
import com.littlecodesauce.ggnext.engine.MemoryEngine
import com.littlecodesauce.ggnext.engine.NumberParser
import com.littlecodesauce.ggnext.model.BpMode
import com.littlecodesauce.ggnext.model.BpType
import com.littlecodesauce.ggnext.model.MemoryItem
import com.littlecodesauce.ggnext.model.TargetProcess
import com.littlecodesauce.ggnext.model.ValueType
import com.littlecodesauce.ggnext.ui.model.RegionCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class McpToolCallResult(
    val text: String,
    val isError: Boolean = false
)

data class McpToolParamSpec(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String
)

data class McpToolSummary(
    val name: String,
    val category: String,
    val description: String,
    val params: List<McpToolParamSpec> = emptyList()
)

private data class PatchRecord(
    val id: Int,
    val address: Long,
    val originalBytes: ByteArray,
    val patchedBytes: ByteArray,
    val note: String,
    val timestamp: String
)

/**
 * GGNext 本地 MCP 工具注册表与执行器（25 项真实工具）：
 * - 全部工具描述与参数提示词均使用规范英文，面向大模型工具调用优化；
 * - 所有工具均直接打通 Session / MemoryEngine / AsmEngineManager / ProcessScanner / Shell 真实链路。
 */
object McpToolExecutor {

    private val engineLock = Any()
    private val patchHistory = ArrayList<PatchRecord>()
    private var nextPatchId = 1

    private val cachedDefinitions: JSONArray by lazy { buildToolDefinitions() }

    val toolSummaries: List<McpToolSummary> by lazy {
        val defs = cachedDefinitions
        val categoryMap = mapOf(
            "get_status" to "System",
            "list_processes" to "Process",
            "attach_process" to "Process",
            "process_control" to "Process",
            "list_regions" to "Memory",
            "set_search_range" to "Memory",
            "read_memory" to "Memory",
            "write_memory" to "Memory",
            "view_memory" to "Memory",
            "allocate_memory_page" to "Memory",
            "copy_memory" to "Memory",
            "dump_memory" to "Memory",
            "patch_memory" to "Edit",
            "freeze_value" to "Edit",
            "search_memory" to "Search",
            "fuzzy_search" to "Search",
            "pointer_search" to "Search",
            "get_search_results" to "List",
            "manage_search_results" to "List",
            "clear_search" to "List",
            "manage_saved_results" to "List",
            "assemble" to "ASM",
            "disassemble" to "ASM",
            "manage_breakpoints" to "Debug",
            "bp_modify_register" to "Debug"
        )
        List(defs.length()) { i ->
            val obj = defs.getJSONObject(i)
            val name = obj.getString("name")
            val desc = obj.getString("description")
            val schema = obj.optJSONObject("inputSchema") ?: JSONObject()
            val props = schema.optJSONObject("properties") ?: JSONObject()
            val reqArr = schema.optJSONArray("required")
            val reqSet = HashSet<String>()
            if (reqArr != null) {
                for (r in 0 until reqArr.length()) reqSet.add(reqArr.optString(r))
            }
            val paramList = ArrayList<McpToolParamSpec>()
            val keys = props.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val pObj = props.optJSONObject(k) ?: continue
                paramList.add(
                    McpToolParamSpec(
                        name = k,
                        type = pObj.optString("type", "string"),
                        required = k in reqSet,
                        description = pObj.optString("description", "")
                    )
                )
            }
            McpToolSummary(
                name = name,
                category = categoryMap[name] ?: "General",
                description = desc,
                params = paramList
            )
        }
    }

    fun toolDefinitions(): JSONArray = cachedDefinitions

    private fun buildToolDefinitions(): JSONArray = JSONArray().apply {
        put(toolDef(
            name = "get_status",
            desc = "Get current GGNext runtime status including work mode (root/no_root), attached target process, architecture, search result count, saved list count, breakpoints count, selected regions, and assembler/disassembler status.",
            props = JSONObject(),
            required = emptyList()
        ))
        put(toolDef(
            name = "list_processes",
            desc = "List running Android processes visible under the current work mode.",
            props = JSONObject()
                .put("query", prop("string", "Optional case-insensitive filter matching process name, package name, or PID."))
                .put("limit", prop("integer", "Max processes to return (default 50, max 200).")),
            required = emptyList()
        ))
        put(toolDef(
            name = "attach_process",
            desc = "Attach GGNext memory engine to a target process by PID or package name.",
            props = JSONObject()
                .put("pid", prop("integer", "Target process PID."))
                .put("package_name", prop("string", "Target package name or process label (used when pid is omitted).")),
            required = emptyList()
        ))
        put(toolDef(
            name = "process_control",
            desc = "Pause (SIGSTOP), resume (SIGCONT), or terminate (SIGKILL) the currently attached target process.",
            props = JSONObject()
                .put("action", prop("string", "Control action: pause, resume, or kill.")),
            required = listOf("action")
        ))
        put(toolDef(
            name = "list_regions",
            desc = "List memory regions (/proc/<pid>/maps) of the attached process and aggregated module base addresses.",
            props = JSONObject()
                .put("region_codes", prop("string", "Optional comma-separated region codes to filter, e.g. 'Ca,A,Xa,Cd'."))
                .put("module_filter", prop("string", "Optional substring filter on module/pathname, e.g. 'libil2cpp.so'."))
                .put("offset", prop("integer", "Pagination offset (default 0)."))
                .put("limit", prop("integer", "Max regions to return (default 100, max 500).")),
            required = emptyList()
        ))
        put(toolDef(
            name = "set_search_range",
            desc = "Update active memory region selection used by subsequent searches.",
            props = JSONObject()
                .put("regions", prop("string", "Comma-separated region codes (e.g. 'Ca,A,Cd,Cb,Xa'), or 'default', or 'all'.")),
            required = listOf("regions")
        ))
        put(toolDef(
            name = "read_memory",
            desc = "Read raw hex bytes and decoded typed values at an address in the attached process.",
            props = JSONObject()
                .put("address", prop("string", "Target address in hex (e.g. '0x7a12345678') or decimal."))
                .put("size", prop("integer", "Number of raw bytes to read (default 16, max 4096)."))
                .put("type", prop("string", "Optional typed decode: byte, word, dword, qword, float, double, utf8, utf16.")),
            required = listOf("address")
        ))
        put(toolDef(
            name = "write_memory",
            desc = "Write raw hex bytes or a typed value directly to an address in the attached process.",
            props = JSONObject()
                .put("address", prop("string", "Target address in hex or decimal."))
                .put("hex_bytes", prop("string", "Hex byte string to write (e.g. '1F 20 03 D5'). Takes precedence over value/type."))
                .put("value", prop("string", "Numeric value string to write when hex_bytes is omitted (e.g. '999', '3.14', '0x10')."))
                .put("type", prop("string", "Value type for numeric write: byte, word, dword, qword, float, double, xor (default dword).")),
            required = listOf("address")
        ))
        put(toolDef(
            name = "patch_memory",
            desc = "Apply a reversible memory patch (auto-saving original bytes before writing), list recorded patches, or roll back a previous patch.",
            props = JSONObject()
                .put("action", prop("string", "Action: apply, rollback, or list."))
                .put("address", prop("string", "Target address in hex (required for apply)."))
                .put("hex_bytes", prop("string", "Replacement hex bytes (required for apply)."))
                .put("note", prop("string", "Optional label describing this patch."))
                .put("patch_id", prop("integer", "Patch ID to rollback (defaults to most recent patch if omitted).")),
            required = listOf("action")
        ))
        put(toolDef(
            name = "view_memory",
            desc = "Inspect a structured memory window around an address (4-byte aligned rows with hex, dword, float, pointer tag, ascii, and optional disassembly).",
            props = JSONObject()
                .put("address", prop("string", "Start address in hex or decimal."))
                .put("bytes", prop("integer", "Window size in bytes (default 128, min 4, max 1024)."))
                .put("disasm_arch", prop("string", "Optional disassembly arch: arm64, arm, thumb, x86, x86_64.")),
            required = listOf("address")
        ))
        put(toolDef(
            name = "search_memory",
            desc = "Search or refine memory in the attached process. Supports exact values, ranges (A~B), group search (A;B:512), XOR (AXK), hex (1234h), and text.",
            props = JSONObject()
                .put("expression", prop("string", "Search query expression, e.g. '100', '10~20', '100;200:512', '50X4'."))
                .put("type", prop("string", "Value type: auto, byte, word, dword, qword, float, double, xor (default dword)."))
                .put("refine", prop("boolean", "If true, refine existing search results; if false, start a new search (default false)."))
                .put("sign", prop("string", "Comparison operator: eq, neq, gt, gte, lt, lte (default eq)."))
                .put("regions", prop("string", "Optional comma-separated region codes (e.g. 'Ca,A,Cd,Cb'). Defaults to active selection."))
                .put("memory_from", prop("string", "Optional lower address bound (hex)."))
                .put("memory_to", prop("string", "Optional upper address bound (hex)."))
                .put("encrypted_xor", prop("boolean", "Enable encrypted value search mode (default false)."))
                .put("preview_limit", prop("integer", "Max sample results returned inline (default 50, max 200).")),
            required = listOf("expression")
        ))
        put(toolDef(
            name = "fuzzy_search",
            desc = "Perform unknown initial memory snapshot or fuzzy change refinement (increased, decreased, changed, unchanged, delta).",
            props = JSONObject()
                .put("action", prop("string", "Action: init, increased, decreased, changed, unchanged, eq, neq, gt, lt, delta."))
                .put("type", prop("string", "Value type: byte, word, dword, qword, float, double (default dword)."))
                .put("delta", prop("string", "Optional delta value when action is 'delta'."))
                .put("regions", prop("string", "Optional comma-separated region codes for init."))
                .put("preview_limit", prop("integer", "Max sample results returned inline (default 50, max 200).")),
            required = listOf("action")
        ))
        put(toolDef(
            name = "pointer_search",
            desc = "Perform a single-level (depth 1) pointer search or refinement finding addresses whose pointer value falls within [target_address - max_offset, target_address].",
            props = JSONObject()
                .put("target_address", prop("string", "Target address in hex to find pointers referencing it."))
                .put("max_offset", prop("integer", "Maximum positive byte offset from pointer to target_address (default 512)."))
                .put("pointer_type", prop("string", "Pointer width: auto, qword (64-bit), or dword (32-bit). Defaults to auto."))
                .put("refine", prop("boolean", "If true, refine existing search results instead of scanning all regions (default false)."))
                .put("regions", prop("string", "Optional comma-separated region codes to scan."))
                .put("preview_limit", prop("integer", "Max sample results returned inline (default 50, max 200).")),
            required = listOf("target_address")
        ))
        put(toolDef(
            name = "get_search_results",
            desc = "Fetch a paginated slice of current search results with live refreshed memory values.",
            props = JSONObject()
                .put("offset", prop("integer", "Start index (default 0)."))
                .put("limit", prop("integer", "Number of items (default 50, max 200).")),
            required = emptyList()
        ))
        put(toolDef(
            name = "manage_search_results",
            desc = "Manage the current search result list: remove specific addresses, save top/sliced results to the saved list, or clear all results.",
            props = JSONObject()
                .put("action", prop("string", "Action: save_to_list, remove_addresses, or clear."))
                .put("addresses", prop("string", "Comma or semicolon-separated hex addresses (for remove_addresses)."))
                .put("offset", prop("integer", "Start offset in search results (for save_to_list, default 0)."))
                .put("count", prop("integer", "Number of search results to save (for save_to_list, default 20, max 200)."))
                .put("note", prop("string", "Optional note/label applied to saved entries.")),
            required = listOf("action")
        ))
        put(toolDef(
            name = "clear_search",
            desc = "Clear current search results and release native candidate buffers.",
            props = JSONObject(),
            required = emptyList()
        ))
        put(toolDef(
            name = "manage_saved_results",
            desc = "Read and manage the saved address list: list entries (with live values), add an address, remove addresses, update note, or clear all.",
            props = JSONObject()
                .put("action", prop("string", "Action: list, add, remove, update_note, or clear."))
                .put("address", prop("string", "Target address in hex (for add or update_note)."))
                .put("addresses", prop("string", "Comma or semicolon-separated hex addresses (for remove)."))
                .put("type", prop("string", "Value type for add: byte, word, dword, qword, float, double, xor (default dword)."))
                .put("note", prop("string", "Note/description text (for add or update_note).")),
            required = listOf("action")
        ))
        put(toolDef(
            name = "assemble",
            desc = "Assemble instructions into machine code bytes (arm64, arm, thumb, x86, x86_64) and optionally write them directly to target process memory.",
            props = JSONObject()
                .put("code", prop("string", "Assembly instruction(s), e.g. 'MOV X0, #1; RET' or 'NOP'."))
                .put("arch", prop("string", "Architecture: arm64, arm, thumb, x86, x86_64 (defaults to target arch)."))
                .put("address", prop("string", "Base address for PC-relative encoding and optional memory write (default '0x0')."))
                .put("write_to_address", prop("boolean", "If true, write assembled bytes directly to the specified address in target process (default false).")),
            required = listOf("code")
        ))
        put(toolDef(
            name = "disassemble",
            desc = "Disassemble either raw hex machine code or live bytes read from an address in the attached process.",
            props = JSONObject()
                .put("address", prop("string", "Base address in hex (required when reading target memory, optional for hex_bytes)."))
                .put("hex_bytes", prop("string", "Optional raw hex bytes to disassemble instead of reading target memory."))
                .put("bytes", prop("integer", "Bytes to read from target process if hex_bytes is omitted (default 64, max 1024)."))
                .put("arch", prop("string", "Architecture: arm64, arm, thumb, x86, x86_64 (defaults to target arch)."))
                .put("max_instructions", prop("integer", "Max instructions to decode (default 32, max 256).")),
            required = emptyList()
        ))
        put(toolDef(
            name = "allocate_memory_page",
            desc = "Allocate a new memory page inside the target process via cross-process SYS_mmap with custom RWX permissions.",
            props = JSONObject()
                .put("hint_address", prop("string", "Optional preferred hint address in hex (default '0x0' for kernel choice)."))
                .put("size", prop("integer", "Allocation size in bytes (default 4096, page-aligned)."))
                .put("read", prop("boolean", "PROT_READ permission (default true)."))
                .put("write", prop("boolean", "PROT_WRITE permission (default true)."))
                .put("exec", prop("boolean", "PROT_EXEC permission (default false).")),
            required = emptyList()
        ))
        put(toolDef(
            name = "copy_memory",
            desc = "Copy a block of memory from one address to another within the attached process.",
            props = JSONObject()
                .put("from_address", prop("string", "Source address in hex."))
                .put("to_address", prop("string", "Destination address in hex."))
                .put("size", prop("integer", "Number of bytes to copy (1..16777216).")),
            required = listOf("from_address", "to_address", "size")
        ))
        put(toolDef(
            name = "dump_memory",
            desc = "Dump a memory range [start_address, end_address) of the attached process to a file on storage.",
            props = JSONObject()
                .put("start_address", prop("string", "Start address in hex."))
                .put("end_address", prop("string", "End address in hex (exclusive)."))
                .put("output_path", prop("string", "Output file path (defaults to app cache dir dump file).")),
            required = listOf("start_address", "end_address")
        ))
        put(toolDef(
            name = "freeze_value",
            desc = "Lock (freeze) or unlock an address value using the native locker.",
            props = JSONObject()
                .put("action", prop("string", "Action: lock, unlock, unlock_all."))
                .put("address", prop("string", "Target address in hex (required for lock/unlock)."))
                .put("value", prop("string", "Value to freeze (required for lock)."))
                .put("type", prop("string", "Value type: byte, word, dword, qword, float, double (default dword)."))
                .put("freeze_mode", prop("string", "Mode: exact, increment, decrement, range (default exact)."))
                .put("range_from", prop("string", "Min value for range clamp mode."))
                .put("range_to", prop("string", "Max value for range clamp mode.")),
            required = listOf("action")
        ))
        put(toolDef(
            name = "manage_breakpoints",
            desc = "Manage hardware breakpoints/watchpoints (Perf or Ptrace backend): add, list, pause, resume, clear_hits, remove, stop_all.",
            props = JSONObject()
                .put("action", prop("string", "Action: add, list, pause, resume, clear_hits, remove, stop_all."))
                .put("address", prop("string", "Breakpoint address in hex (required for add)."))
                .put("bp_type", prop("string", "Type for add: read, write, rw, exec (default rw)."))
                .put("size", prop("integer", "Watchpoint byte length for add: 1, 2, 4, 8 (default 4)."))
                .put("mode", prop("string", "Backend mode for add: perf or ptrace (default from settings)."))
                .put("capture_callstack", prop("boolean", "Capture callstack on hit (default true)."))
                .put("bp_id", prop("integer", "Breakpoint ID for pause, resume, clear_hits, remove.")),
            required = listOf("action")
        ))
        put(toolDef(
            name = "bp_modify_register",
            desc = "Modify a general-purpose or floating-point register value on a thread hit by an active hardware breakpoint.",
            props = JSONObject()
                .put("bp_id", prop("integer", "Active breakpoint session ID."))
                .put("tid", prop("integer", "Target thread ID (tid) from breakpoint hit."))
                .put("hit_pc", prop("string", "Hit PC address in hex for snapshot cache sync."))
                .put("reg_name", prop("string", "Register name, e.g. 'x0', 'pc', 'v0', 'rax'."))
                .put("hex_value", prop("string", "New register value in hex (without or with 0x prefix)."))
                .put("is_fp", prop("boolean", "True if modifying a floating-point/SIMD register (default false).")),
            required = listOf("bp_id", "tid", "reg_name", "hex_value")
        ))
    }

    fun executeTool(context: Context, name: String, args: JSONObject): McpToolCallResult {
        val started = System.currentTimeMillis()
        com.littlecodesauce.ggnext.data.AppLog.i("MCP", "executeTool $name args=${args.toString().take(400)}")
        val result = try {
            when (name) {
                "get_status" -> handleGetStatus()
                "list_processes" -> handleListProcesses(context, args)
                "attach_process" -> handleAttachProcess(context, args)
                "process_control" -> handleProcessControl(args)
                "list_regions" -> handleListRegions(args)
                "set_search_range" -> handleSetSearchRange(args)
                "read_memory" -> handleReadMemory(args)
                "write_memory" -> handleWriteMemory(args)
                "patch_memory" -> handlePatchMemory(args)
                "view_memory" -> handleViewMemory(args)
                "search_memory" -> handleSearchMemory(args)
                "fuzzy_search" -> handleFuzzySearch(args)
                "pointer_search" -> handlePointerSearch(args)
                "get_search_results" -> handleGetSearchResults(args)
                "manage_search_results" -> handleManageSearchResults(args)
                "clear_search" -> handleClearSearch()
                "manage_saved_results" -> handleManageSavedResults(args)
                "assemble" -> handleAssemble(args)
                "disassemble" -> handleDisassemble(args)
                "allocate_memory_page" -> handleAllocatePage(args)
                "copy_memory" -> handleCopyMemory(args)
                "dump_memory" -> handleDumpMemory(context, args)
                "freeze_value" -> handleFreezeValue(args)
                "manage_breakpoints" -> handleManageBreakpoints(args)
                "bp_modify_register" -> handleBpModifyRegister(args)
                else -> McpToolCallResult("Unknown tool: $name", isError = true)
            }
        } catch (t: Throwable) {
            com.littlecodesauce.ggnext.data.AppLog.e("MCP", "executeTool $name 异常", t)
            McpToolCallResult("Tool '$name' failed: ${t.message ?: t.javaClass.simpleName}", isError = true)
        }
        com.littlecodesauce.ggnext.data.AppLog.i(
            "MCP",
            "executeTool $name 完成 ${System.currentTimeMillis() - started}ms isError=${result.isError} out=${result.text.take(300)}"
        )
        return result
    }

    private fun handleGetStatus(): McpToolCallResult {
        val ui = Session.ui.value
        val eng = Session.engineForMcp()
        val asm = AsmEngineManager.status
        val out = JSONObject().apply {
            put("work_mode", ui.workMode.code)
            put("root_available", ui.hasRootPermission)
            put("attached", eng.attachedOk && eng.pid > 0)
            put("pid", eng.pid)
            put("process_paused", ui.processPaused)
            put("target_label", ui.target?.label ?: "")
            put("target_package", ui.target?.packageName ?: "")
            put("target_64bit", eng.x64)
            put("root_daemon_active", eng.rootDaemonActive)
            put("native_backend_active", eng.nativeBackendActive)
            put("search_result_total", ui.totalCount)
            put("saved_count", ui.saved.size)
            put("breakpoints_count", ui.breakpoints.size)
            put("selected_regions", JSONArray(ui.selectedRegions.toList()))
            put("asm_engine", JSONObject().apply {
                put("installed", asm.installed)
                put("keystone_ready", asm.keystoneReady)
                put("capstone_ready", asm.capstoneReady)
                put("abi", asm.abi)
            })
        }
        return McpToolCallResult(out.toString(2))
    }

    private fun handleListProcesses(context: Context, args: JSONObject): McpToolCallResult {
        val query = args.optString("query", "").trim().lowercase(Locale.ROOT)
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val procs = ProcessScanner.scan(context)
        val filtered = if (query.isEmpty()) procs else procs.filter {
            it.label.lowercase(Locale.ROOT).contains(query) ||
                it.packageName.lowercase(Locale.ROOT).contains(query) ||
                it.pid.toString().contains(query)
        }
        val arr = JSONArray()
        filtered.take(limit).forEach { p ->
            arr.put(JSONObject().apply {
                put("pid", p.pid)
                put("package_name", p.packageName)
                put("label", p.label)
                put("x64", p.x64)
                put("uid", p.uid)
                put("memory_bytes", p.sizeBytes)
                put("is_virtual", p.isVirtual)
            })
        }
        val out = JSONObject()
            .put("total_matched", filtered.size)
            .put("returned", arr.length())
            .put("processes", arr)
        return McpToolCallResult(out.toString(2))
    }

    private fun handleAttachProcess(context: Context, args: JSONObject): McpToolCallResult {
        val pidArg = args.optInt("pid", -1)
        val pkgArg = args.optString("package_name", "").trim()
        val procs = ProcessScanner.scan(context)
        val target: TargetProcess = when {
            pidArg > 0 -> procs.firstOrNull { it.pid == pidArg }
                ?: TargetProcess(
                    pid = pidArg,
                    packageName = pkgArg.ifEmpty { "pid_$pidArg" },
                    label = pkgArg.ifEmpty { "PID $pidArg" },
                    x64 = true
                )
            pkgArg.isNotEmpty() -> procs.firstOrNull {
                it.packageName.equals(pkgArg, ignoreCase = true) ||
                    it.label.equals(pkgArg, ignoreCase = true)
            } ?: procs.firstOrNull {
                it.packageName.contains(pkgArg, ignoreCase = true) ||
                    it.label.contains(pkgArg, ignoreCase = true)
            } ?: return McpToolCallResult("Process not found matching '$pkgArg'", isError = true)
            else -> return McpToolCallResult("Either 'pid' or 'package_name' must be provided", isError = true)
        }

        val ok = synchronized(engineLock) { Session.attachFromMcpSync(target) }
        val eng = Session.engineForMcp()
        val out = JSONObject().apply {
            put("attached", ok)
            put("pid", target.pid)
            put("package_name", target.packageName)
            put("label", target.label)
            put("x64", eng.x64)
            put("root_daemon_active", eng.rootDaemonActive)
            if (!ok) put("error", eng.lastAttachError.ifBlank { "Attach failed" })
        }
        return McpToolCallResult(out.toString(2), isError = !ok)
    }

    private fun handleProcessControl(args: JSONObject): McpToolCallResult {
        val action = args.optString("action", "").trim().lowercase(Locale.ROOT)
        val (ok, msg) = synchronized(engineLock) { Session.controlProcessFromMcp(action) }
        val out = JSONObject().put("success", ok).put("action", action).put("message", msg)
        return McpToolCallResult(out.toString(2), isError = !ok)
    }

    private fun handleListRegions(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached. Call attach_process first.", isError = true)
        val codeFilter = args.optString("region_codes", "").trim()
            .split(',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
        val modFilter = args.optString("module_filter", "").trim().lowercase(Locale.ROOT)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val limit = args.optInt("limit", 100).coerceIn(1, 500)

        val all = synchronized(engineLock) { eng.regions() }
        val filtered = all.filter { r ->
            val codeOk = codeFilter.isEmpty() || r.code.lowercase(Locale.ROOT) in codeFilter
            val modOk = modFilter.isEmpty() || r.path.lowercase(Locale.ROOT).contains(modFilter)
            codeOk && modOk
        }
        // Aggregate module base addresses for fast lookup by agents
        val modBases = LinkedHashMap<String, Pair<Long, Long>>()
        for (r in filtered) {
            if (r.path.isNotBlank() && !r.path.startsWith("[")) {
                val modName = r.path.substringAfterLast('/')
                val prev = modBases[modName]
                if (prev == null) {
                    modBases[modName] = r.start to r.end
                } else {
                    modBases[modName] = minOf(prev.first, r.start) to maxOf(prev.second, r.end)
                }
            }
        }
        val modArr = JSONArray()
        modBases.entries.take(60).forEach { (name, range) ->
            modArr.put(JSONObject().apply {
                put("module", name)
                put("base", toHex(range.first))
                put("end", toHex(range.second))
                put("size", range.second - range.first)
            })
        }

        val arr = JSONArray()
        filtered.drop(offset).take(limit).forEach { r ->
            arr.put(JSONObject().apply {
                put("start", toHex(r.start))
                put("end", toHex(r.end))
                put("size", r.size)
                put("perms", r.perms)
                put("code", r.code)
                put("path", r.path)
            })
        }
        val out = JSONObject()
            .put("total_regions", filtered.size)
            .put("offset", offset)
            .put("returned", arr.length())
            .put("module_bases", modArr)
            .put("regions", arr)
        return McpToolCallResult(out.toString(2))
    }

    private fun handleSetSearchRange(args: JSONObject): McpToolCallResult {
        val raw = args.optString("regions", "").trim()
        if (raw.isEmpty()) return McpToolCallResult("'regions' is required", isError = true)
        val resolved: Set<String> = when (raw.lowercase(Locale.ROOT)) {
            "default" -> RegionCatalog.DEFAULT_SELECTED
            "all" -> RegionCatalog.ALL.map { it.code }.toSet()
            else -> parseRegionSet(raw)
        }
        Session.setSelectedRegionsFromMcp(resolved)
        val out = JSONObject()
            .put("success", true)
            .put("selected_regions", JSONArray(resolved.toList()))
        return McpToolCallResult(out.toString(2))
    }

    private fun handleReadMemory(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val addr = parseAddress(args.optString("address", ""))
            ?: return McpToolCallResult("Invalid address format", isError = true)
        val size = args.optInt("size", 16).coerceIn(1, 4096)
        val typeStr = args.optString("type", "").trim().lowercase(Locale.ROOT)

        val bytes = synchronized(engineLock) { eng.readBlock(addr, size) }
        if (bytes.isEmpty()) {
            return McpToolCallResult("Failed to read memory at ${toHex(addr)}", isError = true)
        }
        val out = JSONObject().apply {
            put("address", toHex(addr))
            put("size", bytes.size)
            put("region", eng.regionCodeAt(addr))
            put("hex", bytesToHex(bytes))
            if (bytes.size >= 4) {
                val dw = bytesToLittleEndianLong(bytes, 4)
                put("dword", dw.toInt())
                put("uint32", dw and 0xFFFFFFFFL)
                put("float", java.lang.Float.intBitsToFloat(dw.toInt()).toDouble())
            }
            if (bytes.size >= 8) {
                val qw = bytesToLittleEndianLong(bytes, 8)
                put("qword", qw)
                put("double", java.lang.Double.longBitsToDouble(qw))
            }
            if (typeStr.isNotEmpty()) {
                put("typed_value", decodeTyped(bytes, typeStr))
            }
        }
        return McpToolCallResult(out.toString(2))
    }

    private fun handleWriteMemory(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val addr = parseAddress(args.optString("address", ""))
            ?: return McpToolCallResult("Invalid address format", isError = true)
        val hexStr = args.optString("hex_bytes", "").trim()

        val ok: Boolean
        val writtenBytes: Int
        if (hexStr.isNotEmpty()) {
            val bytes = hexToBytes(hexStr)
            if (bytes.isEmpty()) return McpToolCallResult("Invalid hex_bytes", isError = true)
            ok = synchronized(engineLock) { eng.writeBlock(addr, bytes) }
            writtenBytes = bytes.size
        } else {
            val valStr = args.optString("value", "").trim()
            if (valStr.isEmpty()) return McpToolCallResult("Provide either 'hex_bytes' or 'value'", isError = true)
            val typeFlag = parseValueType(args.optString("type", "dword"))
            val rawBits = parseTypedValueBits(valStr, typeFlag)
                ?: return McpToolCallResult("Cannot parse value '$valStr' as ${ValueType.shortName(typeFlag)}", isError = true)
            writtenBytes = ValueType.sizeOf(typeFlag)
            ok = synchronized(engineLock) { eng.write(addr, writtenBytes, rawBits) }
        }
        val out = JSONObject()
            .put("success", ok)
            .put("address", toHex(addr))
            .put("bytes_written", if (ok) writtenBytes else 0)
        return McpToolCallResult(out.toString(2), isError = !ok)
    }

    private fun handlePatchMemory(args: JSONObject): McpToolCallResult {
        val action = args.optString("action", "list").trim().lowercase(Locale.ROOT)
        return when (action) {
            "list" -> {
                val arr = JSONArray()
                synchronized(engineLock) {
                    patchHistory.reversed().take(50).forEach { p ->
                        arr.put(JSONObject().apply {
                            put("patch_id", p.id)
                            put("address", toHex(p.address))
                            put("original_hex", bytesToHex(p.originalBytes))
                            put("patched_hex", bytesToHex(p.patchedBytes))
                            put("note", p.note)
                            put("timestamp", p.timestamp)
                        })
                    }
                }
                McpToolCallResult(JSONObject().put("count", arr.length()).put("patches", arr).toString(2))
            }
            "apply" -> {
                val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
                val addr = parseAddress(args.optString("address", ""))
                    ?: return McpToolCallResult("Invalid address", isError = true)
                val newBytes = hexToBytes(args.optString("hex_bytes", ""))
                if (newBytes.isEmpty()) return McpToolCallResult("Invalid hex_bytes", isError = true)
                val note = args.optString("note", "").trim()
                val result = synchronized(engineLock) {
                    val orig = eng.readBlock(addr, newBytes.size)
                    if (orig.size != newBytes.size) return@synchronized null to "Failed to snapshot original bytes at ${toHex(addr)}"
                    val wrote = eng.writeBlock(addr, newBytes)
                    if (!wrote) return@synchronized null to "Failed to write patch bytes at ${toHex(addr)}"
                    val rec = PatchRecord(
                        id = nextPatchId++,
                        address = addr,
                        originalBytes = orig,
                        patchedBytes = newBytes,
                        note = note,
                        timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                    )
                    patchHistory.add(rec)
                    if (patchHistory.size > 100) patchHistory.removeAt(0)
                    rec to ""
                }
                val rec = result.first
                if (rec == null) return McpToolCallResult(result.second, isError = true)
                val out = JSONObject()
                    .put("success", true)
                    .put("patch_id", rec.id)
                    .put("address", toHex(rec.address))
                    .put("original_hex", bytesToHex(rec.originalBytes))
                    .put("patched_hex", bytesToHex(rec.patchedBytes))
                McpToolCallResult(out.toString(2))
            }
            "rollback" -> {
                val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
                val targetId = args.optInt("patch_id", -1)
                val outcome = synchronized(engineLock) {
                    val idx = if (targetId > 0) patchHistory.indexOfLast { it.id == targetId } else patchHistory.lastIndex
                    if (idx < 0) return@synchronized null to "No matching patch record found to rollback"
                    val rec = patchHistory[idx]
                    val ok = eng.writeBlock(rec.address, rec.originalBytes)
                    if (!ok) return@synchronized null to "Failed to write original bytes back to ${toHex(rec.address)}"
                    patchHistory.removeAt(idx)
                    rec to ""
                }
                val rec = outcome.first
                if (rec == null) return McpToolCallResult(outcome.second, isError = true)
                val out = JSONObject()
                    .put("success", true)
                    .put("rolled_back_patch_id", rec.id)
                    .put("address", toHex(rec.address))
                    .put("restored_hex", bytesToHex(rec.originalBytes))
                McpToolCallResult(out.toString(2))
            }
            else -> McpToolCallResult("Unsupported patch action '$action'", isError = true)
        }
    }

    private fun handleViewMemory(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val rawAddr = parseAddress(args.optString("address", ""))
            ?: return McpToolCallResult("Invalid address format", isError = true)
        val alignedAddr = rawAddr and -4L
        val length = args.optInt("bytes", 128).coerceIn(4, 1024)
        val alignedLen = ((length + 3) / 4) * 4
        val disasmArchStr = args.optString("disasm_arch", "").trim()
        val asmArch = if (disasmArchStr.isNotEmpty()) AsmEngineManager.archFromString(disasmArchStr) else null

        val bytes = synchronized(engineLock) { eng.readBlock(alignedAddr, alignedLen) }
        if (bytes.isEmpty()) {
            return McpToolCallResult("Failed to read memory window at ${toHex(alignedAddr)}", isError = true)
        }
        val disasmMap = if (asmArch != null && AsmEngineManager.status.capstoneReady) {
            AsmEngineManager.disassembleBlockMap(asmArch, alignedAddr, bytes, alignedLen / 2)
        } else {
            emptyMap()
        }

        val rows = JSONArray()
        var off = 0
        while (off + 4 <= bytes.size) {
            val rowAddr = alignedAddr + off
            val dw = ((bytes[off].toLong() and 0xFF)) or
                ((bytes[off + 1].toLong() and 0xFF) shl 8) or
                ((bytes[off + 2].toLong() and 0xFF) shl 16) or
                ((bytes[off + 3].toLong() and 0xFF) shl 24)
            val ptrTag = if (eng.x64 && off + 8 <= bytes.size) {
                val qw = dw or
                    ((bytes[off + 4].toLong() and 0xFF) shl 32) or
                    ((bytes[off + 5].toLong() and 0xFF) shl 40) or
                    ((bytes[off + 6].toLong() and 0xFF) shl 48) or
                    ((bytes[off + 7].toLong() and 0xFF) shl 56)
                eng.classifyPointer(qw)
            } else {
                eng.classifyPointer(dw)
            }
            val rowObj = JSONObject().apply {
                put("address", toHex(rowAddr))
                put("hex", Formatter.hex(dw, 4))
                put("dword", dw.toInt())
                put("float", java.lang.Float.intBitsToFloat(dw.toInt()).toDouble())
                put("ascii", Formatter.bytesToAscii(dw, 4))
                if (ptrTag != 0) put("pointer_flag", ptrTag)
                disasmMap[rowAddr]?.let { put("disasm", it) }
            }
            rows.put(rowObj)
            off += 4
        }
        val out = JSONObject()
            .put("base_address", toHex(alignedAddr))
            .put("region", eng.regionCodeAt(alignedAddr))
            .put("bytes_read", bytes.size)
            .put("rows", rows)
        return McpToolCallResult(out.toString(2))
    }

    private fun handleSearchMemory(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val expr = args.optString("expression", "").trim()
        if (expr.isEmpty()) return McpToolCallResult("'expression' is required", isError = true)

        val typeFlag = parseValueType(args.optString("type", "dword"))
        val sign = parseSearchSign(args.optString("sign", "eq"))
        val refine = args.optBoolean("refine", false)
        val encryptedXor = args.optBoolean("encrypted_xor", false)
        val previewLimit = args.optInt("preview_limit", 50).coerceIn(1, 200)
        val regions = parseRegionSet(args.optString("regions", ""))
        val memFrom = parseAddress(args.optString("memory_from", "")) ?: 0L
        val memTo = parseAddress(args.optString("memory_to", "")) ?: Long.MAX_VALUE

        val q = NumberParser.parse(expr, typeFlag, sign)
        val firstNum = q.numbers.firstOrNull() ?: 0L
        val criteria = MemoryEngine.Criteria(
            type = q.baseType and 0x7F,
            value = firstNum,
            from = firstNum,
            to = q.to.firstOrNull() ?: firstNum,
            isRange = q.kind == NumberParser.Kind.RANGE,
            notRange = q.notRange,
            sign = q.sign,
            xorKey = q.xorKey,
            xorOffset = q.xorOffset,
            orderedGroup = if (q.kind == NumberParser.Kind.GROUP) q.numbers else emptyList(),
            groupTo = if (q.kind == NumberParser.Kind.GROUP) q.to else emptyList(),
            groupOps = if (q.kind == NumberParser.Kind.GROUP) q.groupOps else emptyList(),
            ordered = q.ordered,
            groupTypes = if (q.kind == NumberParser.Kind.GROUP) q.types else emptyList(),
            area = q.area,
            memoryFrom = memFrom,
            memoryTo = memTo,
            rawExpression = expr,
            encryptedXor = encryptedXor,
            selectedRegions = regions,
            isText = q.kind == NumberParser.Kind.TEXT,
            textBytes = q.textBytes,
            textUtf16 = q.textUtf16
        )

        val prev = if (refine) Session.ui.value.results else null
        val res = synchronized(engineLock) { eng.search(criteria, previous = prev) }
        Session.syncSearchResultsFromMcp(res.items, res.totalFound)

        val arr = JSONArray()
        res.items.take(previewLimit).forEach { arr.put(itemToJson(it)) }
        val out = JSONObject()
            .put("refine", refine)
            .put("total_count", res.totalFound)
            .put("returned", arr.length())
            .put("results", arr)
        return McpToolCallResult(out.toString(2))
    }

    private fun handleFuzzySearch(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val action = args.optString("action", "").trim().lowercase(Locale.ROOT)
        val typeFlag = parseValueType(args.optString("type", "dword"))
        val previewLimit = args.optInt("preview_limit", 50).coerceIn(1, 200)
        val regions = parseRegionSet(args.optString("regions", ""))

        val isInit = action == "init" || action == "unknown"
        val sign = when (action) {
            "init", "unknown" -> ValueType.SIGN_EQUAL
            "increased", "inc", "gt" -> ValueType.SIGN_GREATER_EQ
            "decreased", "dec", "lt" -> ValueType.SIGN_LESS_EQ
            "changed", "neq" -> ValueType.SIGN_NOT_EQUAL
            "unchanged", "eq" -> ValueType.SIGN_EQUAL
            "delta" -> ValueType.SIGN_NOT_EQUAL
            else -> return McpToolCallResult("Unsupported fuzzy action '$action'", isError = true)
        }
        val deltaStr = args.optString("delta", "").trim()
        val deltaBits = if (deltaStr.isNotEmpty()) parseTypedValueBits(deltaStr, typeFlag) ?: 0L else 0L

        val prev = if (isInit) null else Session.ui.value.results
        val res = synchronized(engineLock) {
            eng.fuzzySearch(
                type = typeFlag,
                sign = sign,
                delta = deltaBits,
                previous = prev,
                selectedRegions = regions
            )
        }
        Session.syncSearchResultsFromMcp(res.items, res.totalFound)
        val arr = JSONArray()
        res.items.take(previewLimit).forEach { arr.put(itemToJson(it)) }
        val out = JSONObject()
            .put("action", action)
            .put("total_count", res.totalFound)
            .put("returned", arr.length())
            .put("results", arr)
        return McpToolCallResult(out.toString(2))
    }

    private fun handlePointerSearch(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val targetAddr = parseAddress(args.optString("target_address", ""))
            ?: return McpToolCallResult("Invalid target_address", isError = true)
        val maxOffset = args.optLong("max_offset", 512L).coerceIn(0L, 64L * 1024L * 1024L)
        val ptrType = when (args.optString("pointer_type", "auto").trim().lowercase(Locale.ROOT)) {
            "dword", "32", "32bit" -> ValueType.DWORD
            "qword", "64", "64bit" -> ValueType.QWORD
            else -> ValueType.AUTO
        }
        val refine = args.optBoolean("refine", false)
        val previewLimit = args.optInt("preview_limit", 50).coerceIn(1, 200)
        val regions = parseRegionSet(args.optString("regions", ""))

        val prev = if (refine) Session.ui.value.results else null
        val res = synchronized(engineLock) {
            eng.pointerSearch(
                targetAddress = targetAddr,
                maxOffset = maxOffset,
                type = ptrType,
                previous = prev,
                selectedRegions = regions
            )
        }
        Session.syncSearchResultsFromMcp(res.items, res.totalFound)
        val arr = JSONArray()
        res.items.take(previewLimit).forEach { item ->
            val obj = itemToJson(item)
            val offset = targetAddr - item.value
            obj.put("pointer_value", toHex(item.value))
            obj.put("offset", toHex(offset))
            arr.put(obj)
        }
        val out = JSONObject()
            .put("depth", 1)
            .put("target_address", toHex(targetAddr))
            .put("max_offset", maxOffset)
            .put("refine", refine)
            .put("total_count", res.totalFound)
            .put("returned", arr.length())
            .put("results", arr)
        return McpToolCallResult(out.toString(2))
    }

    private fun handleGetSearchResults(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val offset = args.optInt("offset", 0).coerceAtLeast(0)
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val ui = Session.ui.value
        val slice = ui.results.drop(offset).take(limit)
        val refreshed = if (slice.isNotEmpty()) {
            val liveMap = synchronized(engineLock) {
                eng.batchReadRawValues(slice.map { it.address to it.size })
            }
            slice.map { item -> liveMap[item.address]?.let { item.copy(value = it) } ?: item }
        } else {
            emptyList()
        }
        val arr = JSONArray()
        refreshed.forEach { arr.put(itemToJson(it)) }
        val out = JSONObject()
            .put("total_count", ui.totalCount)
            .put("offset", offset)
            .put("returned", arr.length())
            .put("results", arr)
        return McpToolCallResult(out.toString(2))
    }

    private fun handleManageSearchResults(args: JSONObject): McpToolCallResult {
        val action = args.optString("action", "").trim().lowercase(Locale.ROOT)
        return when (action) {
            "clear" -> handleClearSearch()
            "remove_addresses" -> {
                val addrs = parseAddressList(args.optString("addresses", ""))
                if (addrs.isEmpty()) return McpToolCallResult("No valid addresses provided", isError = true)
                val removed = synchronized(engineLock) { Session.removeSearchAddressesFromMcp(addrs) }
                McpToolCallResult(JSONObject().put("success", true).put("removed_count", removed).toString(2))
            }
            "save_to_list" -> {
                val offset = args.optInt("offset", 0).coerceAtLeast(0)
                val count = args.optInt("count", 20).coerceIn(1, 200)
                val note = args.optString("note", "").trim()
                val slice = Session.ui.value.results.drop(offset).take(count).map { item ->
                    if (note.isNotEmpty()) item.copy(name = note, note = note) else item
                }
                val added = synchronized(engineLock) { Session.saveItemsFromMcp(slice) }
                McpToolCallResult(JSONObject().put("success", true).put("saved_count", slice.size).put("newly_added", added).toString(2))
            }
            else -> McpToolCallResult("Unsupported action '$action'", isError = true)
        }
    }

    private fun handleClearSearch(): McpToolCallResult {
        synchronized(engineLock) { Session.clearResults() }
        return McpToolCallResult(JSONObject().put("cleared", true).toString(2))
    }

    private fun handleManageSavedResults(args: JSONObject): McpToolCallResult {
        val action = args.optString("action", "list").trim().lowercase(Locale.ROOT)
        return when (action) {
            "list" -> {
                val saved = Session.ui.value.saved
                val eng = Session.engineForMcp()
                val refreshed = if (eng.attachedOk && eng.pid > 0 && saved.isNotEmpty()) {
                    val liveMap = synchronized(engineLock) {
                        eng.batchReadRawValues(saved.map { it.address to it.size })
                    }
                    saved.map { item -> liveMap[item.address]?.let { item.copy(value = it) } ?: item }
                } else saved
                val arr = JSONArray()
                refreshed.forEach { item ->
                    arr.put(itemToJson(item).apply {
                        put("note", item.note.ifBlank { item.name })
                        put("frozen", item.freeze)
                        put("freeze_type", item.freezeType)
                    })
                }
                McpToolCallResult(JSONObject().put("count", arr.length()).put("saved", arr).toString(2))
            }
            "add" -> {
                val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
                val addr = parseAddress(args.optString("address", ""))
                    ?: return McpToolCallResult("Invalid address", isError = true)
                val typeFlag = parseValueType(args.optString("type", "dword"))
                val note = args.optString("note", "").trim()
                val sz = ValueType.sizeOf(typeFlag)
                val rawVal = synchronized(engineLock) { eng.read(addr, sz) }
                val region = eng.regionCodeAt(addr)
                val item = MemoryItem(address = addr, value = rawVal, flags = typeFlag, name = note, note = note, region = region)
                val added = synchronized(engineLock) { Session.saveItemsFromMcp(listOf(item)) }
                McpToolCallResult(JSONObject().put("success", true).put("address", toHex(addr)).put("newly_added", added).toString(2))
            }
            "remove" -> {
                val addrs = parseAddressList(args.optString("addresses", args.optString("address", "")))
                if (addrs.isEmpty()) return McpToolCallResult("No valid addresses provided", isError = true)
                val removed = synchronized(engineLock) { Session.removeSavedAddressesFromMcp(addrs) }
                McpToolCallResult(JSONObject().put("success", true).put("removed_count", removed).toString(2))
            }
            "update_note" -> {
                val addr = parseAddress(args.optString("address", ""))
                    ?: return McpToolCallResult("Invalid address", isError = true)
                val note = args.optString("note", "").trim()
                val ok = synchronized(engineLock) { Session.updateSavedNoteFromMcp(addr, note) }
                McpToolCallResult(JSONObject().put("success", ok).put("address", toHex(addr)).put("note", note).toString(2), isError = !ok)
            }
            "clear" -> {
                val cleared = synchronized(engineLock) { Session.clearSavedFromMcp() }
                McpToolCallResult(JSONObject().put("success", true).put("cleared_count", cleared).toString(2))
            }
            else -> McpToolCallResult("Unsupported saved-list action '$action'", isError = true)
        }
    }

    private fun handleAssemble(args: JSONObject): McpToolCallResult {
        val code = args.optString("code", "").trim()
        if (code.isEmpty()) return McpToolCallResult("'code' is required", isError = true)
        val eng = Session.engineForMcp()
        val archStr = args.optString("arch", "").trim()
        val arch = if (archStr.isNotEmpty()) AsmEngineManager.archFromString(archStr) else AsmEngineManager.defaultArchForDevice(eng.x64)
        val addr = parseAddress(args.optString("address", "0")) ?: 0L
        val writeBack = args.optBoolean("write_to_address", false)

        val asmResult = AsmEngineManager.assemble(arch, addr, code)
        val bytes = asmResult.getOrElse { err ->
            return McpToolCallResult("Assemble failed: ${err.message}", isError = true)
        }
        var written = false
        if (writeBack) {
            val activeEng = requireAttached() ?: return McpToolCallResult("Cannot write_to_address: no process attached", isError = true)
            written = synchronized(engineLock) { activeEng.writeBlock(addr, bytes) }
        }
        val out = JSONObject()
            .put("arch", arch.name)
            .put("address", toHex(addr))
            .put("byte_count", bytes.size)
            .put("hex_bytes", bytesToHex(bytes))
            .put("written_to_memory", written)
        return McpToolCallResult(out.toString(2), isError = writeBack && !written)
    }

    private fun handleDisassemble(args: JSONObject): McpToolCallResult {
        val eng = Session.engineForMcp()
        val archStr = args.optString("arch", "").trim()
        val arch = if (archStr.isNotEmpty()) AsmEngineManager.archFromString(archStr) else AsmEngineManager.defaultArchForDevice(eng.x64)
        val addr = parseAddress(args.optString("address", "0")) ?: 0L
        val hexStr = args.optString("hex_bytes", "").trim()
        val maxInsns = args.optInt("max_instructions", 32).coerceIn(1, 256)

        val bytes: ByteArray = if (hexStr.isNotEmpty()) {
            hexToBytes(hexStr)
        } else {
            val activeEng = requireAttached() ?: return McpToolCallResult("No target process attached (or provide hex_bytes).", isError = true)
            val len = args.optInt("bytes", 64).coerceIn(2, 1024)
            synchronized(engineLock) { activeEng.readBlock(addr, len) }
        }
        if (bytes.isEmpty()) return McpToolCallResult("No bytes available to disassemble", isError = true)
        if (!AsmEngineManager.status.capstoneReady) {
            return McpToolCallResult("Disassembler pack is not loaded. Import the ASM pack in Extensions first.", isError = true)
        }

        val map = AsmEngineManager.disassembleBlockMap(arch, addr, bytes, maxInsns)
        val arr = JSONArray()
        map.entries.sortedBy { it.key }.forEach { (insnAddr, insnText) ->
            arr.put(JSONObject().put("address", toHex(insnAddr)).put("instruction", insnText))
        }
        val out = JSONObject()
            .put("arch", arch.name)
            .put("base_address", toHex(addr))
            .put("count", arr.length())
            .put("instructions", arr)
        return McpToolCallResult(out.toString(2))
    }

    private fun handleAllocatePage(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val hint = parseAddress(args.optString("hint_address", "0")) ?: 0L
        val size = args.optInt("size", 4096).coerceIn(4096, 16 * 1024 * 1024)
        val read = args.optBoolean("read", true)
        val write = args.optBoolean("write", true)
        val exec = args.optBoolean("exec", false)

        val (allocAddr, msg) = synchronized(engineLock) {
            eng.allocatePage(hint, read, write, exec, size)
        }
        val ok = allocAddr != 0L
        val out = JSONObject()
            .put("success", ok)
            .put("address", if (ok) toHex(allocAddr) else "0x0")
            .put("size", size)
            .put("perms", "${if (read) 'r' else '-'}${if (write) 'w' else '-'}${if (exec) 'x' else '-'}")
            .put("message", msg)
        return McpToolCallResult(out.toString(2), isError = !ok)
    }

    private fun handleCopyMemory(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val from = parseAddress(args.optString("from_address", ""))
            ?: return McpToolCallResult("Invalid from_address", isError = true)
        val to = parseAddress(args.optString("to_address", ""))
            ?: return McpToolCallResult("Invalid to_address", isError = true)
        val size = args.optLong("size", 0L).coerceIn(1L, 16L * 1024L * 1024L)
        val ok = synchronized(engineLock) { eng.copyMemory(from, to, size) }
        val out = JSONObject()
            .put("success", ok)
            .put("from_address", toHex(from))
            .put("to_address", toHex(to))
            .put("size", size)
        return McpToolCallResult(out.toString(2), isError = !ok)
    }

    private fun handleDumpMemory(context: Context, args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val start = parseAddress(args.optString("start_address", ""))
            ?: return McpToolCallResult("Invalid start_address", isError = true)
        val end = parseAddress(args.optString("end_address", ""))
            ?: return McpToolCallResult("Invalid end_address", isError = true)
        if (end <= start) return McpToolCallResult("end_address must be greater than start_address", isError = true)
        val outPath = args.optString("output_path", "").trim().ifEmpty {
            File(context.cacheDir, "mcp_dump_${toHex(start)}_${toHex(end)}.bin").absolutePath
        }
        val ok = synchronized(engineLock) { eng.dumpMemory(start, end, outPath) }
        val out = JSONObject()
            .put("success", ok)
            .put("start_address", toHex(start))
            .put("end_address", toHex(end))
            .put("output_path", outPath)
        return McpToolCallResult(out.toString(2), isError = !ok)
    }

    private fun handleFreezeValue(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val action = args.optString("action", "").trim().lowercase(Locale.ROOT)
        when (action) {
            "unlock_all" -> {
                synchronized(engineLock) { eng.clearNativeFreezes() }
                return McpToolCallResult(JSONObject().put("action", "unlock_all").put("success", true).toString(2))
            }
            "unlock" -> {
                val addr = parseAddress(args.optString("address", ""))
                    ?: return McpToolCallResult("Invalid address", isError = true)
                val typeFlag = parseValueType(args.optString("type", "dword"))
                synchronized(engineLock) { eng.setFreeze(addr, typeFlag, 0L, enabled = false) }
                return McpToolCallResult(JSONObject().put("action", "unlock").put("address", toHex(addr)).put("success", true).toString(2))
            }
            "lock" -> {
                val addr = parseAddress(args.optString("address", ""))
                    ?: return McpToolCallResult("Invalid address", isError = true)
                val valStr = args.optString("value", "").trim()
                if (valStr.isEmpty()) return McpToolCallResult("'value' is required for lock", isError = true)
                val typeFlag = parseValueType(args.optString("type", "dword"))
                val bits = parseTypedValueBits(valStr, typeFlag)
                    ?: return McpToolCallResult("Invalid numeric value '$valStr'", isError = true)
                val fMode = when (args.optString("freeze_mode", "exact").lowercase(Locale.ROOT)) {
                    "increment", "inc" -> 1
                    "decrement", "dec" -> 2
                    "range", "clamp" -> 3
                    else -> 0
                }
                val fromBits = args.optString("range_from", "").takeIf { it.isNotBlank() }?.let { parseTypedValueBits(it, typeFlag) } ?: 0L
                val toBits = args.optString("range_to", "").takeIf { it.isNotBlank() }?.let { parseTypedValueBits(it, typeFlag) } ?: 0L
                synchronized(engineLock) {
                    eng.setFreeze(addr, typeFlag, bits, enabled = true, freezeType = fMode, freezeFrom = fromBits, freezeTo = toBits)
                }
                return McpToolCallResult(JSONObject().put("action", "lock").put("address", toHex(addr)).put("success", true).toString(2))
            }
            else -> return McpToolCallResult("Unsupported freeze action '$action'", isError = true)
        }
    }

    private fun handleManageBreakpoints(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val action = args.optString("action", "list").trim().lowercase(Locale.ROOT)
        return when (action) {
            "list" -> {
                val snaps = synchronized(engineLock) { eng.bpSnapshot() }
                val arr = JSONArray()
                snaps.forEach { bp ->
                    val hitsArr = JSONArray()
                    bp.hits.take(30).forEach { h ->
                        val gprObj = JSONObject()
                        h.gprs.forEach { gprObj.put(it.name, toHex(it.value)) }
                        val fprObj = JSONObject()
                        h.fprs.forEach { fprObj.put(it.name, it.hexValue) }
                        hitsArr.put(JSONObject().apply {
                            put("pc", toHex(h.pc))
                            put("tid", h.tid)
                            put("hit_count", h.count)
                            put("module", h.moduleName)
                            put("module_offset", toHex(h.moduleOffset))
                            put("disasm", h.disasmText)
                            put("gprs", gprObj)
                            put("fprs", fprObj)
                        })
                    }
                    arr.put(JSONObject().apply {
                        put("id", bp.id)
                        put("pid", bp.pid)
                        put("address", toHex(bp.address))
                        put("type", bp.type.name)
                        put("mode", bp.mode.name)
                        put("size", bp.size)
                        put("running", bp.running)
                        put("paused", bp.paused)
                        put("attached_threads", bp.attachedThreads)
                        put("total_threads", bp.totalThreads)
                        put("total_hits", bp.totalHits)
                        put("last_error", bp.lastError)
                        put("hits", hitsArr)
                    })
                }
                McpToolCallResult(JSONObject().put("count", arr.length()).put("breakpoints", arr).toString(2))
            }
            "add" -> {
                val addr = parseAddress(args.optString("address", ""))
                    ?: return McpToolCallResult("Invalid address", isError = true)
                val bpType = when (args.optString("bp_type", "rw").lowercase(Locale.ROOT)) {
                    "read", "r" -> BpType.READ
                    "write", "w" -> BpType.WRITE
                    "exec", "x" -> BpType.EXEC
                    else -> BpType.RW
                }
                val size = args.optInt("size", 4).let { if (it in listOf(1, 2, 4, 8)) it else 4 }
                val modeStr = args.optString("mode", "").lowercase(Locale.ROOT)
                val bpMode = when (modeStr) {
                    "perf" -> BpMode.PERF
                    "ptrace" -> BpMode.PTRACE
                    else -> if (Session.ui.value.settings["bp_backend"] == "1") BpMode.PTRACE else BpMode.PERF
                }
                val callstack = args.optBoolean("capture_callstack", true)
                val (id, err) = synchronized(engineLock) { eng.bpAdd(addr, bpType, size, bpMode, callstack) }
                Session.refreshBreakpointsNow()
                val ok = id > 0
                McpToolCallResult(
                    JSONObject().put("success", ok).put("bp_id", id).put("error", err).toString(2),
                    isError = !ok
                )
            }
            "pause", "resume" -> {
                val bpId = args.optInt("bp_id", 0)
                val paused = action == "pause"
                val ok = synchronized(engineLock) {
                    if (bpId > 0) eng.bpSetPaused(bpId, paused) else eng.bpSetAllPaused(paused)
                }
                Session.refreshBreakpointsNow()
                McpToolCallResult(JSONObject().put("success", ok).put("bp_id", bpId).put("paused", paused).toString(2), isError = !ok)
            }
            "clear_hits" -> {
                val bpId = args.optInt("bp_id", 0)
                synchronized(engineLock) { eng.bpClearHits(bpId) }
                Session.refreshBreakpointsNow()
                McpToolCallResult(JSONObject().put("success", true).put("bp_id", bpId).toString(2))
            }
            "remove" -> {
                val bpId = args.optInt("bp_id", 0)
                if (bpId <= 0) return McpToolCallResult("'bp_id' > 0 is required for remove", isError = true)
                val ok = synchronized(engineLock) { eng.bpRemove(bpId) }
                Session.refreshBreakpointsNow()
                McpToolCallResult(JSONObject().put("success", ok).put("bp_id", bpId).toString(2), isError = !ok)
            }
            "stop_all" -> {
                synchronized(engineLock) { eng.bpStopAll() }
                Session.refreshBreakpointsNow()
                McpToolCallResult(JSONObject().put("success", true).toString(2))
            }
            else -> McpToolCallResult("Unsupported breakpoint action '$action'", isError = true)
        }
    }

    private fun handleBpModifyRegister(args: JSONObject): McpToolCallResult {
        val eng = requireAttached() ?: return McpToolCallResult("No target process attached.", isError = true)
        val bpId = args.optInt("bp_id", 0)
        val tid = args.optInt("tid", 0)
        val hitPc = parseAddress(args.optString("hit_pc", "0")) ?: 0L
        val regName = args.optString("reg_name", "").trim()
        val hexVal = args.optString("hex_value", "").trim().removePrefix("0x").removePrefix("0X")
        val isFp = args.optBoolean("is_fp", false)
        if (bpId <= 0 || tid <= 0 || regName.isEmpty() || hexVal.isEmpty()) {
            return McpToolCallResult("bp_id, tid, reg_name, and hex_value are required", isError = true)
        }
        val (ok, err) = synchronized(engineLock) { eng.bpSetRegister(bpId, tid, hitPc, regName, hexVal, isFp) }
        Session.refreshBreakpointsNow()
        val out = JSONObject()
            .put("success", ok)
            .put("bp_id", bpId)
            .put("tid", tid)
            .put("reg_name", regName)
            .put("hex_value", hexVal)
            .put("error", err)
        return McpToolCallResult(out.toString(2), isError = !ok)
    }

    // ---------------- Helpers ----------------

    private fun requireAttached(): MemoryEngine? {
        val eng = Session.engineForMcp()
        return if (eng.attachedOk && eng.pid > 0) eng else null
    }

    private fun toolDef(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject {
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", props)
        if (required.isNotEmpty()) {
            schema.put("required", JSONArray(required))
        }
        return JSONObject()
            .put("name", name)
            .put("description", desc)
            .put("inputSchema", schema)
    }

    private fun prop(type: String, desc: String): JSONObject =
        JSONObject().put("type", type).put("description", desc)

    private fun itemToJson(item: MemoryItem): JSONObject = JSONObject().apply {
        put("address", toHex(item.address))
        put("type", ValueType.shortName(item.flags))
        put("raw_value", item.value)
        put("formatted_value", Formatter.primary(item, Formatter.DEFAULT_MASK))
        put("region", item.region)
    }

    private fun parseAddress(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return try {
            when {
                s.startsWith("0x", ignoreCase = true) -> java.lang.Long.parseUnsignedLong(s.substring(2), 16)
                s.endsWith("h", ignoreCase = true) -> java.lang.Long.parseUnsignedLong(s.dropLast(1), 16)
                s.any { it in 'a'..'f' || it in 'A'..'F' } -> java.lang.Long.parseUnsignedLong(s, 16)
                else -> java.lang.Long.parseUnsignedLong(s, 10)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseAddressList(raw: String): Set<Long> =
        raw.split(',', ';', ' ', '\n').mapNotNull { parseAddress(it) }.toSet()

    private fun parseValueType(raw: String): Int = when (raw.trim().lowercase(Locale.ROOT)) {
        "byte", "b", "int8" -> ValueType.BYTE
        "word", "w", "int16" -> ValueType.WORD
        "dword", "d", "int32" -> ValueType.DWORD
        "qword", "q", "int64" -> ValueType.QWORD
        "float", "f", "f32" -> ValueType.FLOAT
        "double", "e", "f64" -> ValueType.DOUBLE
        "xor", "x" -> ValueType.XOR
        "auto", "a" -> ValueType.AUTO
        else -> ValueType.DWORD
    }

    private fun parseSearchSign(raw: String): Int = when (raw.trim().lowercase(Locale.ROOT)) {
        "neq", "!=" -> ValueType.SIGN_NOT_EQUAL
        "gt", ">", "gte", ">=" -> ValueType.SIGN_GREATER_EQ
        "lt", "<", "lte", "<=" -> ValueType.SIGN_LESS_EQ
        else -> ValueType.SIGN_EQUAL
    }

    private fun parseRegionSet(raw: String): Set<String> {
        val s = raw.trim()
        if (s.isEmpty()) return Session.ui.value.selectedRegions.ifEmpty { RegionCatalog.DEFAULT_SELECTED }
        return s.split(',').mapNotNull { token ->
            val clean = token.trim()
            RegionCatalog.ALL.firstOrNull { it.code.equals(clean, ignoreCase = true) }?.code
        }.toSet().ifEmpty { RegionCatalog.DEFAULT_SELECTED }
    }

    private fun parseTypedValueBits(raw: String, typeFlag: Int): Long? {
        val s = raw.trim()
        return try {
            when (typeFlag and 0x7F) {
                ValueType.FLOAT -> java.lang.Float.floatToRawIntBits(s.toFloat()).toLong() and 0xFFFFFFFFL
                ValueType.DOUBLE -> java.lang.Double.doubleToRawLongBits(s.toDouble())
                else -> {
                    if (s.startsWith("0x", ignoreCase = true)) java.lang.Long.parseUnsignedLong(s.substring(2), 16)
                    else s.toLong()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeTyped(bytes: ByteArray, typeStr: String): Any = when (typeStr) {
        "byte", "int8" -> bytes[0].toInt()
        "word", "int16" -> if (bytes.size >= 2) bytesToLittleEndianLong(bytes, 2).toShort().toInt() else 0
        "dword", "int32" -> if (bytes.size >= 4) bytesToLittleEndianLong(bytes, 4).toInt() else 0
        "qword", "int64" -> if (bytes.size >= 8) bytesToLittleEndianLong(bytes, 8) else 0L
        "float", "f32" -> if (bytes.size >= 4) java.lang.Float.intBitsToFloat(bytesToLittleEndianLong(bytes, 4).toInt()).toDouble() else 0.0
        "double", "f64" -> if (bytes.size >= 8) java.lang.Double.longBitsToDouble(bytesToLittleEndianLong(bytes, 8)) else 0.0
        "utf8" -> String(bytes, Charsets.UTF_8).substringBefore('\u0000')
        "utf16" -> String(bytes, Charsets.UTF_16LE).substringBefore('\u0000')
        else -> bytesToHex(bytes)
    }

    private fun bytesToLittleEndianLong(bytes: ByteArray, count: Int): Long {
        var v = 0L
        val n = minOf(count, bytes.size, 8)
        for (i in 0 until n) {
            v = v or ((bytes[i].toLong() and 0xFF) shl (i * 8))
        }
        return v
    }

    private fun bytesToHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append("0123456789ABCDEF"[v ushr 4])
            append("0123456789ABCDEF"[v and 0x0F])
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace("0x", "", ignoreCase = true).replace(Regex("[^0-9a-fA-F]"), "")
        if (clean.length < 2 || clean.length % 2 != 0) return ByteArray(0)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun toHex(addr: Long): String =
        "0x" + java.lang.Long.toUnsignedString(addr, 16).uppercase(Locale.ROOT)
}
