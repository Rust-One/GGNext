#include "mem_engine/remote_alloc.hpp"
#include "mem_engine/maps_parser.hpp"
#include "mem_engine/proc_io.hpp"

#include <cerrno>
#include <csignal>
#include <cstring>
#include <algorithm>
#include <elf.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

namespace mem_engine {

namespace {

std::string protToRwx(int prot) {
    std::string s;
    s.push_back((prot & PROT_READ)  ? 'r' : '-');
    s.push_back((prot & PROT_WRITE) ? 'w' : '-');
    s.push_back((prot & PROT_EXEC)  ? 'x' : '-');
    return s;
}

bool findSyscallGadgetInTarget(ProcIO& io, int pid, uint64_t& outPc) {
    auto regions = MapsParser::parseProcessMaps(pid, 0, false);

    // Prefer executable mappings in libc.so or [vdso], then any r-x region
    std::vector<VmaRegion> execRegions;
    for (const auto& r : regions) {
        if (r.executable && r.readable && r.length >= 4096) {
            execRegions.push_back(r);
        }
    }
    std::stable_sort(execRegions.begin(), execRegions.end(), [](const VmaRegion& a, const VmaRegion& b) {
        auto score = [](const std::string& p) -> int {
            if (p.find("libc.so") != std::string::npos) return 0;
            if (p == "[vdso]") return 1;
            if (p.find("/system/") != std::string::npos || p.find("/apex/") != std::string::npos) return 2;
            return 3;
        };
        return score(a.pathname) < score(b.pathname);
    });

    std::vector<uint8_t> buf(64 * 1024);
    for (const auto& r : execRegions) {
        size_t maxScan = std::min<size_t>(r.length, 512 * 1024);
        size_t scanned = 0;
        while (scanned < maxScan) {
            size_t chunk = std::min(buf.size(), maxScan - scanned);
            size_t got = 0;
            if (io.readMemory(r.startAddress + scanned, buf.data(), chunk, &got) != EngineErrc::OK || got < 4) {
                break;
            }
#if defined(__aarch64__)
            // ARM64: svc #0 -> 01 00 00 D4 (little-endian 0xD4000001)
            for (size_t off = 0; off + 4 <= got; off += 4) {
                uint32_t insn = 0;
                std::memcpy(&insn, buf.data() + off, 4);
                if (insn == 0xD4000001u) {
                    outPc = static_cast<uint64_t>(r.startAddress + scanned + off);
                    return true;
                }
            }
#elif defined(__arm__)
            // ARM32: svc #0 -> 00 00 00 EF (0xEF000000)
            for (size_t off = 0; off + 4 <= got; off += 4) {
                uint32_t insn = 0;
                std::memcpy(&insn, buf.data() + off, 4);
                if (insn == 0xEF000000u) {
                    outPc = static_cast<uint64_t>(r.startAddress + scanned + off);
                    return true;
                }
            }
#elif defined(__x86_64__)
            // x86_64: syscall -> 0F 05
            for (size_t off = 0; off + 2 <= got; ++off) {
                if (buf[off] == 0x0Fu && buf[off + 1] == 0x05u) {
                    outPc = static_cast<uint64_t>(r.startAddress + scanned + off);
                    return true;
                }
            }
#elif defined(__i386__)
            // x86: int 0x80 -> CD 80
            for (size_t off = 0; off + 2 <= got; ++off) {
                if (buf[off] == 0xCDu && buf[off + 1] == 0x80u) {
                    outPc = static_cast<uint64_t>(r.startAddress + scanned + off);
                    return true;
                }
            }
#endif
            scanned += got;
        }
    }
    return false;
}

bool waitForStop(int pid, int& outStatus) {
    while (true) {
        pid_t r = ::waitpid(pid, &outStatus, __WALL);
        if (r < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        return r == pid && WIFSTOPPED(outStatus);
    }
}

bool verifyAllocatedVma(
    int pid,
    uint64_t addr,
    size_t expectedSize,
    int requestedProt,
    ProcIO& io,
    RemoteAllocResult& res
) {
    auto regions = MapsParser::parseProcessMaps(pid, 0, false);
    const VmaRegion* found = nullptr;
    for (const auto& r : regions) {
        if (addr >= r.startAddress && addr < r.endAddress) {
            found = &r;
            break;
        }
    }
    if (!found) {
        res.ok = false;
        res.error = "已调用 mmap 但目标进程 /proc/" + std::to_string(pid) + "/maps 中未找到该内存页";
        return false;
    }

    int actualProt = 0;
    if (found->readable)   actualProt |= PROT_READ;
    if (found->writable)   actualProt |= PROT_WRITE;
    if (found->executable) actualProt |= PROT_EXEC;
    res.actualProt = actualProt;
    res.size = std::max<size_t>(expectedSize, found->length > 0 ? expectedSize : 4096);

    if ((requestedProt & PROT_WRITE) && !(actualProt & PROT_WRITE)) {
        res.ok = false;
        res.error = "内核返回的内存页缺少可写权限 (请求 " + protToRwx(requestedProt) + "，实际 " + protToRwx(actualProt) + ")";
        return false;
    }
    if ((requestedProt & PROT_EXEC) && !(actualProt & PROT_EXEC)) {
        res.ok = false;
        res.error = "内核/SELinux 拒绝了可执行权限 (请求 " + protToRwx(requestedProt) + "，实际 " + protToRwx(actualProt) + ")";
        return false;
    }

    // Verify actual cross-process write & readback when PROT_WRITE is requested
    if (requestedProt & PROT_WRITE) {
        uint8_t orig[8] = {0};
        size_t got = 0;
        io.readMemory(static_cast<uintptr_t>(addr), orig, sizeof(orig), &got);

        const uint8_t probe[4] = {0x5A, 0xA5, 0x3C, 0xC3};
        auto wrc = io.writeMemory(static_cast<uintptr_t>(addr), probe, sizeof(probe));
        if (wrc != EngineErrc::OK) {
            res.ok = false;
            res.error = "内存页已在目标进程分配，但跨进程写入通道校验失败 (rc=" + std::to_string(static_cast<int>(wrc)) + ")";
            return false;
        }
        uint8_t check[4] = {0};
        size_t checkGot = 0;
        auto rrc = io.readMemory(static_cast<uintptr_t>(addr), check, sizeof(check), &checkGot);
        // Restore zeroed/original bytes cleanly
        io.writeMemory(static_cast<uintptr_t>(addr), orig, sizeof(probe));

        if (rrc != EngineErrc::OK || checkGot != sizeof(probe) || std::memcmp(probe, check, sizeof(probe)) != 0) {
            res.ok = false;
            res.error = "内存页写入回读校验不一致，目标页可能受写保护";
            return false;
        }
        res.writeVerified = true;
    }

    res.ok = true;
    res.address = addr;
    return true;
}

} // namespace

RemoteAllocResult allocateTargetProcessPage(
    int targetPid,
    uint64_t hintAddr,
    size_t size,
    int prot
) {
    RemoteAllocResult res;
    if (targetPid <= 0) {
        res.error = "未附加有效的目标进程";
        return res;
    }

    long pageSize = ::sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) pageSize = 4096;
    size_t alignedSize = size == 0
        ? static_cast<size_t>(pageSize)
        : ((size + static_cast<size_t>(pageSize) - 1) / static_cast<size_t>(pageSize)) * static_cast<size_t>(pageSize);

    // Self-process fast path
    if (targetPid == ::getpid()) {
        void* p = ::mmap(
            reinterpret_cast<void*>(static_cast<uintptr_t>(hintAddr)),
            alignedSize,
            prot,
            MAP_PRIVATE | MAP_ANONYMOUS,
            -1,
            0
        );
        if (p == MAP_FAILED) {
            res.error = "mmap 分配失败 (errno=" + std::to_string(errno) + ": " + std::strerror(errno) + ")";
            return res;
        }
        ProcIO selfIo;
        selfIo.attach(targetPid);
        verifyAllocatedVma(targetPid, static_cast<uint64_t>(reinterpret_cast<uintptr_t>(p)), alignedSize, prot, selfIo, res);
        return res;
    }

    ProcIO io;
    if (io.attach(targetPid) != EngineErrc::OK) {
        res.error = "无法附加目标进程内存通道 (pid=" + std::to_string(targetPid) + ")";
        return res;
    }

    uint64_t syscallPc = 0;
    if (!findSyscallGadgetInTarget(io, targetPid, syscallPc)) {
        res.error = "未在目标进程可执行段中找到系统调用指令 (libc.so / [vdso])";
        return res;
    }

    if (::ptrace(PTRACE_ATTACH, targetPid, nullptr, nullptr) != 0) {
        int e = errno;
        res.error = "ptrace 附加目标进程失败 (errno=" + std::to_string(e) + ": " + std::strerror(e) + "，请确认已获 Root 或同 UID 权限)";
        return res;
    }

    int status = 0;
    if (!waitForStop(targetPid, status)) {
        ::ptrace(PTRACE_DETACH, targetPid, nullptr, nullptr);
        res.error = "等待目标进程暂停失败";
        return res;
    }

    int64_t mmapRet = -EINVAL;
    bool execOk = false;

#if defined(__aarch64__)
    struct user_pt_regs savedRegs{};
    struct iovec iov{&savedRegs, sizeof(savedRegs)};
    if (::ptrace(PTRACE_GETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) == 0) {
        struct user_pt_regs callRegs = savedRegs;
        callRegs.regs[8] = __NR_mmap;
        callRegs.regs[0] = hintAddr;
        callRegs.regs[1] = static_cast<uint64_t>(alignedSize);
        callRegs.regs[2] = static_cast<uint64_t>(prot);
        callRegs.regs[3] = static_cast<uint64_t>(MAP_PRIVATE | MAP_ANONYMOUS);
        callRegs.regs[4] = static_cast<uint64_t>(-1LL);
        callRegs.regs[5] = 0;
        callRegs.pc = syscallPc;

        struct iovec setIov{&callRegs, sizeof(callRegs)};
        if (::ptrace(PTRACE_SETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &setIov) == 0) {
            if (::ptrace(PTRACE_SINGLESTEP, targetPid, nullptr, nullptr) == 0 && waitForStop(targetPid, status)) {
                struct user_pt_regs afterRegs{};
                struct iovec afterIov{&afterRegs, sizeof(afterRegs)};
                if (::ptrace(PTRACE_GETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &afterIov) == 0) {
                    mmapRet = static_cast<int64_t>(afterRegs.regs[0]);
                    execOk = true;
                }
            }
            struct iovec restoreIov{&savedRegs, sizeof(savedRegs)};
            ::ptrace(PTRACE_SETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &restoreIov);
        }
    }
#elif defined(__arm__)
    struct pt_regs savedRegs{};
    struct iovec iov{&savedRegs, sizeof(savedRegs)};
    if (::ptrace(PTRACE_GETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) == 0) {
        struct pt_regs callRegs = savedRegs;
        callRegs.uregs[7] = __NR_mmap2;
        callRegs.uregs[0] = static_cast<unsigned long>(hintAddr);
        callRegs.uregs[1] = static_cast<unsigned long>(alignedSize);
        callRegs.uregs[2] = static_cast<unsigned long>(prot);
        callRegs.uregs[3] = static_cast<unsigned long>(MAP_PRIVATE | MAP_ANONYMOUS);
        callRegs.uregs[4] = static_cast<unsigned long>(-1);
        callRegs.uregs[5] = 0;
        callRegs.uregs[15] = static_cast<unsigned long>(syscallPc);
        // Clear Thumb bit (T=0) because svc #0 gadget is ARM-mode 0xEF000000
        callRegs.uregs[16] &= ~(1UL << 5);

        struct iovec setIov{&callRegs, sizeof(callRegs)};
        if (::ptrace(PTRACE_SETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &setIov) == 0) {
            // Stop at syscall-enter then syscall-exit
            if (::ptrace(PTRACE_SYSCALL, targetPid, nullptr, nullptr) == 0 && waitForStop(targetPid, status) &&
                ::ptrace(PTRACE_SYSCALL, targetPid, nullptr, nullptr) == 0 && waitForStop(targetPid, status)) {
                struct pt_regs afterRegs{};
                struct iovec afterIov{&afterRegs, sizeof(afterRegs)};
                if (::ptrace(PTRACE_GETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &afterIov) == 0) {
                    mmapRet = static_cast<int64_t>(static_cast<int32_t>(afterRegs.uregs[0]));
                    execOk = true;
                }
            }
            struct iovec restoreIov{&savedRegs, sizeof(savedRegs)};
            ::ptrace(PTRACE_SETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &restoreIov);
        }
    }
#elif defined(__x86_64__)
    struct user_regs_struct savedRegs{};
    struct iovec iov{&savedRegs, sizeof(savedRegs)};
    if (::ptrace(PTRACE_GETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) == 0) {
        struct user_regs_struct callRegs = savedRegs;
        callRegs.rax = __NR_mmap;
        callRegs.rdi = hintAddr;
        callRegs.rsi = static_cast<unsigned long long>(alignedSize);
        callRegs.rdx = static_cast<unsigned long long>(prot);
        callRegs.r10 = static_cast<unsigned long long>(MAP_PRIVATE | MAP_ANONYMOUS);
        callRegs.r8  = static_cast<unsigned long long>(-1LL);
        callRegs.r9  = 0;
        callRegs.rip = syscallPc;

        struct iovec setIov{&callRegs, sizeof(callRegs)};
        if (::ptrace(PTRACE_SETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &setIov) == 0) {
            if (::ptrace(PTRACE_SINGLESTEP, targetPid, nullptr, nullptr) == 0 && waitForStop(targetPid, status)) {
                struct user_regs_struct afterRegs{};
                struct iovec afterIov{&afterRegs, sizeof(afterRegs)};
                if (::ptrace(PTRACE_GETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &afterIov) == 0) {
                    mmapRet = static_cast<int64_t>(afterRegs.rax);
                    execOk = true;
                }
            }
            struct iovec restoreIov{&savedRegs, sizeof(savedRegs)};
            ::ptrace(PTRACE_SETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &restoreIov);
        }
    }
#elif defined(__i386__)
    struct user_regs_struct savedRegs{};
    struct iovec iov{&savedRegs, sizeof(savedRegs)};
    if (::ptrace(PTRACE_GETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &iov) == 0) {
        struct user_regs_struct callRegs = savedRegs;
        callRegs.eax = __NR_mmap2;
        callRegs.ebx = static_cast<long>(hintAddr);
        callRegs.ecx = static_cast<long>(alignedSize);
        callRegs.edx = static_cast<long>(prot);
        callRegs.esi = static_cast<long>(MAP_PRIVATE | MAP_ANONYMOUS);
        callRegs.edi = -1;
        callRegs.ebp = 0;
        callRegs.eip = static_cast<long>(syscallPc);

        struct iovec setIov{&callRegs, sizeof(callRegs)};
        if (::ptrace(PTRACE_SETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &setIov) == 0) {
            if (::ptrace(PTRACE_SINGLESTEP, targetPid, nullptr, nullptr) == 0 && waitForStop(targetPid, status)) {
                struct user_regs_struct afterRegs{};
                struct iovec afterIov{&afterRegs, sizeof(afterRegs)};
                if (::ptrace(PTRACE_GETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &afterIov) == 0) {
                    mmapRet = static_cast<int64_t>(static_cast<int32_t>(afterRegs.eax));
                    execOk = true;
                }
            }
            struct iovec restoreIov{&savedRegs, sizeof(savedRegs)};
            ::ptrace(PTRACE_SETREGSET, targetPid, reinterpret_cast<void*>(static_cast<uintptr_t>(NT_PRSTATUS)), &restoreIov);
        }
    }
#else
    res.error = "当前架构不支持跨进程 mmap 分配";
#endif

    ::ptrace(PTRACE_DETACH, targetPid, nullptr, nullptr);

    if (!execOk) {
        if (res.error.empty()) {
            res.error = "目标进程内执行 mmap 系统调用失败 (可能是架构位宽不匹配或 ptrace 寄存器读写受限)";
        }
        return res;
    }

    // Linux syscalls return -errno in [-4095, -1] on error
    if (mmapRet < 0 && mmapRet >= -4095) {
        int errCode = static_cast<int>(-mmapRet);
        std::string hint;
        if (errCode == EACCES && (prot & PROT_EXEC)) {
            hint = " (SELinux 禁止 execmem，可尝试去掉“执行”权限仅申请读写页)";
        }
        res.error = "目标进程 mmap 返回错误 errno=" + std::to_string(errCode) +
                    " (" + std::strerror(errCode) + ")" + hint;
        return res;
    }

    uint64_t mappedAddr = static_cast<uint64_t>(mmapRet);
    if (mappedAddr == 0 || mappedAddr == static_cast<uint64_t>(-1LL)) {
        res.error = "目标进程 mmap 返回无效地址 0x0";
        return res;
    }

    verifyAllocatedVma(targetPid, mappedAddr, alignedSize, prot, io, res);
    return res;
}

} // namespace mem_engine
