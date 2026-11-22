#include "mem_engine/types.hpp"
#include "mem_engine/maps_parser.hpp"
#include "mem_engine/process_enum.hpp"
#include "mem_engine/proc_io.hpp"
#include "mem_engine/search_engine.hpp"
#include "mem_engine/asm_engine.hpp"
#include "mem_engine/debugger.hpp"
#include "xdl.h"
#include <memory>
#include <mutex>
#include <cstring>
#include <fstream>
#include <algorithm>
#include <sstream>
#include <sys/mman.h>
#include <unistd.h>

extern "C" {
int ggnext_agent_install_hooks();
void ggnext_agent_set_speed(float rate);
float ggnext_agent_get_speed();
uint64_t ggnext_agent_alloc_page(uint64_t hintAddr, size_t size, int prot);
int ggnext_agent_is_hook_active();
}

#if __has_include(<jni.h>)
#include <jni.h>
#else
using jint      = int32_t;
using jlong     = int64_t;
using jboolean  = uint8_t;
using jfloat    = float;
using jdouble   = double;
using jbyte     = int8_t;
using jsize     = int32_t;
struct _jobject;
using jobject       = _jobject*;
using jclass        = _jobject*;
using jstring       = _jobject*;
using jintArray     = _jobject*;
using jlongArray    = _jobject*;
using jdoubleArray  = _jobject*;
using jbyteArray    = _jobject*;
using jobjectArray  = _jobject*;
struct JNIEnv {
    const char* GetStringUTFChars(jstring, jboolean*) { return ""; }
    void ReleaseStringUTFChars(jstring, const char*) {}
    jstring NewStringUTF(const char*) { return nullptr; }
    jsize GetArrayLength(jobject) { return 0; }
    jbyte* GetByteArrayElements(jbyteArray, jboolean*) { return nullptr; }
    void ReleaseByteArrayElements(jbyteArray, jbyte*, jint) {}
    jint* GetIntArrayElements(jintArray, jboolean*) { return nullptr; }
    void ReleaseIntArrayElements(jintArray, jint*, jint) {}
    jlong* GetLongArrayElements(jlongArray, jboolean*) { return nullptr; }
    void ReleaseLongArrayElements(jlongArray, jlong*, jint) {}
    jdouble* GetDoubleArrayElements(jdoubleArray, jboolean*) { return nullptr; }
    void ReleaseDoubleArrayElements(jdoubleArray, jdouble*, jint) {}
    jbyteArray NewByteArray(jsize) { return nullptr; }
    void SetByteArrayRegion(jbyteArray, jsize, jsize, const jbyte*) {}
    jlongArray NewLongArray(jsize) { return nullptr; }
    void SetLongArrayRegion(jlongArray, jsize, jsize, const jlong*) {}
};
#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL
#define JNI_TRUE 1
#define JNI_FALSE 0
#endif

namespace {

struct NativeEngineSession {
    std::mutex                                mtx;
    mem_engine::ProcIO                        io;
    std::unique_ptr<mem_engine::SearchEngine>      scanner;
    std::unique_ptr<mem_engine::ValueLocker>       locker;
    std::unique_ptr<mem_engine::BreakpointManager> debugger;
    mem_engine::CancellationToken                  cancelToken;
    std::vector<mem_engine::VmaRegion>             cachedAllRegions;
    mem_engine::VmaIndex                           vmaIndex;

    explicit NativeEngineSession(size_t threads, const std::string& spillDir)
        : scanner(std::make_unique<mem_engine::SearchEngine>(io, threads, spillDir)),
          locker(std::make_unique<mem_engine::ValueLocker>(io, 5)),
          debugger(std::make_unique<mem_engine::BreakpointManager>()) {}

    void refreshAllRegions() {
        if (io.pid() > 0) {
            cachedAllRegions = mem_engine::MapsParser::parseProcessMaps(io.pid(), 0, false);
        } else {
            cachedAllRegions.clear();
        }
        vmaIndex.rebuild(cachedAllRegions);
    }
};

constexpr uint32_t kGGNextRegionOrder[15] = {
    mem_engine::RegionFlag::CH,
    mem_engine::RegionFlag::JH,
    mem_engine::RegionFlag::CA,
    mem_engine::RegionFlag::CD,
    mem_engine::RegionFlag::CB,
    mem_engine::RegionFlag::PS,
    mem_engine::RegionFlag::A,
    mem_engine::RegionFlag::J,
    mem_engine::RegionFlag::S,
    mem_engine::RegionFlag::AS,
    mem_engine::RegionFlag::V,
    mem_engine::RegionFlag::O,
    mem_engine::RegionFlag::B,
    mem_engine::RegionFlag::XA,
    mem_engine::RegionFlag::XS
};

} // anonymous namespace

extern "C" {

// ============================================================================
// GGNext Native JNI Bridge (com.littlecodesauce.ggnext.engine.NativeMemBridge)
// ============================================================================

JNIEXPORT jlong JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeCreate(
    JNIEnv* env,
    jclass /*clazz*/,
    jint workerThreads,
    jstring spillDirJstr
) {
    std::string spillDir = "/data/local/tmp";
    if (spillDirJstr != nullptr) {
        const char* cstr = env->GetStringUTFChars(spillDirJstr, nullptr);
        if (cstr && cstr[0] != '\0') spillDir = cstr;
        if (cstr) env->ReleaseStringUTFChars(spillDirJstr, cstr);
    }
    auto* session = new NativeEngineSession(static_cast<size_t>(workerThreads > 0 ? workerThreads : 4), spillDir);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeDestroy(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (session) {
        session->cancelToken.cancel();
        delete session;
    }
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeAttach(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint pid
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->locker->unlockAll();
    auto rc = session->io.attach(static_cast<int>(pid));
    if (rc == mem_engine::EngineErrc::OK) {
        session->scanner->refreshRegions();
        session->refreshAllRegions();
    }
    return static_cast<jint>(rc);
}

JNIEXPORT jboolean JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeIsProcessAlive(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return JNI_FALSE;
    int pid = session->io.pid();
    if (pid <= 0) return JNI_FALSE;
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    return ::access(path, F_OK) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSetRegionMask(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint regionMask
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->scanner->setRegionMask(static_cast<uint32_t>(regionMask));
    if (session->io.pid() > 0) {
        session->scanner->refreshRegions();
    }
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSetAddressFilter(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jlong minAddr,
    jlong maxAddr,
    jlong addrMask,
    jlong addrPattern
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->scanner->setAddressFilter(
        static_cast<uint64_t>(minAddr),
        static_cast<uint64_t>(maxAddr),
        static_cast<uint64_t>(addrMask),
        static_cast<uint64_t>(addrPattern)
    );
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeCancelScan(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (session) {
        session->cancelToken.cancel();
    }
}

JNIEXPORT jlongArray JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeGetRegionTotals(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jint pid
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return nullptr;
    std::lock_guard<std::mutex> lk(session->mtx);

    if (pid > 0 && session->io.pid() == pid) {
        session->refreshAllRegions();
    } else if (pid > 0) {
        session->cachedAllRegions = mem_engine::MapsParser::parseProcessMaps(static_cast<int>(pid), 0, false);
        session->vmaIndex.rebuild(session->cachedAllRegions);
    }

    jlong out[45];
    std::memset(out, 0, sizeof(out));

    for (const auto& r : session->cachedAllRegions) {
        for (int idx = 0; idx < 15; ++idx) {
            if (r.regionFlag == kGGNextRegionOrder[idx]) {
                out[idx] += static_cast<jlong>(r.length);
                if (out[15 + idx] == 0) {
                    out[15 + idx] = static_cast<jlong>(r.startAddress);
                }
                out[30 + idx] += 1;
                break;
            }
        }
    }

    jlongArray arr = env->NewLongArray(45);
    if (arr) {
        env->SetLongArrayRegion(arr, 0, 45, out);
    }
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeGetRegionCodeAt(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlong address
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return env->NewStringUTF("O");
    std::lock_guard<std::mutex> lk(session->mtx);

    const char* tag = session->vmaIndex.regionTagAt(
        static_cast<uintptr_t>(address),
        session->io.targetIs64Bit()
    );
    return env->NewStringUTF(tag);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeClassifyPointer(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jlong ptrValue
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return 0;
    std::lock_guard<std::mutex> lk(session->mtx);

    uint32_t mask = session->vmaIndex.classifyPointer(
        static_cast<uint64_t>(ptrValue),
        session->io.targetIs64Bit()
    );
    return static_cast<jint>(mask);
}

JNIEXPORT jbyteArray JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeReadBlock(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlong address,
    jint size
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || size <= 0 || size > (16 * 1024 * 1024)) return nullptr;
    std::lock_guard<std::mutex> lk(session->mtx);

    std::vector<jbyte> buf(static_cast<size_t>(size), 0);
    size_t bytesRead = 0;
    session->io.readMemory(static_cast<uintptr_t>(address), buf.data(), static_cast<size_t>(size), &bytesRead);

    jbyteArray arr = env->NewByteArray(size);
    if (arr) {
        env->SetByteArrayRegion(arr, 0, size, buf.data());
    }
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeWriteBlock(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlong address,
    jbyteArray bytesJarr
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !bytesJarr) return JNI_FALSE;
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(bytesJarr);
    jbyte* ptr = env->GetByteArrayElements(bytesJarr, nullptr);
    if (!ptr) return JNI_FALSE;

    auto rc = session->io.writeMemory(static_cast<uintptr_t>(address), ptr, static_cast<size_t>(len));
    env->ReleaseByteArrayElements(bytesJarr, ptr, 0);
    return rc == mem_engine::EngineErrc::OK ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeCopyMemory(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jlong fromAddr,
    jlong toAddr,
    jlong length
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || length <= 0) return 0;
    std::lock_guard<std::mutex> lk(session->mtx);

    const size_t total = static_cast<size_t>(std::min<int64_t>(length, 16LL * 1024LL * 1024LL));
    const size_t chunk = 64 * 1024;
    std::vector<uint8_t> buf(chunk);
    size_t copied = 0;

    while (copied < total) {
        size_t step = std::min(chunk, total - copied);
        size_t bytesRead = 0;
        auto rrc = session->io.readMemory(
            static_cast<uintptr_t>(fromAddr) + copied,
            buf.data(),
            step,
            &bytesRead
        );
        if (rrc != mem_engine::EngineErrc::OK || bytesRead == 0) break;
        auto wrc = session->io.writeMemory(
            static_cast<uintptr_t>(toAddr) + copied,
            buf.data(),
            bytesRead
        );
        if (wrc != mem_engine::EngineErrc::OK) break;
        copied += bytesRead;
    }
    return static_cast<jlong>(copied);
}

JNIEXPORT jlong JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeDumpMemory(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlong startAddr,
    jlong endAddr,
    jstring outFilePathJstr
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || endAddr <= startAddr || !outFilePathJstr) return 0;
    std::lock_guard<std::mutex> lk(session->mtx);

    const char* cpath = env->GetStringUTFChars(outFilePathJstr, nullptr);
    std::string outPath = cpath ? cpath : "";
    if (cpath) env->ReleaseStringUTFChars(outFilePathJstr, cpath);
    if (outPath.empty()) return 0;

    std::ofstream ofs(outPath, std::ios::out | std::ios::binary | std::ios::trunc);
    if (!ofs.is_open()) return 0;

    const size_t total = static_cast<size_t>(std::min<int64_t>(endAddr - startAddr, 64LL * 1024LL * 1024LL));
    const size_t chunk = 64 * 1024;
    std::vector<char> buf(chunk, 0);
    size_t written = 0;

    while (written < total) {
        size_t step = std::min(chunk, total - written);
        size_t bytesRead = 0;
        std::memset(buf.data(), 0, step);
        session->io.readMemory(
            static_cast<uintptr_t>(startAddr) + written,
            buf.data(),
            step,
            &bytesRead
        );
        ofs.write(buf.data(), static_cast<std::streamsize>(step));
        written += step;
    }
    ofs.close();
    return static_cast<jlong>(written);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchExact(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jlong intTarget,
    jdouble floatTarget,
    jdouble epsilon,
    jboolean aligned
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->searchExact(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<int64_t>(intTarget),
        static_cast<double>(floatTarget),
        static_cast<double>(epsilon),
        aligned == JNI_TRUE,
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRefineExact(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jlong intTarget,
    jdouble floatTarget,
    jdouble epsilon
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->refineExact(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<int64_t>(intTarget),
        static_cast<double>(floatTarget),
        static_cast<double>(epsilon),
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchNotEqual(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jlong intTarget,
    jdouble floatTarget,
    jdouble epsilon,
    jboolean aligned
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->searchNotEqual(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<int64_t>(intTarget),
        static_cast<double>(floatTarget),
        static_cast<double>(epsilon),
        aligned == JNI_TRUE,
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRefineNotEqual(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jlong intTarget,
    jdouble floatTarget,
    jdouble epsilon
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->refineNotEqual(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<int64_t>(intTarget),
        static_cast<double>(floatTarget),
        static_cast<double>(epsilon),
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchRange(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jlong intMin,
    jlong intMax,
    jdouble floatMin,
    jdouble floatMax,
    jboolean inclusive,
    jboolean aligned,
    jboolean excludeRange
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->searchRange(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<int64_t>(intMin),
        static_cast<int64_t>(intMax),
        static_cast<double>(floatMin),
        static_cast<double>(floatMax),
        inclusive == JNI_TRUE,
        aligned == JNI_TRUE,
        &session->cancelToken,
        nullptr,
        excludeRange == JNI_TRUE
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRefineRange(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jlong intMin,
    jlong intMax,
    jdouble floatMin,
    jdouble floatMax,
    jboolean inclusive,
    jboolean excludeRange
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->refineRange(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<int64_t>(intMin),
        static_cast<int64_t>(intMax),
        static_cast<double>(floatMin),
        static_cast<double>(floatMax),
        inclusive == JNI_TRUE,
        &session->cancelToken,
        nullptr,
        excludeRange == JNI_TRUE
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchPointers(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jlong targetAddress,
    jlong maxOffset,
    jint protMask,
    jboolean aligned
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->searchPointers(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<uintptr_t>(targetAddress),
        static_cast<uint64_t>(maxOffset >= 0 ? maxOffset : -maxOffset),
        static_cast<uint32_t>(protMask),
        aligned == JNI_TRUE,
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRefinePointers(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jlong targetAddress,
    jlong maxOffset,
    jint protMask
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->refinePointers(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<uintptr_t>(targetAddress),
        static_cast<uint64_t>(maxOffset >= 0 ? maxOffset : -maxOffset),
        static_cast<uint32_t>(protMask),
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchGroup(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jintArray typesJarr,
    jlongArray intValsJarr,
    jdoubleArray floatValsJarr,
    jlongArray intMaxValsJarr,
    jdoubleArray floatMaxValsJarr,
    jintArray fieldOpsJarr,
    jint windowSize,
    jboolean ordered,
    jboolean aligned
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !typesJarr || !intValsJarr || !floatValsJarr) {
        return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    }
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(typesJarr);
    if (len <= 0) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);

    jint* typesPtr = env->GetIntArrayElements(typesJarr, nullptr);
    jlong* intPtr = env->GetLongArrayElements(intValsJarr, nullptr);
    jdouble* fltPtr = env->GetDoubleArrayElements(floatValsJarr, nullptr);
    jlong* intMaxPtr = intMaxValsJarr ? env->GetLongArrayElements(intMaxValsJarr, nullptr) : nullptr;
    jdouble* fltMaxPtr = floatMaxValsJarr ? env->GetDoubleArrayElements(floatMaxValsJarr, nullptr) : nullptr;
    jint* opsPtr = fieldOpsJarr ? env->GetIntArrayElements(fieldOpsJarr, nullptr) : nullptr;

    mem_engine::GroupSearchQuery query;
    query.fields.resize(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        query.fields[static_cast<size_t>(i)].type = static_cast<mem_engine::DataType>(typesPtr[i]);
        query.fields[static_cast<size_t>(i)].iVal = static_cast<int64_t>(intPtr[i]);
        query.fields[static_cast<size_t>(i)].fVal = static_cast<double>(fltPtr[i]);
        query.fields[static_cast<size_t>(i)].iMax = intMaxPtr ? static_cast<int64_t>(intMaxPtr[i]) : static_cast<int64_t>(intPtr[i]);
        query.fields[static_cast<size_t>(i)].fMax = fltMaxPtr ? static_cast<double>(fltMaxPtr[i]) : static_cast<double>(fltPtr[i]);
        query.fields[static_cast<size_t>(i)].op = opsPtr ? static_cast<uint8_t>(opsPtr[i]) : 0;
        query.fields[static_cast<size_t>(i)].epsilon = 1e-4;
        query.fields[static_cast<size_t>(i)].origIndex = static_cast<size_t>(i);
    }
    query.maxSpanBytes = static_cast<size_t>(windowSize > 0 ? windowSize : 512);
    query.ordered = (ordered == JNI_TRUE);
    query.aligned = (aligned == JNI_TRUE);

    env->ReleaseIntArrayElements(typesJarr, typesPtr, 0);
    env->ReleaseLongArrayElements(intValsJarr, intPtr, 0);
    env->ReleaseDoubleArrayElements(floatValsJarr, fltPtr, 0);
    if (intMaxPtr) env->ReleaseLongArrayElements(intMaxValsJarr, intMaxPtr, 0);
    if (fltMaxPtr) env->ReleaseDoubleArrayElements(floatMaxValsJarr, fltMaxPtr, 0);
    if (opsPtr) env->ReleaseIntArrayElements(fieldOpsJarr, opsPtr, 0);

    session->cancelToken.reset();
    auto rc = session->scanner->searchGroup(query, &session->cancelToken);
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRefineGroup(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jintArray typesJarr,
    jlongArray intValsJarr,
    jdoubleArray floatValsJarr,
    jlongArray intMaxValsJarr,
    jdoubleArray floatMaxValsJarr,
    jintArray fieldOpsJarr,
    jint windowSize,
    jboolean ordered,
    jboolean aligned
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !typesJarr || !intValsJarr || !floatValsJarr) {
        return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    }
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(typesJarr);
    if (len <= 0) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);

    jint* typesPtr = env->GetIntArrayElements(typesJarr, nullptr);
    jlong* intPtr = env->GetLongArrayElements(intValsJarr, nullptr);
    jdouble* fltPtr = env->GetDoubleArrayElements(floatValsJarr, nullptr);
    jlong* intMaxPtr = intMaxValsJarr ? env->GetLongArrayElements(intMaxValsJarr, nullptr) : nullptr;
    jdouble* fltMaxPtr = floatMaxValsJarr ? env->GetDoubleArrayElements(floatMaxValsJarr, nullptr) : nullptr;
    jint* opsPtr = fieldOpsJarr ? env->GetIntArrayElements(fieldOpsJarr, nullptr) : nullptr;

    mem_engine::GroupSearchQuery query;
    query.fields.resize(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        query.fields[static_cast<size_t>(i)].type = static_cast<mem_engine::DataType>(typesPtr[i]);
        query.fields[static_cast<size_t>(i)].iVal = static_cast<int64_t>(intPtr[i]);
        query.fields[static_cast<size_t>(i)].fVal = static_cast<double>(fltPtr[i]);
        query.fields[static_cast<size_t>(i)].iMax = intMaxPtr ? static_cast<int64_t>(intMaxPtr[i]) : static_cast<int64_t>(intPtr[i]);
        query.fields[static_cast<size_t>(i)].fMax = fltMaxPtr ? static_cast<double>(fltMaxPtr[i]) : static_cast<double>(fltPtr[i]);
        query.fields[static_cast<size_t>(i)].op = opsPtr ? static_cast<uint8_t>(opsPtr[i]) : 0;
        query.fields[static_cast<size_t>(i)].epsilon = 1e-4;
        query.fields[static_cast<size_t>(i)].origIndex = static_cast<size_t>(i);
    }
    query.maxSpanBytes = static_cast<size_t>(windowSize > 0 ? windowSize : 512);
    query.ordered = (ordered == JNI_TRUE);
    query.aligned = (aligned == JNI_TRUE);

    env->ReleaseIntArrayElements(typesJarr, typesPtr, 0);
    env->ReleaseLongArrayElements(intValsJarr, intPtr, 0);
    env->ReleaseDoubleArrayElements(floatValsJarr, fltPtr, 0);
    if (intMaxPtr) env->ReleaseLongArrayElements(intMaxValsJarr, intMaxPtr, 0);
    if (fltMaxPtr) env->ReleaseDoubleArrayElements(floatMaxValsJarr, fltMaxPtr, 0);
    if (opsPtr) env->ReleaseIntArrayElements(fieldOpsJarr, opsPtr, 0);

    session->cancelToken.reset();
    auto rc = session->scanner->refineGroup(query, &session->cancelToken);
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchPattern(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jbyteArray patternJarr,
    jstring maskJstr
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !patternJarr) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(patternJarr);
    if (len <= 0) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    jbyte* ptr = env->GetByteArrayElements(patternJarr, nullptr);
    std::vector<uint8_t> pat(reinterpret_cast<uint8_t*>(ptr), reinterpret_cast<uint8_t*>(ptr) + len);
    env->ReleaseByteArrayElements(patternJarr, ptr, 0);

    std::vector<uint8_t> maskBytes(static_cast<size_t>(len), 0xFF);
    if (maskJstr != nullptr) {
        const char* cmask = env->GetStringUTFChars(maskJstr, nullptr);
        if (cmask && cmask[0] != '\0') {
            size_t mlen = std::min(static_cast<size_t>(len), std::strlen(cmask));
            for (size_t i = 0; i < mlen; ++i) {
                maskBytes[i] = (cmask[i] == '?' || cmask[i] == '.') ? 0x00 : 0xFF;
            }
        }
        if (cmask) env->ReleaseStringUTFChars(maskJstr, cmask);
    }

    session->cancelToken.reset();
    auto rc = session->scanner->searchBytePattern(pat, maskBytes, &session->cancelToken);
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSearchEncoded(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jint encodedMode,
    jlong logicalInt,
    jdouble logicalFloat,
    jlong keyOrMask,
    jboolean aligned
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->searchEncoded(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<mem_engine::EncodedMode>(encodedMode),
        static_cast<int64_t>(logicalInt),
        static_cast<double>(logicalFloat),
        static_cast<uint64_t>(keyOrMask),
        aligned == JNI_TRUE,
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRefineEncoded(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType,
    jint encodedMode,
    jlong logicalInt,
    jdouble logicalFloat,
    jlong keyOrMask
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->refineEncoded(
        static_cast<mem_engine::DataType>(dataType),
        static_cast<mem_engine::EncodedMode>(encodedMode),
        static_cast<int64_t>(logicalInt),
        static_cast<double>(logicalFloat),
        static_cast<uint64_t>(keyOrMask),
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeStartUnknownSnapshot(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint dataType
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->startUnknownSnapshot(
        static_cast<mem_engine::DataType>(dataType),
        &session->cancelToken
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRefineFuzzy(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint deltaOp,
    jlong intDelta,
    jdouble floatDelta,
    jdouble epsilon,
    jint onlyNth
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return static_cast<jint>(mem_engine::EngineErrc::ERR_INVAL);
    std::lock_guard<std::mutex> lk(session->mtx);
    session->cancelToken.reset();
    auto rc = session->scanner->refineFuzzy(
        static_cast<mem_engine::SearchOp>(deltaOp),
        static_cast<int64_t>(intDelta),
        static_cast<double>(floatDelta),
        static_cast<double>(epsilon),
        &session->cancelToken,
        nullptr,
        static_cast<uint32_t>(onlyNth > 0 ? onlyNth : 0)
    );
    return static_cast<jint>(rc);
}

JNIEXPORT jlong JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeGetCandidateCount(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return 0;
    std::lock_guard<std::mutex> lk(session->mtx);
    return static_cast<jlong>(session->scanner->totalMatchedCount());
}

JNIEXPORT jlongArray JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeGetCandidatesPacked(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jint offset,
    jint limit
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || offset < 0 || limit <= 0) return nullptr;
    std::lock_guard<std::mutex> lk(session->mtx);

    std::vector<mem_engine::CandidateEntry> slice;
    session->scanner->fetchCandidates(static_cast<size_t>(offset), static_cast<size_t>(limit), slice);
    if (slice.empty()) return env->NewLongArray(0);

    size_t count = slice.size();
    std::vector<jlong> packed(count * 3);
    for (size_t i = 0; i < count; ++i) {
        const auto& c = slice[i];
        packed[i * 3 + 0] = static_cast<jlong>(c.address);
        packed[i * 3 + 1] = static_cast<jlong>(c.rawValue);
        packed[i * 3 + 2] = static_cast<jlong>(
            static_cast<uint64_t>(c.type) |
            (static_cast<uint64_t>(c.regionTag) << 8) |
            (static_cast<uint64_t>(c.flags) << 16)
        );
    }

    jlongArray arr = env->NewLongArray(static_cast<jsize>(count * 3));
    if (arr) {
        env->SetLongArrayRegion(arr, 0, static_cast<jsize>(count * 3), packed.data());
    }
    return arr;
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeRemoveCandidates(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlongArray addressesJarr
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !addressesJarr) return;
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(addressesJarr);
    if (len <= 0) return;
    jlong* ptr = env->GetLongArrayElements(addressesJarr, nullptr);
    if (!ptr) return;

    session->scanner->removeCandidates(reinterpret_cast<const uint64_t*>(ptr), static_cast<size_t>(len));
    env->ReleaseLongArrayElements(addressesJarr, ptr, 0);
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeClearSession(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->scanner->clearSession();
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeLockAddress(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlong address,
    jbyteArray bytesJarr
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !bytesJarr) return;
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(bytesJarr);
    jbyte* ptr = env->GetByteArrayElements(bytesJarr, nullptr);
    if (!ptr) return;

    session->locker->lockAddress(static_cast<uintptr_t>(address), ptr, static_cast<size_t>(len));
    env->ReleaseByteArrayElements(bytesJarr, ptr, 0);
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeLockAddressConfigured(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jlong address,
    jbyteArray bytesJarr,
    jint dataType,
    jint freezeMode,
    jlong minVal,
    jlong maxVal
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !bytesJarr) return;
    std::lock_guard<std::mutex> lk(session->mtx);

    jsize len = env->GetArrayLength(bytesJarr);
    jbyte* ptr = env->GetByteArrayElements(bytesJarr, nullptr);
    if (!ptr) return;

    session->locker->lockAddressConfigured(
        static_cast<uintptr_t>(address),
        ptr,
        static_cast<size_t>(len),
        static_cast<mem_engine::DataType>(dataType),
        static_cast<mem_engine::FreezeMode>(freezeMode),
        static_cast<int64_t>(minVal),
        static_cast<int64_t>(maxVal)
    );
    env->ReleaseByteArrayElements(bytesJarr, ptr, 0);
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSetFreezePaused(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jboolean paused
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->locker->setPaused(paused == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeSetFreezeIntervalMs(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint intervalMs
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || intervalMs <= 0) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->locker->setIntervalMs(static_cast<uint32_t>(intervalMs));
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeUnlockAddress(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jlong address
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->locker->unlockAddress(static_cast<uintptr_t>(address));
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeUnlockAll(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session) return;
    std::lock_guard<std::mutex> lk(session->mtx);
    session->locker->unlockAll();
}

// ============================================================================
// Hardware Breakpoint / Watchpoint JNI Bridge (Perf & Ptrace, supports No-Root)
// ============================================================================

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeBpAdd(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint pid,
    jlong address,
    jint bpType,
    jint bpSize,
    jint bpMode,
    jboolean captureCallstack
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !session->debugger) return -1;
    int targetPid = (pid > 0) ? static_cast<int>(pid) : session->io.pid();
    return static_cast<jint>(session->debugger->addBreakpoint(
        targetPid,
        static_cast<uint64_t>(address),
        static_cast<mem_engine::BpType>(bpType),
        static_cast<int32_t>(bpSize),
        static_cast<mem_engine::BpMode>(bpMode),
        captureCallstack == JNI_TRUE,
        nullptr
    ));
}

JNIEXPORT jboolean JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeBpRemove(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint bpId
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !session->debugger) return JNI_FALSE;
    return session->debugger->removeBreakpoint(static_cast<int32_t>(bpId)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeBpSetPaused(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint bpId,
    jboolean paused
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !session->debugger) return JNI_FALSE;
    if (bpId <= 0) {
        return session->debugger->setAllPaused(paused == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
    }
    return session->debugger->setPaused(static_cast<int32_t>(bpId), paused == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeBpClearHits(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle,
    jint bpId
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !session->debugger) return;
    if (bpId <= 0) session->debugger->clearAllHits();
    else session->debugger->clearHits(static_cast<int32_t>(bpId));
}

JNIEXPORT void JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeBpStopAll(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !session->debugger) return;
    session->debugger->stopAll();
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeBpSnapshotJson(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<NativeEngineSession*>(handle);
    if (!session || !session->debugger) return env->NewStringUTF("[]");
    std::string json = session->debugger->snapshotsToJson();
    return env->NewStringUTF(json.c_str());
}

// ============================================================================
// Dynamic Keystone & Capstone Assembly Engine JNI Bridge
// ============================================================================

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeLoadAsmEngine(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring libDirJstr
) {
    std::string dir;
    if (libDirJstr) {
        const char* c = env->GetStringUTFChars(libDirJstr, nullptr);
        if (c) {
            dir = c;
            env->ReleaseStringUTFChars(libDirJstr, c);
        }
    }
    std::string err;
    bool ok = mem_engine::AsmEngine::instance().loadFromDir(dir, &err);
    if (ok) return env->NewStringUTF("OK");
    return env->NewStringUTF(err.empty() ? "LOAD_FAILED" : err.c_str());
}

JNIEXPORT jint JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeGetAsmEngineFlags(
    JNIEnv* /*env*/,
    jclass /*clazz*/
) {
    int flags = 0;
    if (mem_engine::AsmEngine::instance().isKeystoneLoaded()) flags |= 1;
    if (mem_engine::AsmEngine::instance().isCapstoneLoaded()) flags |= 2;
    return static_cast<jint>(flags);
}

JNIEXPORT jbyteArray JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeAssemble(
    JNIEnv* env,
    jclass /*clazz*/,
    jint arch,
    jlong address,
    jstring codeJstr
) {
    if (!codeJstr) return nullptr;
    const char* c = env->GetStringUTFChars(codeJstr, nullptr);
    std::string code = c ? c : "";
    if (c) env->ReleaseStringUTFChars(codeJstr, c);

    auto res = mem_engine::AsmEngine::instance().assemble(
        static_cast<mem_engine::AsmArch>(arch),
        static_cast<uint64_t>(address),
        code
    );
    if (!res.ok || res.bytes.empty()) return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(res.bytes.size()));
    if (arr) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(res.bytes.size()), reinterpret_cast<const jbyte*>(res.bytes.data()));
    }
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeAssembleWithError(
    JNIEnv* env,
    jclass /*clazz*/,
    jint arch,
    jlong address,
    jstring codeJstr
) {
    if (!codeJstr) return env->NewStringUTF("ERR:汇编代码为空");
    const char* c = env->GetStringUTFChars(codeJstr, nullptr);
    std::string code = c ? c : "";
    if (c) env->ReleaseStringUTFChars(codeJstr, c);

    auto res = mem_engine::AsmEngine::instance().assemble(
        static_cast<mem_engine::AsmArch>(arch),
        static_cast<uint64_t>(address),
        code
    );
    if (!res.ok) {
        std::string msg = "ERR:" + res.error;
        return env->NewStringUTF(msg.c_str());
    }
    static const char* kHex = "0123456789ABCDEF";
    std::string out = "OK:";
    out.reserve(3 + res.bytes.size() * 2);
    for (uint8_t b : res.bytes) {
        out.push_back(kHex[(b >> 4) & 0xF]);
        out.push_back(kHex[b & 0xF]);
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeDisassembleOne(
    JNIEnv* env,
    jclass /*clazz*/,
    jint arch,
    jlong address,
    jbyteArray bytesJarr
) {
    if (!bytesJarr) return env->NewStringUTF("");
    jsize len = env->GetArrayLength(bytesJarr);
    if (len <= 0) return env->NewStringUTF("");
    jbyte* ptr = env->GetByteArrayElements(bytesJarr, nullptr);
    if (!ptr) return env->NewStringUTF("");
    std::string text = mem_engine::AsmEngine::instance().disassembleOne(
        static_cast<mem_engine::AsmArch>(arch),
        static_cast<uint64_t>(address),
        reinterpret_cast<const uint8_t*>(ptr),
        static_cast<size_t>(len)
    );
    env->ReleaseByteArrayElements(bytesJarr, ptr, 0);
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeDisassembleBlock(
    JNIEnv* env,
    jclass /*clazz*/,
    jint arch,
    jlong baseAddress,
    jbyteArray bytesJarr,
    jint maxInsns
) {
    if (!bytesJarr) return env->NewStringUTF("");
    jsize len = env->GetArrayLength(bytesJarr);
    if (len <= 0) return env->NewStringUTF("");
    jbyte* ptr = env->GetByteArrayElements(bytesJarr, nullptr);
    if (!ptr) return env->NewStringUTF("");
    auto insns = mem_engine::AsmEngine::instance().disassembleBlock(
        static_cast<mem_engine::AsmArch>(arch),
        static_cast<uint64_t>(baseAddress),
        reinterpret_cast<const uint8_t*>(ptr),
        static_cast<size_t>(len),
        static_cast<size_t>(maxInsns > 0 ? maxInsns : 256)
    );
    env->ReleaseByteArrayElements(bytesJarr, ptr, 0);

    std::ostringstream oss;
    for (size_t i = 0; i < insns.size(); ++i) {
        if (i > 0) oss << '\n';
        oss << std::hex << insns[i].address << '|' << std::dec << insns[i].size << '|' << insns[i].text;
    }
    std::string out = oss.str();
    return env->NewStringUTF(out.c_str());
}

// ============================================================================
// ShadowHook + xDL Agent (Speedhack & Local Memory Page Allocation)
// ============================================================================

JNIEXPORT jboolean JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeAgentSetSpeed(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jfloat rate
) {
    ggnext_agent_set_speed(static_cast<float>(rate));
    return ggnext_agent_is_hook_active() != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeAgentGetSpeed(
    JNIEnv* /*env*/,
    jclass /*clazz*/
) {
    return static_cast<jfloat>(ggnext_agent_get_speed());
}

JNIEXPORT jlong JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeAgentAllocPage(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong hintAddr,
    jint size,
    jint prot
) {
    return static_cast<jlong>(ggnext_agent_alloc_page(
        static_cast<uint64_t>(hintAddr),
        static_cast<size_t>(size > 0 ? size : 4096),
        static_cast<int>(prot)
    ));
}

JNIEXPORT jboolean JNICALL
Java_com_littlecodesauce_ggnext_engine_NativeMemBridge_nativeVerifyXdlAndHook(
    JNIEnv* /*env*/,
    jclass /*clazz*/
) {
    void* libc = xdl_open("libc.so", XDL_DEFAULT);
    if (!libc) return JNI_FALSE;
    void* sym = xdl_sym(libc, "clock_gettime", nullptr);
    xdl_close(libc);
    return sym != nullptr ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
