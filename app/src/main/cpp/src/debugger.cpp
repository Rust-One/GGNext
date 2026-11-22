#include "mem_engine/debugger.hpp"
#include "mem_engine/maps_parser.hpp"
#include "mem_engine/proc_io.hpp"

#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <elf.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <sstream>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <unistd.h>
#include <unordered_map>

#include <linux/hw_breakpoint.h>
#include <linux/perf_event.h>

#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif
#ifndef NT_PRFPREG
#define NT_PRFPREG 2
#endif
#ifndef NT_ARM_HW_BREAK
#define NT_ARM_HW_BREAK 0x402
#endif
#ifndef NT_ARM_HW_WATCH
#define NT_ARM_HW_WATCH 0x403
#endif
#ifndef PTRACE_GETREGSET
#define PTRACE_GETREGSET 0x4204
#endif
#ifndef PTRACE_SETREGSET
#define PTRACE_SETREGSET 0x4205
#endif
#ifndef PTRACE_SETHBPREGS
#define PTRACE_SETHBPREGS 30
#endif

namespace mem_engine {

namespace {

std::string hostArchName() {
#if defined(__aarch64__)
    return "aarch64";
#elif defined(__arm__)
    return "arm";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__i386__)
    return "x86";
#else
    return "unknown";
#endif
}

std::vector<int> listProcessThreads(int pid) {
    std::vector<int> tids;
    char dirPath[64];
    std::snprintf(dirPath, sizeof(dirPath), "/proc/%d/task", pid);
    DIR* d = ::opendir(dirPath);
    if (!d) return tids;
    while (struct dirent* ent = ::readdir(d)) {
        if (ent->d_name[0] < '0' || ent->d_name[0] > '9') continue;
        int tid = std::atoi(ent->d_name);
        if (tid > 0) tids.push_back(tid);
    }
    ::closedir(d);
    std::sort(tids.begin(), tids.end());
    return tids;
}

std::string baseModuleName(const std::string& path) {
    if (path.empty()) return "[anon]";
    size_t pos = path.find_last_of('/');
    if (pos != std::string::npos && pos + 1 < path.size()) {
        return path.substr(pos + 1);
    }
    return path;
}

void resolveAddressModule(
    const std::vector<VmaRegion>& maps,
    uint64_t addr,
    std::string& outModule,
    uint64_t& outOffset
) {
    outModule = "[anon]";
    outOffset = 0;
    for (const auto& r : maps) {
        if (addr >= r.startAddress && addr < r.endAddress) {
            outModule = baseModuleName(r.pathname);
            // Compute offset from earliest mapping of same module if file-backed
            uint64_t base = r.startAddress;
            if (!r.pathname.empty() && r.pathname[0] == '/') {
                for (const auto& first : maps) {
                    if (first.pathname == r.pathname) {
                        base = first.startAddress;
                        break;
                    }
                }
            }
            outOffset = addr - base;
            return;
        }
    }
}

std::string jsonEscape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) >= 0x20) out.push_back(c);
                break;
        }
    }
    return out;
}

std::string bytesToHexLower(const std::vector<uint8_t>& b) {
    static const char* kHex = "0123456789ABCDEF";
    std::string out;
    out.resize(b.size() * 2);
    for (size_t i = 0; i < b.size(); ++i) {
        out[i * 2] = kHex[(b[i] >> 4) & 0xF];
        out[i * 2 + 1] = kHex[b[i] & 0xF];
    }
    return out;
}

// Unwind frame-pointer chain from target memory using ProcIO
std::vector<CallFrameEntry> unwindFpChain(
    ProcIO& io,
    const std::vector<VmaRegion>& maps,
    uint64_t pc,
    uint64_t lr,
    uint64_t fp,
    int maxDepth = 16
) {
    std::vector<CallFrameEntry> frames;
    auto pushFrame = [&](uint64_t addr) {
        if (addr == 0) return;
        CallFrameEntry f;
        f.address = addr;
        resolveAddressModule(maps, addr, f.moduleName, f.moduleOffset);
        frames.push_back(std::move(f));
    };

    pushFrame(pc);
    if (lr != 0 && lr != pc) {
        pushFrame(lr);
    }

    uint64_t curFp = fp;
    for (int i = 0; i < maxDepth && curFp != 0 && (curFp & 0x7ULL) == 0; ++i) {
        uint64_t pair[2] = {0, 0};
        size_t got = 0;
        if (io.readMemory(static_cast<uintptr_t>(curFp), pair, sizeof(pair), &got) != EngineErrc::OK || got < sizeof(pair)) {
            break;
        }
        uint64_t nextFp = pair[0];
        uint64_t retAddr = pair[1];
        if (retAddr != 0) {
            pushFrame(retAddr);
        }
        if (nextFp <= curFp || nextFp - curFp > (1ULL << 22)) {
            break;
        }
        curFp = nextFp;
    }
    return frames;
}

// ============================================================================
// Architecture-specific Perf & Ptrace Helpers
// ============================================================================

uint32_t toPerfBpType(BpType t) {
    switch (t) {
        case BpType::READ:  return HW_BREAKPOINT_R;
        case BpType::WRITE: return HW_BREAKPOINT_W;
        case BpType::RW:    return HW_BREAKPOINT_RW;
        case BpType::EXEC:  return HW_BREAKPOINT_X;
        default:            return HW_BREAKPOINT_RW;
    }
}

uint64_t toPerfBpLen(int32_t sizeBytes, BpType t) {
    if (t == BpType::EXEC) {
#if defined(__aarch64__) || defined(__arm__)
        return HW_BREAKPOINT_LEN_4;
#else
        return HW_BREAKPOINT_LEN_1;
#endif
    }
    switch (sizeBytes) {
        case 1:  return HW_BREAKPOINT_LEN_1;
        case 2:  return HW_BREAKPOINT_LEN_2;
        case 8:  return HW_BREAKPOINT_LEN_8;
        default: return HW_BREAKPOINT_LEN_4;
    }
}

#if defined(__aarch64__)
struct Arm64HwDebugState {
    uint32_t dbg_info;
    uint32_t pad;
    struct {
        uint64_t addr;
        uint32_t ctrl;
        uint32_t pad;
    } dbg_regs[16];
};

bool installArm64HwBp(int tid, uint64_t addr, BpType type, int32_t sizeBytes, bool enable, int slot = 0) {
    const int regset = (type == BpType::EXEC) ? NT_ARM_HW_BREAK : NT_ARM_HW_WATCH;
    Arm64HwDebugState state{};
    struct iovec iov{};
    iov.iov_base = &state;
    iov.iov_len = sizeof(state);

    if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(regset)), &iov) < 0) {
        return false;
    }

    int maxSlots = static_cast<int>(state.dbg_info & 0xFFu);
    if (maxSlots <= 0) maxSlots = 1;
    if (slot >= maxSlots) slot = 0;

    if (!enable) {
        state.dbg_regs[slot].addr = 0;
        state.dbg_regs[slot].ctrl = 0;
    } else if (type == BpType::EXEC) {
        // Execution hardware breakpoint (4-byte aligned instruction)
        state.dbg_regs[slot].addr = addr & ~3ULL;
        // E=1, PMC=EL0(2<<1), BAS=0xF(<<5)
        state.dbg_regs[slot].ctrl = 1u | (2u << 1) | (0xFu << 5);
    } else {
        // Data watchpoint: align base to 8 bytes and compute byte-address-select mask
        uint64_t alignedAddr = addr & ~7ULL;
        uint32_t byteOff = static_cast<uint32_t>(addr & 7ULL);
        int32_t clampedLen = (sizeBytes == 1 || sizeBytes == 2 || sizeBytes == 8) ? sizeBytes : 4;
        if (byteOff + static_cast<uint32_t>(clampedLen) > 8u) {
            clampedLen = static_cast<int32_t>(8u - byteOff);
            if (clampedLen <= 0) clampedLen = 1;
        }
        uint32_t bas = ((1u << clampedLen) - 1u) << byteOff;
        uint32_t lsc = (type == BpType::READ) ? 1u : (type == BpType::WRITE ? 2u : 3u);
        // E=1, PAC=EL0(2<<1), LSC=(lsc<<3), BAS=(bas<<5)
        state.dbg_regs[slot].addr = alignedAddr;
        state.dbg_regs[slot].ctrl = 1u | (2u << 1) | (lsc << 3) | ((bas & 0xFFu) << 5);
    }

    iov.iov_base = &state;
    // Kernel expects exact size for active slot count: header(8) + (slot+1)*16
    iov.iov_len = 8 + static_cast<size_t>(slot + 1) * 16;
    return ::ptrace(PTRACE_SETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(regset)), &iov) == 0;
}

bool captureArm64Regs(
    int tid,
    uint64_t& outPc,
    uint64_t& outSp,
    uint64_t& outLr,
    uint64_t& outFp,
    std::vector<RegEntry>& outGprs,
    std::vector<FpRegEntry>& outFprs
) {
    struct user_pt_regs regs{};
    struct iovec iov{&regs, sizeof(regs)};
    if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) < 0) {
        return false;
    }
    outPc = regs.pc;
    outSp = regs.sp;
    outLr = regs.regs[30];
    outFp = regs.regs[29];

    outGprs.clear();
    outGprs.reserve(34);
    for (int i = 0; i <= 28; ++i) {
        outGprs.push_back({"x" + std::to_string(i), regs.regs[i]});
    }
    outGprs.push_back({"fp", regs.regs[29]});
    outGprs.push_back({"lr", regs.regs[30]});
    outGprs.push_back({"sp", regs.sp});
    outGprs.push_back({"pc", regs.pc});
    outGprs.push_back({"pstate", regs.pstate});

    struct user_fpsimd_state fpRegs{};
    struct iovec fpIov{&fpRegs, sizeof(fpRegs)};
    outFprs.clear();
    if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRFPREG)), &fpIov) == 0) {
        outFprs.reserve(32);
        for (int i = 0; i < 32; ++i) {
            uint64_t lo = static_cast<uint64_t>(fpRegs.vregs[i]);
            uint64_t hi = static_cast<uint64_t>(fpRegs.vregs[i] >> 64);
            char hex[40];
            if (hi != 0) {
                std::snprintf(hex, sizeof(hex), "%016llX%016llX",
                              static_cast<unsigned long long>(hi),
                              static_cast<unsigned long long>(lo));
            } else {
                std::snprintf(hex, sizeof(hex), "%llX", static_cast<unsigned long long>(lo));
            }
            outFprs.push_back({"v" + std::to_string(i), hex});
        }
    }
    return true;
}
#endif

#if defined(__arm__)
bool installArm32HwBp(int tid, uint64_t addr, BpType type, int32_t sizeBytes, bool enable) {
    // Positive index = breakpoint, negative index = watchpoint (-1 = addr, -2 = ctrl)
    long idxAddr = (type == BpType::EXEC) ? 1 : -1;
    long idxCtrl = (type == BpType::EXEC) ? 2 : -2;
    uint32_t alignedAddr = static_cast<uint32_t>(addr & ~3ULL);
    uint32_t ctrl = 0;
    if (enable) {
        if (type == BpType::EXEC) {
            ctrl = 1u | (2u << 1) | (0xFu << 5);
        } else {
            uint32_t off = static_cast<uint32_t>(addr & 3ULL);
            int32_t len = (sizeBytes == 1 || sizeBytes == 2) ? sizeBytes : 4;
            if (off + static_cast<uint32_t>(len) > 4u) len = static_cast<int32_t>(4u - off);
            uint32_t bas = ((1u << len) - 1u) << off;
            uint32_t lsc = (type == BpType::READ) ? 1u : (type == BpType::WRITE ? 2u : 3u);
            ctrl = 1u | (2u << 1) | (lsc << 3) | ((bas & 0xFu) << 5);
        }
    }
    if (::ptrace(PTRACE_SETHBPREGS, tid, reinterpret_cast<void*>(idxAddr), &alignedAddr) < 0) {
        return false;
    }
    return ::ptrace(PTRACE_SETHBPREGS, tid, reinterpret_cast<void*>(idxCtrl), &ctrl) == 0;
}

bool captureArm32Regs(
    int tid,
    uint64_t& outPc,
    uint64_t& outSp,
    uint64_t& outLr,
    uint64_t& outFp,
    std::vector<RegEntry>& outGprs,
    std::vector<FpRegEntry>& outFprs
) {
    struct pt_regs regs{};
    struct iovec iov{&regs, sizeof(regs)};
    if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) < 0) {
        return false;
    }
    outFp = static_cast<uint32_t>(regs.uregs[11]);
    outSp = static_cast<uint32_t>(regs.uregs[13]);
    outLr = static_cast<uint32_t>(regs.uregs[14]);
    outPc = static_cast<uint32_t>(regs.uregs[15]);
    outGprs.clear();
    for (int i = 0; i <= 12; ++i) {
        outGprs.push_back({"r" + std::to_string(i), static_cast<uint64_t>(static_cast<uint32_t>(regs.uregs[i]))});
    }
    outGprs.push_back({"sp", static_cast<uint64_t>(static_cast<uint32_t>(regs.uregs[13]))});
    outGprs.push_back({"lr", static_cast<uint64_t>(static_cast<uint32_t>(regs.uregs[14]))});
    outGprs.push_back({"pc", static_cast<uint64_t>(static_cast<uint32_t>(regs.uregs[15]))});
    outGprs.push_back({"cpsr", static_cast<uint64_t>(static_cast<uint32_t>(regs.uregs[16]))});
    outFprs.clear();
    return true;
}
#endif

#if defined(__x86_64__) || defined(__i386__)
bool installX86HwBp(int tid, uint64_t addr, BpType type, int32_t sizeBytes, bool enable) {
    const size_t dr0Off = offsetof(struct user, u_debugreg[0]);
    const size_t dr7Off = offsetof(struct user, u_debugreg[7]);
    if (!enable) {
        ::ptrace(PTRACE_POKEUSER, tid, reinterpret_cast<void*>(dr7Off), reinterpret_cast<void*>(0));
        ::ptrace(PTRACE_POKEUSER, tid, reinterpret_cast<void*>(dr0Off), reinterpret_cast<void*>(0));
        return true;
    }
    if (::ptrace(PTRACE_POKEUSER, tid, reinterpret_cast<void*>(dr0Off), reinterpret_cast<void*>(static_cast<uintptr_t>(addr))) < 0) {
        return false;
    }
    // DR7: L0=1, R/W0 (bits 16..17): 00=exec, 01=write, 11=rw/read; LEN0 (bits 18..19): 00=1, 01=2, 10=8, 11=4
    uintptr_t rwBits = (type == BpType::EXEC) ? 0u : (type == BpType::WRITE ? 1u : 3u);
    uintptr_t lenBits = 0u;
    if (type != BpType::EXEC) {
        switch (sizeBytes) {
            case 1: lenBits = 0u; break;
            case 2: lenBits = 1u; break;
            case 8: lenBits = 2u; break;
            default: lenBits = 3u; break;
        }
    }
    uintptr_t dr7 = 1u | (rwBits << 16) | (lenBits << 18);
    return ::ptrace(PTRACE_POKEUSER, tid, reinterpret_cast<void*>(dr7Off), reinterpret_cast<void*>(dr7)) == 0;
}

bool captureX86Regs(
    int tid,
    uint64_t& outPc,
    uint64_t& outSp,
    uint64_t& outLr,
    uint64_t& outFp,
    std::vector<RegEntry>& outGprs,
    std::vector<FpRegEntry>& outFprs
) {
    struct user_regs_struct regs{};
    struct iovec iov{&regs, sizeof(regs)};
    if (::ptrace(PTRACE_GETREGSET, tid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) < 0) {
        return false;
    }
    outGprs.clear();
#if defined(__x86_64__)
    outPc = regs.rip;
    outSp = regs.rsp;
    outFp = regs.rbp;
    outLr = 0;
    outGprs = {
        {"rax", regs.rax}, {"rbx", regs.rbx}, {"rcx", regs.rcx}, {"rdx", regs.rdx},
        {"rsi", regs.rsi}, {"rdi", regs.rdi}, {"rbp", regs.rbp}, {"rsp", regs.rsp},
        {"r8",  regs.r8},  {"r9",  regs.r9},  {"r10", regs.r10}, {"r11", regs.r11},
        {"r12", regs.r12}, {"r13", regs.r13}, {"r14", regs.r14}, {"r15", regs.r15},
        {"rip", regs.rip}, {"eflags", regs.eflags}
    };
#else
    outPc = regs.eip;
    outSp = regs.esp;
    outFp = regs.ebp;
    outLr = 0;
    outGprs = {
        {"eax", static_cast<uint32_t>(regs.eax)}, {"ebx", static_cast<uint32_t>(regs.ebx)},
        {"ecx", static_cast<uint32_t>(regs.ecx)}, {"edx", static_cast<uint32_t>(regs.edx)},
        {"esi", static_cast<uint32_t>(regs.esi)}, {"edi", static_cast<uint32_t>(regs.edi)},
        {"ebp", static_cast<uint32_t>(regs.ebp)}, {"esp", static_cast<uint32_t>(regs.esp)},
        {"eip", static_cast<uint32_t>(regs.eip)}, {"eflags", static_cast<uint32_t>(regs.eflags)}
    };
#endif
    outFprs.clear();
    return true;
}
#endif

} // namespace

struct BreakpointManager::Session {
    int32_t id = 0;
    int32_t pid = 0;
    uint64_t address = 0;
    BpType type = BpType::RW;
    int32_t size = 4;
    BpMode mode = BpMode::PERF;
    bool captureCallstack = true;

    std::atomic<bool> stopFlag{false};
    std::atomic<bool> pausedFlag{false};
    std::atomic<bool> runningFlag{false};

    mutable std::mutex dataMtx;
    int32_t attachedThreads = 0;
    int32_t totalThreads = 0;
    uint64_t totalHits = 0;
    int32_t tracerPid = 0;
    std::vector<int32_t> sampleTids;
    std::string lastError;
    std::unordered_map<uint64_t, BpHitGroup> hitMap;

    std::thread worker;

    void recordHit(
        uint64_t pc,
        int32_t tid,
        int32_t hitType,
        const std::vector<VmaRegion>& maps,
        ProcIO& io,
        uint64_t sp,
        uint64_t lr,
        std::vector<RegEntry>&& gprs,
        std::vector<FpRegEntry>&& fprs,
        std::vector<CallFrameEntry>&& callstack
    ) {
        std::vector<uint8_t> insnBuf(16, 0);
        size_t got = 0;
        if (pc != 0) {
            io.readMemory(static_cast<uintptr_t>(pc), insnBuf.data(), insnBuf.size(), &got);
            if (got < insnBuf.size()) insnBuf.resize(got);
        } else {
            insnBuf.clear();
        }

        std::string mod;
        uint64_t off = 0;
        resolveAddressModule(maps, pc, mod, off);

        std::lock_guard<std::mutex> lk(dataMtx);
        totalHits++;
        auto& g = hitMap[pc];
        g.pc = pc;
        g.hitType = hitType;
        g.tid = tid;
        g.arch = hostArchName();
        g.moduleName = mod;
        g.moduleOffset = off;
        if (!insnBuf.empty()) g.insnBytes = std::move(insnBuf);
        g.hitCount++;
        g.sp = sp;
        g.lr = lr;
        if (!gprs.empty()) g.gprs = std::move(gprs);
        if (!fprs.empty()) g.fprs = std::move(fprs);
        if (!callstack.empty()) g.callstack = std::move(callstack);
    }
};

BreakpointManager::~BreakpointManager() {
    stopAll();
}

int32_t BreakpointManager::addBreakpoint(
    int pid,
    uint64_t address,
    BpType type,
    int32_t sizeBytes,
    BpMode mode,
    bool captureCallstack,
    std::string* outError
) {
    if (pid <= 0 || address == 0) {
        if (outError) *outError = "无效的目标进程或断点地址";
        return -1;
    }
    int32_t validSize = (sizeBytes == 1 || sizeBytes == 2 || sizeBytes == 4 || sizeBytes == 8) ? sizeBytes : 4;

    auto sess = std::make_shared<Session>();
    {
        std::lock_guard<std::mutex> lk(mtx_);
        sess->id = nextId_++;
    }
    sess->pid = pid;
    sess->address = address;
    sess->type = type;
    sess->size = validSize;
    sess->mode = mode;
    sess->captureCallstack = captureCallstack;
    sess->tracerPid = ::getpid();
    sess->runningFlag.store(true);

    if (mode == BpMode::PERF) {
        sess->worker = std::thread(perfWorkerLoop, sess);
    } else {
        sess->worker = std::thread(ptraceWorkerLoop, sess);
    }

    // Give worker a brief window to attach threads so immediate errors can be surfaced
    std::this_thread::sleep_for(std::chrono::milliseconds(60));
    {
        std::lock_guard<std::mutex> dlk(sess->dataMtx);
        if (!sess->lastError.empty() && sess->attachedThreads == 0 && !sess->runningFlag.load()) {
            if (outError) *outError = sess->lastError;
            if (sess->worker.joinable()) sess->worker.join();
            return -1;
        }
    }

    {
        std::lock_guard<std::mutex> lk(mtx_);
        sessions_.push_back(sess);
    }
    return sess->id;
}

bool BreakpointManager::removeBreakpoint(int32_t id) {
    std::shared_ptr<Session> target;
    {
        std::lock_guard<std::mutex> lk(mtx_);
        auto it = std::find_if(sessions_.begin(), sessions_.end(), [&](const auto& s) { return s->id == id; });
        if (it == sessions_.end()) return false;
        target = *it;
        sessions_.erase(it);
    }
    target->stopFlag.store(true);
    if (target->worker.joinable()) {
        target->worker.join();
    }
    return true;
}

bool BreakpointManager::setPaused(int32_t id, bool paused) {
    std::lock_guard<std::mutex> lk(mtx_);
    for (auto& s : sessions_) {
        if (s->id == id) {
            s->pausedFlag.store(paused);
            return true;
        }
    }
    return false;
}

bool BreakpointManager::setAllPaused(bool paused) {
    std::lock_guard<std::mutex> lk(mtx_);
    for (auto& s : sessions_) {
        s->pausedFlag.store(paused);
    }
    return true;
}

bool BreakpointManager::clearHits(int32_t id) {
    std::lock_guard<std::mutex> lk(mtx_);
    for (auto& s : sessions_) {
        if (s->id == id) {
            std::lock_guard<std::mutex> dlk(s->dataMtx);
            s->hitMap.clear();
            s->totalHits = 0;
            return true;
        }
    }
    return false;
}

void BreakpointManager::clearAllHits() {
    std::lock_guard<std::mutex> lk(mtx_);
    for (auto& s : sessions_) {
        std::lock_guard<std::mutex> dlk(s->dataMtx);
        s->hitMap.clear();
        s->totalHits = 0;
    }
}

void BreakpointManager::stopAll() {
    std::vector<std::shared_ptr<Session>> copy;
    {
        std::lock_guard<std::mutex> lk(mtx_);
        copy.swap(sessions_);
    }
    for (auto& s : copy) {
        s->stopFlag.store(true);
    }
    for (auto& s : copy) {
        if (s->worker.joinable()) s->worker.join();
    }
}

std::vector<BpSessionSnapshot> BreakpointManager::snapshots() const {
    std::vector<std::shared_ptr<Session>> list;
    {
        std::lock_guard<std::mutex> lk(mtx_);
        list = sessions_;
    }
    std::vector<BpSessionSnapshot> out;
    out.reserve(list.size());
    for (const auto& s : list) {
        BpSessionSnapshot snap;
        snap.id = s->id;
        snap.pid = s->pid;
        snap.address = s->address;
        snap.type = static_cast<int32_t>(s->type);
        snap.size = s->size;
        snap.mode = static_cast<int32_t>(s->mode);
        snap.captureCallstack = s->captureCallstack;
        snap.running = s->runningFlag.load();
        snap.paused = s->pausedFlag.load();
        {
            std::lock_guard<std::mutex> dlk(s->dataMtx);
            snap.attachedThreads = s->attachedThreads;
            snap.totalThreads = s->totalThreads;
            snap.totalHits = s->totalHits;
            snap.tracerPid = s->tracerPid;
            snap.sampleTids = s->sampleTids;
            snap.lastError = s->lastError;
            snap.hits.reserve(s->hitMap.size());
            for (const auto& kv : s->hitMap) {
                snap.hits.push_back(kv.second);
            }
        }
        std::sort(snap.hits.begin(), snap.hits.end(), [](const BpHitGroup& a, const BpHitGroup& b) {
            return a.hitCount > b.hitCount;
        });
        out.push_back(std::move(snap));
    }
    return out;
}

std::string BreakpointManager::snapshotsToJson() const {
    auto list = snapshots();
    std::ostringstream oss;
    oss << "[";
    for (size_t i = 0; i < list.size(); ++i) {
        const auto& s = list[i];
        if (i > 0) oss << ",";
        oss << "{"
            << "\"id\":" << s.id
            << ",\"pid\":" << s.pid
            << ",\"address\":" << s.address
            << ",\"type\":" << s.type
            << ",\"size\":" << s.size
            << ",\"mode\":" << s.mode
            << ",\"captureCallstack\":" << (s.captureCallstack ? "true" : "false")
            << ",\"running\":" << (s.running ? "true" : "false")
            << ",\"paused\":" << (s.paused ? "true" : "false")
            << ",\"attachedThreads\":" << s.attachedThreads
            << ",\"totalThreads\":" << s.totalThreads
            << ",\"totalHits\":" << s.totalHits
            << ",\"tracerPid\":" << s.tracerPid
            << ",\"sampleTids\":[";
        for (size_t t = 0; t < s.sampleTids.size(); ++t) {
            if (t > 0) oss << ",";
            oss << s.sampleTids[t];
        }
        oss << "],\"lastError\":\"" << jsonEscape(s.lastError) << "\",\"hits\":[";
        for (size_t h = 0; h < s.hits.size(); ++h) {
            const auto& g = s.hits[h];
            if (h > 0) oss << ",";
            oss << "{"
                << "\"pc\":" << g.pc
                << ",\"hitType\":" << g.hitType
                << ",\"tid\":" << g.tid
                << ",\"arch\":\"" << jsonEscape(g.arch) << "\""
                << ",\"module\":\"" << jsonEscape(g.moduleName) << "\""
                << ",\"offset\":" << g.moduleOffset
                << ",\"insnHex\":\"" << bytesToHexLower(g.insnBytes) << "\""
                << ",\"count\":" << g.hitCount
                << ",\"sp\":" << g.sp
                << ",\"lr\":" << g.lr
                << ",\"gprs\":[";
            for (size_t r = 0; r < g.gprs.size(); ++r) {
                if (r > 0) oss << ",";
                oss << "{\"n\":\"" << jsonEscape(g.gprs[r].name) << "\",\"v\":" << g.gprs[r].value << "}";
            }
            oss << "],\"fprs\":[";
            for (size_t r = 0; r < g.fprs.size(); ++r) {
                if (r > 0) oss << ",";
                oss << "{\"n\":\"" << jsonEscape(g.fprs[r].name) << "\",\"v\":\"" << jsonEscape(g.fprs[r].hexValue) << "\"}";
            }
            oss << "],\"callstack\":[";
            for (size_t c = 0; c < g.callstack.size(); ++c) {
                if (c > 0) oss << ",";
                oss << "{\"addr\":" << g.callstack[c].address
                    << ",\"mod\":\"" << jsonEscape(g.callstack[c].moduleName) << "\""
                    << ",\"off\":" << g.callstack[c].moduleOffset << "}";
            }
            oss << "]}";
        }
        oss << "]}";
    }
    oss << "]";
    return oss.str();
}

// ============================================================================
// Mode 1: Linux perf_event_open Hardware Breakpoint Worker
// ============================================================================

void BreakpointManager::perfWorkerLoop(std::shared_ptr<Session> sess) {
    ProcIO io;
    io.attach(sess->pid);
    auto maps = MapsParser::parseProcessMaps(sess->pid, 0, false);

    const long pageSize = ::sysconf(_SC_PAGESIZE);
    const size_t dataPages = 4;
    const size_t mmapSize = static_cast<size_t>(pageSize) * (1 + dataPages);
    const size_t dataSize = static_cast<size_t>(pageSize) * dataPages;

    struct PerfThreadSlot {
        int tid = 0;
        int fd = -1;
        void* mmapBase = nullptr;
        uint64_t sampleType = 0;
        uint64_t regsMask = 0;
    };

    std::vector<PerfThreadSlot> slots;
    std::unordered_map<int, bool> knownTids;
    bool wasPaused = false;

    auto openForTid = [&](int tid) -> bool {
        struct perf_event_attr attr{};
        attr.size = sizeof(attr);
        attr.type = PERF_TYPE_BREAKPOINT;
        attr.bp_type = toPerfBpType(sess->type);
        attr.bp_addr = sess->address;
        attr.bp_len = toPerfBpLen(sess->size, sess->type);
        attr.sample_period = 1;
        attr.wakeup_events = 1;
        attr.exclude_kernel = 1;
        attr.exclude_hv = 1;
        attr.disabled = sess->pausedFlag.load() ? 1 : 0;

        uint64_t regsMask = 0;
#if defined(__aarch64__)
        regsMask = (1ULL << 33) - 1ULL; // x0..x29, lr, sp, pc
#elif defined(__arm__)
        regsMask = (1ULL << 16) - 1ULL;
#elif defined(__x86_64__)
        regsMask = 0xFFFFFFULL;
#elif defined(__i386__)
        regsMask = 0xFFFFULL;
#endif

        // Progressive fallback for kernel sample capabilities
        const uint64_t configs[3] = {
            PERF_SAMPLE_IP | PERF_SAMPLE_TID | PERF_SAMPLE_ADDR | PERF_SAMPLE_REGS_USER |
                (sess->captureCallstack ? PERF_SAMPLE_CALLCHAIN : 0ULL),
            PERF_SAMPLE_IP | PERF_SAMPLE_TID | PERF_SAMPLE_ADDR | PERF_SAMPLE_REGS_USER,
            PERF_SAMPLE_IP | PERF_SAMPLE_TID | PERF_SAMPLE_ADDR
        };

        int fd = -1;
        uint64_t usedSampleType = 0;
        uint64_t usedRegsMask = 0;
        for (int attempt = 0; attempt < 3; ++attempt) {
            attr.sample_type = configs[attempt];
            attr.sample_regs_user = (configs[attempt] & PERF_SAMPLE_REGS_USER) ? regsMask : 0;
            fd = static_cast<int>(::syscall(__NR_perf_event_open, &attr, tid, -1, -1, PERF_FLAG_FD_CLOEXEC));
            if (fd >= 0) {
                usedSampleType = configs[attempt];
                usedRegsMask = attr.sample_regs_user;
                break;
            }
        }
        if (fd < 0) return false;

        void* base = ::mmap(nullptr, mmapSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (base == MAP_FAILED) {
            ::close(fd);
            return false;
        }
        ::ioctl(fd, PERF_EVENT_IOC_RESET, 0);
        if (!sess->pausedFlag.load()) {
            ::ioctl(fd, PERF_EVENT_IOC_ENABLE, 0);
        }
        slots.push_back({tid, fd, base, usedSampleType, usedRegsMask});
        return true;
    };

    auto scanAndAttachThreads = [&]() {
        auto tids = listProcessThreads(sess->pid);
        int attached = static_cast<int>(slots.size());
        for (int tid : tids) {
            if (knownTids.find(tid) == knownTids.end()) {
                knownTids[tid] = true;
                if (openForTid(tid)) {
                    attached++;
                }
            }
        }
        std::lock_guard<std::mutex> dlk(sess->dataMtx);
        sess->totalThreads = static_cast<int32_t>(tids.size());
        sess->attachedThreads = attached;
        sess->sampleTids.clear();
        for (size_t i = 0; i < std::min<size_t>(tids.size(), 4); ++i) {
            sess->sampleTids.push_back(tids[i]);
        }
        if (attached == 0 && !tids.empty()) {
            sess->lastError = "perf_event_open 附加失败 (errno=" + std::to_string(errno) + "，内核可能限制了 perf_event，请切换为 Ptrace 模式)";
        } else if (attached > 0) {
            sess->lastError.clear();
        }
    };

    scanAndAttachThreads();
    if (slots.empty()) {
        sess->runningFlag.store(false);
        return;
    }

    std::vector<uint8_t> recordBuf(4096);
    auto lastRescan = std::chrono::steady_clock::now();

    while (!sess->stopFlag.load()) {
        bool nowPaused = sess->pausedFlag.load();
        if (nowPaused != wasPaused) {
            wasPaused = nowPaused;
            for (auto& sl : slots) {
                ::ioctl(sl.fd, nowPaused ? PERF_EVENT_IOC_DISABLE : PERF_EVENT_IOC_ENABLE, 0);
            }
        }

        auto now = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::milliseconds>(now - lastRescan).count() >= 1000) {
            lastRescan = now;
            scanAndAttachThreads();
            maps = MapsParser::parseProcessMaps(sess->pid, 0, false);
        }

        std::vector<struct pollfd> pfds(slots.size());
        for (size_t i = 0; i < slots.size(); ++i) {
            pfds[i].fd = slots[i].fd;
            pfds[i].events = POLLIN;
            pfds[i].revents = 0;
        }

        int ready = ::poll(pfds.data(), static_cast<nfds_t>(pfds.size()), 40);
        if (ready <= 0) continue;

        for (size_t i = 0; i < slots.size(); ++i) {
            if (!(pfds[i].revents & POLLIN)) continue;
            auto& sl = slots[i];
            auto* meta = reinterpret_cast<struct perf_event_mmap_page*>(sl.mmapBase);
            uint8_t* ring = reinterpret_cast<uint8_t*>(sl.mmapBase) + pageSize;

            uint64_t head = __atomic_load_n(&meta->data_head, __ATOMIC_ACQUIRE);
            uint64_t tail = meta->data_tail;

            while (tail < head) {
                struct perf_event_header hdr{};
                for (size_t b = 0; b < sizeof(hdr); ++b) {
                    reinterpret_cast<uint8_t*>(&hdr)[b] = ring[(tail + b) % dataSize];
                }
                if (hdr.size < sizeof(hdr) || hdr.size > dataSize) {
                    tail = head;
                    break;
                }
                if (hdr.type == PERF_RECORD_SAMPLE && !nowPaused) {
                    if (recordBuf.size() < hdr.size) recordBuf.resize(hdr.size);
                    for (size_t b = 0; b < hdr.size; ++b) {
                        recordBuf[b] = ring[(tail + b) % dataSize];
                    }

                    size_t off = sizeof(hdr);
                    auto readU64 = [&](uint64_t& v) -> bool {
                        if (off + 8 > hdr.size) return false;
                        std::memcpy(&v, recordBuf.data() + off, 8);
                        off += 8;
                        return true;
                    };

                    uint64_t ip = 0;
                    int32_t sampleTid = sl.tid;
                    uint64_t sp = 0, lr = 0, fp = 0;
                    std::vector<RegEntry> gprs;
                    std::vector<FpRegEntry> fprs;
                    std::vector<CallFrameEntry> callstack;

                    if (sl.sampleType & PERF_SAMPLE_IP) {
                        readU64(ip);
                    }
                    if (sl.sampleType & PERF_SAMPLE_TID) {
                        if (off + 8 <= hdr.size) {
                            uint32_t p = 0, t = 0;
                            std::memcpy(&p, recordBuf.data() + off, 4);
                            std::memcpy(&t, recordBuf.data() + off + 4, 4);
                            if (t > 0) sampleTid = static_cast<int32_t>(t);
                            off += 8;
                        }
                    }
                    if (sl.sampleType & PERF_SAMPLE_ADDR) {
                        uint64_t dummyAddr = 0;
                        readU64(dummyAddr);
                    }
                    if (sl.sampleType & PERF_SAMPLE_CALLCHAIN) {
                        uint64_t nr = 0;
                        if (readU64(nr)) {
                            for (uint64_t c = 0; c < nr && c < 32; ++c) {
                                uint64_t frameAddr = 0;
                                if (!readU64(frameAddr)) break;
                                // Skip kernel context markers (PERF_CONTEXT_*)
                                if (frameAddr >= 0xFFFFFFFFFFFFF000ULL) continue;
                                CallFrameEntry f;
                                f.address = frameAddr;
                                resolveAddressModule(maps, frameAddr, f.moduleName, f.moduleOffset);
                                callstack.push_back(std::move(f));
                            }
                        }
                    }
                    if (sl.sampleType & PERF_SAMPLE_REGS_USER) {
                        uint64_t abi = 0;
                        if (readU64(abi) && abi != 0) {
                            std::vector<uint64_t> rawRegs;
                            for (int bit = 0; bit < 64; ++bit) {
                                if (sl.regsMask & (1ULL << bit)) {
                                    uint64_t rv = 0;
                                    if (!readU64(rv)) break;
                                    rawRegs.push_back(rv);
                                }
                            }
#if defined(__aarch64__)
                            for (size_t r = 0; r < rawRegs.size(); ++r) {
                                if (r <= 28) gprs.push_back({"x" + std::to_string(r), rawRegs[r]});
                                else if (r == 29) { fp = rawRegs[r]; gprs.push_back({"fp", fp}); }
                                else if (r == 30) { lr = rawRegs[r]; gprs.push_back({"lr", lr}); }
                                else if (r == 31) { sp = rawRegs[r]; gprs.push_back({"sp", sp}); }
                                else if (r == 32) { if (ip == 0) ip = rawRegs[r]; gprs.push_back({"pc", rawRegs[r]}); }
                            }
#elif defined(__arm__)
                            for (size_t r = 0; r < rawRegs.size() && r < 16; ++r) {
                                if (r == 11) fp = rawRegs[r];
                                if (r == 13) sp = rawRegs[r];
                                if (r == 14) lr = rawRegs[r];
                                if (r == 15 && ip == 0) ip = rawRegs[r];
                                gprs.push_back({"r" + std::to_string(r), rawRegs[r]});
                            }
#else
                            for (size_t r = 0; r < rawRegs.size(); ++r) {
                                gprs.push_back({"reg" + std::to_string(r), rawRegs[r]});
                            }
#endif
                        }
                    }

                    if (sess->captureCallstack && callstack.empty()) {
                        callstack = unwindFpChain(io, maps, ip, lr, fp);
                    }

                    sess->recordHit(
                        ip,
                        sampleTid,
                        static_cast<int32_t>(sess->type),
                        maps,
                        io,
                        sp,
                        lr,
                        std::move(gprs),
                        std::move(fprs),
                        std::move(callstack)
                    );
                }
                tail += hdr.size;
            }
            __atomic_store_n(&meta->data_tail, tail, __ATOMIC_RELEASE);
        }
    }

    for (auto& sl : slots) {
        if (sl.fd >= 0) {
            ::ioctl(sl.fd, PERF_EVENT_IOC_DISABLE, 0);
            if (sl.mmapBase) ::munmap(sl.mmapBase, mmapSize);
            ::close(sl.fd);
        }
    }
    sess->runningFlag.store(false);
}

// ============================================================================
// Mode 2: Linux ptrace Hardware Breakpoint / Watchpoint Worker
// (Inspired by YuPtrace.lua, upgraded to full multi-thread R/W/RW/X + GPR/FPR + unwind)
// ============================================================================

void BreakpointManager::ptraceWorkerLoop(std::shared_ptr<Session> sess) {
    ProcIO io;
    io.attach(sess->pid);
    auto maps = MapsParser::parseProcessMaps(sess->pid, 0, false);

    std::vector<int> attachedTids;
    std::unordered_map<int, bool> steppingTid;
    bool wasPaused = false;

    auto installHw = [&](int tid, bool enable) -> bool {
#if defined(__aarch64__)
        return installArm64HwBp(tid, sess->address, sess->type, sess->size, enable, 0);
#elif defined(__arm__)
        return installArm32HwBp(tid, sess->address, sess->type, sess->size, enable);
#elif defined(__x86_64__) || defined(__i386__)
        return installX86HwBp(tid, sess->address, sess->type, sess->size, enable);
#else
        (void)tid; (void)enable;
        return false;
#endif
    };

    auto attachThreads = [&]() {
        auto tids = listProcessThreads(sess->pid);
        for (int tid : tids) {
            if (std::find(attachedTids.begin(), attachedTids.end(), tid) != attachedTids.end()) {
                continue;
            }
            if (tid == ::getpid() || tid == ::gettid()) continue;
            if (::ptrace(PTRACE_ATTACH, tid, nullptr, nullptr) == 0) {
                int status = 0;
                if (::waitpid(tid, &status, __WALL) == tid) {
                    if (!sess->pausedFlag.load()) {
                        installHw(tid, true);
                    }
                    ::ptrace(PTRACE_CONT, tid, nullptr, nullptr);
                    attachedTids.push_back(tid);
                } else {
                    ::ptrace(PTRACE_DETACH, tid, nullptr, nullptr);
                }
            }
        }
        std::lock_guard<std::mutex> dlk(sess->dataMtx);
        sess->totalThreads = static_cast<int32_t>(tids.size());
        sess->attachedThreads = static_cast<int32_t>(attachedTids.size());
        sess->sampleTids.clear();
        for (size_t i = 0; i < std::min<size_t>(tids.size(), 4); ++i) {
            sess->sampleTids.push_back(tids[i]);
        }
        if (attachedTids.empty()) {
            sess->lastError = "ptrace 附加失败 (errno=" + std::to_string(errno) + "，目标进程可能已被其他调试器占用或无同 UID/Root 权限)";
        } else {
            sess->lastError.clear();
        }
    };

    attachThreads();
    if (attachedTids.empty()) {
        sess->runningFlag.store(false);
        return;
    }

    auto lastMapsRefresh = std::chrono::steady_clock::now();

    while (!sess->stopFlag.load() && !attachedTids.empty()) {
        bool nowPaused = sess->pausedFlag.load();
        if (nowPaused != wasPaused) {
            wasPaused = nowPaused;
            for (int tid : attachedTids) {
                ::kill(tid, SIGSTOP);
                int st = 0;
                if (::waitpid(tid, &st, __WALL) == tid) {
                    installHw(tid, !nowPaused);
                    ::ptrace(PTRACE_CONT, tid, nullptr, nullptr);
                }
            }
        }

        auto now = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::milliseconds>(now - lastMapsRefresh).count() >= 1500) {
            lastMapsRefresh = now;
            maps = MapsParser::parseProcessMaps(sess->pid, 0, false);
        }

        int status = 0;
        pid_t tid = ::waitpid(-1, &status, __WALL | WNOHANG);
        if (tid <= 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(4));
            continue;
        }

        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            attachedTids.erase(std::remove(attachedTids.begin(), attachedTids.end(), tid), attachedTids.end());
            steppingTid.erase(tid);
            continue;
        }

        if (!WIFSTOPPED(status)) {
            continue;
        }

        int sig = WSTOPSIG(status);
        if (sig == SIGTRAP) {
            if (steppingTid[tid]) {
                // Finished single-step past trapped instruction: re-arm watchpoint and resume
                steppingTid[tid] = false;
                if (!nowPaused) {
                    installHw(tid, true);
                }
                ::ptrace(PTRACE_CONT, tid, nullptr, nullptr);
            } else {
                // Hardware breakpoint / watchpoint hit!
                uint64_t pc = 0, sp = 0, lr = 0, fp = 0;
                std::vector<RegEntry> gprs;
                std::vector<FpRegEntry> fprs;
                bool okRegs = false;
#if defined(__aarch64__)
                okRegs = captureArm64Regs(tid, pc, sp, lr, fp, gprs, fprs);
#elif defined(__arm__)
                okRegs = captureArm32Regs(tid, pc, sp, lr, fp, gprs, fprs);
#elif defined(__x86_64__) || defined(__i386__)
                okRegs = captureX86Regs(tid, pc, sp, lr, fp, gprs, fprs);
#endif
                if (okRegs && !nowPaused) {
                    std::vector<CallFrameEntry> callstack;
                    if (sess->captureCallstack) {
                        callstack = unwindFpChain(io, maps, pc, lr, fp);
                    }
                    sess->recordHit(
                        pc,
                        static_cast<int32_t>(tid),
                        static_cast<int32_t>(sess->type),
                        maps,
                        io,
                        sp,
                        lr,
                        std::move(gprs),
                        std::move(fprs),
                        std::move(callstack)
                    );
                }
                // Temporarily disarm, single-step past the faulting instruction, then re-arm
                installHw(tid, false);
#if defined(__aarch64__) || defined(__x86_64__) || defined(__i386__)
                if (::ptrace(PTRACE_SINGLESTEP, tid, nullptr, nullptr) == 0) {
                    steppingTid[tid] = true;
                } else {
                    if (!nowPaused) installHw(tid, true);
                    ::ptrace(PTRACE_CONT, tid, nullptr, nullptr);
                }
#else
                // ARM32 lacks universal PTRACE_SINGLESTEP; continue briefly and re-arm on next cycle
                ::ptrace(PTRACE_CONT, tid, nullptr, nullptr);
#endif
            }
        } else if (sig == SIGSTOP) {
            ::ptrace(PTRACE_CONT, tid, nullptr, nullptr);
        } else {
            // Forward non-debugger signals transparently so the target process behaves normally
            ::ptrace(PTRACE_CONT, tid, nullptr, reinterpret_cast<void*>(static_cast<uintptr_t>(sig)));
        }
    }

    // Cleanly disarm & detach all threads
    for (int tid : attachedTids) {
        ::kill(tid, SIGSTOP);
        int st = 0;
        ::waitpid(tid, &st, __WALL);
        installHw(tid, false);
        ::ptrace(PTRACE_DETACH, tid, nullptr, nullptr);
    }
    sess->runningFlag.store(false);
}

} // namespace mem_engine
