#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <mutex>
#include <sys/mman.h>
#include <sys/time.h>
#include <unistd.h>

#if defined(__ANDROID__)
#include <android/log.h>
#define AGENT_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GGNextAgent", __VA_ARGS__)
#define AGENT_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "GGNextAgent", __VA_ARGS__)
#else
#define AGENT_LOGI(...) ((void)0)
#define AGENT_LOGW(...) ((void)0)
#endif

#include "xdl.h"

#if defined(__arm__) || defined(__aarch64__)
#include "shadowhook.h"
#define GGNEXT_HAS_SHADOWHOOK 1
#else
#define GGNEXT_HAS_SHADOWHOOK 0
#endif

namespace {

std::mutex g_clockMtx;
std::atomic<double> g_speedRate{1.0};
std::atomic<bool> g_hooksInstalled{false};

#if GGNEXT_HAS_SHADOWHOOK
constexpr int kMaxClocks = 16;

struct ClockAnchor {
    bool initialized = false;
    int64_t realBaseNs = 0;
    int64_t virtBaseNs = 0;
    double lastRate = 1.0;
};

ClockAnchor g_anchors[kMaxClocks];
ClockAnchor g_gtodAnchor;
ClockAnchor g_timeAnchor;
std::atomic<int64_t> g_lastConfigCheckNs{0};

using FnClockGettime = int (*)(clockid_t, struct timespec*);
using FnGettimeofday = int (*)(struct timeval*, struct timezone*);
using FnTime         = time_t (*)(time_t*);
using FnUsleep       = int (*)(useconds_t);
using FnNanosleep    = int (*)(const struct timespec*, struct timespec*);

FnClockGettime g_origClockGettime = nullptr;
FnGettimeofday g_origGettimeofday = nullptr;
FnTime         g_origTime         = nullptr;
FnUsleep       g_origUsleep       = nullptr;
FnNanosleep    g_origNanosleep    = nullptr;

void* g_stubClockGettime = nullptr;
void* g_stubGettimeofday = nullptr;
void* g_stubTime         = nullptr;
void* g_stubUsleep       = nullptr;
void* g_stubNanosleep    = nullptr;

inline int64_t tsToNs(const struct timespec& ts) {
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + static_cast<int64_t>(ts.tv_nsec);
}

inline void nsToTs(int64_t ns, struct timespec& ts) {
    if (ns < 0) ns = 0;
    ts.tv_sec = static_cast<time_t>(ns / 1000000000LL);
    ts.tv_nsec = static_cast<long>(ns % 1000000000LL);
}

inline int64_t tvToUs(const struct timeval& tv) {
    return static_cast<int64_t>(tv.tv_sec) * 1000000LL + static_cast<int64_t>(tv.tv_usec);
}

inline void usToTv(int64_t us, struct timeval& tv) {
    if (us < 0) us = 0;
    tv.tv_sec = static_cast<time_t>(us / 1000000LL);
    tv.tv_usec = static_cast<suseconds_t>(us % 1000000LL);
}

void pollExternalSpeedConfig(int64_t nowRealNs) {
    int64_t prev = g_lastConfigCheckNs.load(std::memory_order_relaxed);
    if (nowRealNs - prev < 50000000LL) return;
    if (!g_lastConfigCheckNs.compare_exchange_strong(prev, nowRealNs, std::memory_order_relaxed)) {
        return;
    }

    char path[96];
    std::snprintf(path, sizeof(path), "/data/local/tmp/.ggnext_spd_%d", static_cast<int>(::getpid()));
    int fd = ::open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return;

    char buf[64] = {0};
    ssize_t n = ::read(fd, buf, sizeof(buf) - 1);
    ::close(fd);
    if (n > 0) {
        double parsed = std::strtod(buf, nullptr);
        if (std::isfinite(parsed) && parsed >= 0.01 && parsed <= 100.0) {
            g_speedRate.store(parsed, std::memory_order_release);
        }
    }
}

int64_t scaleMonotonic(ClockAnchor& anchor, int64_t realVal, double rate) {
    if (!anchor.initialized) {
        anchor.initialized = true;
        anchor.realBaseNs = realVal;
        anchor.virtBaseNs = realVal;
        anchor.lastRate = rate;
        return realVal;
    }
    int64_t delta = realVal - anchor.realBaseNs;
    int64_t scaled = anchor.virtBaseNs + static_cast<int64_t>(static_cast<double>(delta) * anchor.lastRate);
    if (std::fabs(rate - anchor.lastRate) > 1e-6) {
        anchor.realBaseNs = realVal;
        anchor.virtBaseNs = scaled;
        anchor.lastRate = rate;
    }
    return scaled;
}

int hooked_clock_gettime(clockid_t clk_id, struct timespec* tp) {
    int rc = g_origClockGettime ? g_origClockGettime(clk_id, tp) : -1;
    if (rc != 0 || !tp) return rc;

    int64_t realNs = tsToNs(*tp);
    pollExternalSpeedConfig(realNs);
    double rate = g_speedRate.load(std::memory_order_acquire);
    if (clk_id >= 0 && clk_id < kMaxClocks) {
        std::lock_guard<std::mutex> lk(g_clockMtx);
        int64_t virtNs = scaleMonotonic(g_anchors[clk_id], realNs, rate);
        nsToTs(virtNs, *tp);
    }
    return rc;
}

int hooked_gettimeofday(struct timeval* tv, struct timezone* tz) {
    int rc = g_origGettimeofday ? g_origGettimeofday(tv, tz) : -1;
    if (rc != 0 || !tv) return rc;

    int64_t realUs = tvToUs(*tv);
    pollExternalSpeedConfig(realUs * 1000LL);
    double rate = g_speedRate.load(std::memory_order_acquire);
    {
        std::lock_guard<std::mutex> lk(g_clockMtx);
        int64_t virtUs = scaleMonotonic(g_gtodAnchor, realUs, rate);
        usToTv(virtUs, *tv);
    }
    return rc;
}

time_t hooked_time(time_t* tloc) {
    time_t realSec = g_origTime ? g_origTime(nullptr) : static_cast<time_t>(-1);
    if (realSec == static_cast<time_t>(-1)) return realSec;

    double rate = g_speedRate.load(std::memory_order_acquire);
    int64_t virtSec = realSec;
    {
        std::lock_guard<std::mutex> lk(g_clockMtx);
        virtSec = scaleMonotonic(g_timeAnchor, static_cast<int64_t>(realSec), rate);
    }
    if (tloc) *tloc = static_cast<time_t>(virtSec);
    return static_cast<time_t>(virtSec);
}

int hooked_usleep(useconds_t usec) {
    double rate = g_speedRate.load(std::memory_order_acquire);
    if (rate > 0.01 && std::fabs(rate - 1.0) > 1e-4) {
        double adj = static_cast<double>(usec) / rate;
        if (adj < 1.0) adj = 1.0;
        if (adj > 60000000.0) adj = 60000000.0;
        usec = static_cast<useconds_t>(adj);
    }
    return g_origUsleep ? g_origUsleep(usec) : 0;
}

int hooked_nanosleep(const struct timespec* req, struct timespec* rem) {
    if (!req || !g_origNanosleep) {
        return g_origNanosleep ? g_origNanosleep(req, rem) : 0;
    }
    double rate = g_speedRate.load(std::memory_order_acquire);
    if (rate > 0.01 && std::fabs(rate - 1.0) > 1e-4) {
        int64_t ns = tsToNs(*req);
        int64_t adjNs = static_cast<int64_t>(static_cast<double>(ns) / rate);
        if (adjNs < 1000LL) adjNs = 1000LL;
        struct timespec adjReq{};
        nsToTs(adjNs, adjReq);
        return g_origNanosleep(&adjReq, rem);
    }
    return g_origNanosleep(req, rem);
}
#endif

} // namespace

extern "C" {

__attribute__((visibility("default")))
int ggnext_agent_install_hooks() {
    if (g_hooksInstalled.exchange(true)) {
        return 0;
    }
#if GGNEXT_HAS_SHADOWHOOK
    int initRc = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false);
    if (initRc != 0 && initRc != SHADOWHOOK_ERRNO_OK) {
        AGENT_LOGW("shadowhook_init returned %d (errno=%d)", initRc, shadowhook_get_init_errno());
    }
    g_stubClockGettime = shadowhook_hook_sym_name(
        "libc.so", "clock_gettime",
        reinterpret_cast<void*>(hooked_clock_gettime),
        reinterpret_cast<void**>(&g_origClockGettime)
    );
    g_stubGettimeofday = shadowhook_hook_sym_name(
        "libc.so", "gettimeofday",
        reinterpret_cast<void*>(hooked_gettimeofday),
        reinterpret_cast<void**>(&g_origGettimeofday)
    );
    g_stubTime = shadowhook_hook_sym_name(
        "libc.so", "time",
        reinterpret_cast<void*>(hooked_time),
        reinterpret_cast<void**>(&g_origTime)
    );
    g_stubUsleep = shadowhook_hook_sym_name(
        "libc.so", "usleep",
        reinterpret_cast<void*>(hooked_usleep),
        reinterpret_cast<void**>(&g_origUsleep)
    );
    g_stubNanosleep = shadowhook_hook_sym_name(
        "libc.so", "nanosleep",
        reinterpret_cast<void*>(hooked_nanosleep),
        reinterpret_cast<void**>(&g_origNanosleep)
    );
    AGENT_LOGI("ShadowHook+xDL time hooks installed (clock_gettime=%p, gettimeofday=%p)",
               g_stubClockGettime, g_stubGettimeofday);
    return (g_stubClockGettime != nullptr || g_stubGettimeofday != nullptr) ? 0 : -1;
#else
    AGENT_LOGW("ShadowHook inline hooks are ARM/ARM64 only; xDL verified on x86/x86_64");
    void* libc = xdl_open("libc.so", XDL_DEFAULT);
    if (libc) xdl_close(libc);
    return 0;
#endif
}

__attribute__((visibility("default")))
void ggnext_agent_set_speed(float rate) {
    if (!std::isfinite(rate)) return;
    double clamped = static_cast<double>(rate);
    if (clamped < 0.01) clamped = 0.01;
    if (clamped > 100.0) clamped = 100.0;
    if (!g_hooksInstalled.load()) {
        ggnext_agent_install_hooks();
    }
    g_speedRate.store(clamped, std::memory_order_release);
}

__attribute__((visibility("default")))
float ggnext_agent_get_speed() {
    return static_cast<float>(g_speedRate.load(std::memory_order_acquire));
}

__attribute__((visibility("default")))
uint64_t ggnext_agent_alloc_page(uint64_t hintAddr, size_t size, int prot) {
    long pageSize = ::sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) pageSize = 4096;
    size_t alignedLen = (size == 0) ? static_cast<size_t>(pageSize)
                                    : ((size + static_cast<size_t>(pageSize) - 1) & ~(static_cast<size_t>(pageSize) - 1));
    void* p = ::mmap(
        reinterpret_cast<void*>(static_cast<uintptr_t>(hintAddr)),
        alignedLen,
        prot,
        MAP_PRIVATE | MAP_ANONYMOUS,
        -1,
        0
    );
    if (p == MAP_FAILED) return 0;
    return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(p));
}

__attribute__((visibility("default")))
int ggnext_agent_is_hook_active() {
    return g_hooksInstalled.load() ? 1 : 0;
}

__attribute__((constructor))
static void ggnext_agent_on_load() {
    // Auto-install hooks when loaded via LD_PRELOAD or wrap.<pkg>
    const char* autoEnv = std::getenv("GGNEXT_AUTO_SPEED");
    if (autoEnv && autoEnv[0] == '1') {
        ggnext_agent_install_hooks();
    }
}

} // extern "C"
