#pragma once
#ifndef MEM_ENGINE_PROC_IO_HPP
#define MEM_ENGINE_PROC_IO_HPP

#include "types.hpp"
#include <sys/uio.h>
#include <vector>
#include <mutex>
#include <thread>
#include <condition_variable>
#include <unordered_map>
#include <atomic>

namespace mem_engine {

struct BatchReadRange {
    uintptr_t remoteAddr = 0;
    size_t    length     = 0;
    uint8_t*  localBuf   = nullptr;
    bool      success    = false;
};

class ProcIO {
public:
    // Adaptive transfer promotion threshold (inspired by KittyMemoryEx KT_RETRY_NORMAL_AFTER)
    static constexpr size_t RETRY_NORMAL_TRANSFER_BYTES = 64 * 1024;

    ProcIO();
    explicit ProcIO(int pid);
    ~ProcIO();

    ProcIO(const ProcIO&) = delete;
    ProcIO& operator=(const ProcIO&) = delete;
    ProcIO(ProcIO&& other) noexcept;
    ProcIO& operator=(ProcIO&& other) noexcept;

    // Attach to target process (compliant with standard Linux/Android DAC & ptrace_may_access)
    EngineErrc attach(int pid);
    void detach() noexcept;

    int pid() const noexcept { return m_pid; }
    size_t pageSize() const noexcept { return m_pageSize; }
    size_t iovMax() const noexcept { return m_iovMax; }
    bool targetIs64Bit() const noexcept { return m_targetIs64Bit; }
    bool hasPagemapResidency() const noexcept { return m_pagemapUsable.load(std::memory_order_relaxed); }
    uint64_t zeroPagePfn() const noexcept { return m_zeroPagePfn; }

    // ------------------------------------------------------------------
    // 用户态隐蔽读取增强 (Stealth Read)
    // ------------------------------------------------------------------
    // 通道轮换：在 process_vm_readv 与 /proc/pid/mem 之间轮换起始通道，
    // 避免固定单一 syscall 被 hook / seccomp 监控时完全暴露。默认开启。
    void setStealthRotation(bool enabled) noexcept;
    bool stealthRotation() const noexcept;

    // 即开即关 fd：读取时不复用长期打开的 /proc/pid/mem fd，读后立即关闭。
    // 用于对抗"扫描本进程 fd 列表发现 /proc/<目标>/mem"的检测。
    // 注意：每次 open/close 有开销，搜索大范围时保持关闭以获得吞吐。
    void setEphemeralFd(bool enabled) noexcept;
    bool ephemeralFd() const noexcept;

    // 通道使用统计（供日志 / UI 展示）。
    uint64_t readCountPvm() const noexcept;
    uint64_t readCountMemfd() const noexcept;
    uint64_t readCountFallback() const noexcept;

    // Standard single-range read/write:
    // - Read: process_vm_readv -> /proc/[pid]/mem pread64 fallback with EINTR retry
    // - Write: process_vm_writev -> /proc/[pid]/mem pwrite64 fallback (FOLL_FORCE for read-only/code pages)
    EngineErrc readMemory(uintptr_t addr, void* buf, size_t size, size_t* outBytesRead = nullptr) const noexcept;
    EngineErrc writeMemory(uintptr_t addr, const void* buf, size_t size) const noexcept;

    // Fault-tolerant chunk read:
    // 1. Pre-filters unallocated / shared-zero pages via /proc/[pid]/pagemap when available.
    // 2. Uses adaptive Normal <-> Page transfer (re-promoting to bulk read after 64KB of contiguous valid pages).
    // 3. Fills outValidPageMask (1 byte per page: 1 = populated & readable, 0 = unallocated/guard/EFAULT).
    EngineErrc readChunkFaultTolerant(
        uintptr_t startAddr,
        uint8_t* buf,
        size_t size,
        std::vector<uint8_t>& outValidPageMask,
        size_t* outReadableBytes = nullptr,
        bool usePagemap = true
    ) const;

    // Page-batched iovec read for iterative refinement across scattered candidate addresses
    EngineErrc readBatchScattered(std::vector<BatchReadRange>& ranges) const noexcept;

    // Typed helpers
    template <typename T>
    EngineErrc readValue(uintptr_t addr, T& outVal) const noexcept {
        return readMemory(addr, &outVal, sizeof(T));
    }

    template <typename T>
    EngineErrc writeValue(uintptr_t addr, const T& val) const noexcept {
        return writeMemory(addr, &val, sizeof(T));
    }

    static size_t querySystemPageSize() noexcept;
    static size_t querySystemIovMax() noexcept;
    static uint64_t detectKernelZeroPagePfn(size_t pageSize) noexcept;
    static bool detectProcessIs64Bit(int pid) noexcept;

private:
    int      m_pid           = -1;
    int      m_memFd         = -1;
    int      m_pagemapFd     = -1;
    size_t   m_pageSize      = 4096;
    size_t   m_iovMax        = 1024;
    uint64_t m_zeroPagePfn   = 0;
    bool     m_targetIs64Bit = true;
    mutable std::atomic<bool> m_pvmSupported{true};
    mutable std::atomic<bool> m_pagemapUsable{false};

    // Stealth read state
    mutable std::atomic<bool>   m_stealthRotation{true};
    mutable std::atomic<bool>   m_ephemeralFd{false};
    mutable std::atomic<uint64_t> m_readCountPvm{0};
    mutable std::atomic<uint64_t> m_readCountMemfd{0};
    mutable std::atomic<uint64_t> m_readCountFallback{0};
    mutable std::atomic<uint32_t> m_readSequence{0};

    static EngineErrc mapErrnoToErrc(int err) noexcept;
    // Ephemeral-fd read: opens/closes /proc/[pid]/mem around a single pread.
    EngineErrc readViaEphemeralFd(uintptr_t addr, void* buf, size_t size, size_t* outBytesRead) const noexcept;
};

// ============================================================================
// High-Precision Multi-Mode Value Locker (Single Authoritative Freeze Worker)
// Supports EXACT, INCREMENT, DECREMENT, and RANGE_CLAMP natively in C++
// ============================================================================
class ValueLocker {
public:
    explicit ValueLocker(ProcIO& io, uint32_t intervalMs = 5);
    ~ValueLocker();

    ValueLocker(const ValueLocker&) = delete;
    ValueLocker& operator=(const ValueLocker&) = delete;

    void lockAddress(uintptr_t addr, const void* data, size_t size);
    void lockAddressConfigured(
        uintptr_t addr,
        const void* data,
        size_t size,
        DataType type,
        FreezeMode mode,
        int64_t minVal = 0,
        int64_t maxVal = 0
    );

    void unlockAddress(uintptr_t addr);
    void unlockAll();
    void setIntervalMs(uint32_t intervalMs);
    uint32_t intervalMs() const;
    void setPaused(bool paused);

private:
    struct LockedItem {
        uintptr_t            addr   = 0;
        std::vector<uint8_t> bytes;
        DataType             type   = DataType::INT32;
        FreezeMode           mode   = FreezeMode::EXACT;
        int64_t              minVal = 0;
        int64_t              maxVal = 0;
    };

    void workerLoop();
    void applyFreezeStep(LockedItem& item);

    ProcIO& m_io;
    mutable std::mutex m_mutex;
    std::condition_variable m_cv;
    std::unordered_map<uintptr_t, LockedItem> m_items;
    uint32_t m_intervalMs = 5;
    bool m_paused  = false;
    bool m_running = false;
    bool m_started = false;
    std::thread m_worker;
};

} // namespace mem_engine

#endif // MEM_ENGINE_PROC_IO_HPP
