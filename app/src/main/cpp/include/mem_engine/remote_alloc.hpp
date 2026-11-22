#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace mem_engine {

struct RemoteAllocResult {
    bool ok = false;
    uint64_t address = 0;
    size_t size = 0;
    int actualProt = 0;
    bool writeVerified = false;
    std::string error;
};

/**
 * Allocates an anonymous memory page inside the target process address space
 * via POSIX ptrace syscall invocation (SYS_mmap / SYS_mmap2), then verifies
 * actual VMA permissions in /proc/<pid>/maps and cross-process writeability.
 */
RemoteAllocResult allocateTargetProcessPage(
    int targetPid,
    uint64_t hintAddr,
    size_t size,
    int prot
);

} // namespace mem_engine
