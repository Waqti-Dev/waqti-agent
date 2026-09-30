# Toolchain for building Waqti's native runtime *on the Android device itself*.
#
# Why this file exists: the stock NDK toolchain
# ($NDK/build/cmake/android.toolchain.cmake) drives the NDK's own prebuilt
# clang, and those prebuilt tools ship only for x86_64/i686 hosts — they cannot
# execute on this aarch64 Android build host (CMake even reports
# CMAKE_HOST_SYSTEM_NAME=Android, which the NDK toolchain does not handle, so it
# resolves the compiler as toolchains/llvm/prebuilt/bin/clang — a path that does
# not exist). Gradle appends this file *after* the NDK one, and CMake keeps the
# last -DCMAKE_TOOLCHAIN_FILE, so this file wins while AGP's normal Android
# configuration (ABI, API level, NDK flags) still applies.
#
# We keep CMake's Android support and simply point it at:
#   * the host's LLVM toolchain (Termux clang, which targets
#     aarch64-linux-android natively), and
#   * the NDK's Android sysroot (headers, crt objects, stub libraries and the
#     static libc++ that the packaged .so links against).
#
# Optional overrides passed in by Gradle (see app/build.gradle.kts):
#   WAAQTI_HOST_LLVM_BIN - directory containing clang/clang++/llvm-* tools
#   WAAQTI_NDK_SYSROOT   - <ndk>/toolchains/llvm/prebuilt/<host>/sysroot
# Both have derivable defaults, so CMake's internal try_compile() projects
# (compiler ABI detection) also succeed when they re-run this file.

cmake_minimum_required(VERSION 3.14...3.28)

# Forward our inputs into CMake's try_compile() sub-configures.
list(APPEND CMAKE_TRY_COMPILE_PLATFORM_VARIABLES WAAQTI_HOST_LLVM_BIN WAAQTI_NDK_SYSROOT)

set(CMAKE_SYSTEM_NAME Android)
set(CMAKE_SYSTEM_PROCESSOR aarch64)

# Android API level: AGP passes -DCMAKE_SYSTEM_VERSION=<api> and
# -DANDROID_PLATFORM=android-<api>. Accept either; default to the app minSdk.
if(NOT CMAKE_SYSTEM_VERSION OR CMAKE_SYSTEM_VERSION STREQUAL "1")
  if(DEFINED ANDROID_PLATFORM)
    string(REGEX REPLACE ".*android-" "" CMAKE_SYSTEM_VERSION "${ANDROID_PLATFORM}")
  else()
    set(CMAKE_SYSTEM_VERSION 30)
  endif()
endif()

if(DEFINED ANDROID_ABI AND NOT ANDROID_ABI STREQUAL "arm64-v8a")
  message(FATAL_ERROR "Waqti builds arm64-v8a only, but ANDROID_ABI='${ANDROID_ABI}'")
endif()

# --- NDK sysroot (Android headers, crt objects, stub libraries, libc++) -------
if(NOT WAAQTI_NDK_SYSROOT AND CMAKE_ANDROID_NDK)
  file(GLOB _waqti_sysroots LIST_DIRECTORIES true
       "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/*/sysroot")
  if(_waqti_sysroots)
    list(GET _waqti_sysroots 0 WAAQTI_NDK_SYSROOT)
  endif()
endif()
if(NOT WAAQTI_NDK_SYSROOT OR NOT IS_DIRECTORY "${WAAQTI_NDK_SYSROOT}")
  message(FATAL_ERROR
    "Waqti: no Android sysroot found. Pass -DWAAQTI_NDK_SYSROOT="
    "<ndk>/toolchains/llvm/prebuilt/<host>/sysroot (or set CMAKE_ANDROID_NDK).")
endif()

# --- host LLVM toolchain ------------------------------------------------------
if(NOT WAAQTI_HOST_LLVM_BIN AND DEFINED ENV{PREFIX} AND EXISTS "$ENV{PREFIX}/bin/clang")
  set(WAAQTI_HOST_LLVM_BIN "$ENV{PREFIX}/bin")
endif()
if(NOT WAAQTI_HOST_LLVM_BIN OR NOT EXISTS "${WAAQTI_HOST_LLVM_BIN}/clang")
  message(FATAL_ERROR
    "Waqti: no host clang found. Pass -DWAAQTI_HOST_LLVM_BIN=<dir containing clang>.")
endif()

set(CMAKE_SYSROOT "${WAAQTI_NDK_SYSROOT}")

set(CMAKE_C_COMPILER   "${WAAQTI_HOST_LLVM_BIN}/clang")
set(CMAKE_CXX_COMPILER "${WAAQTI_HOST_LLVM_BIN}/clang++")

# Clang turns these into --target=aarch64-linux-android<api>, which is what
# selects the API level, the crt objects and the stub libraries in the sysroot.
set(CMAKE_C_COMPILER_TARGET   "aarch64-linux-android${CMAKE_SYSTEM_VERSION}")
set(CMAKE_CXX_COMPILER_TARGET "aarch64-linux-android${CMAKE_SYSTEM_VERSION}")

# Every translation unit must be position independent: llama.cpp and ggml are
# built as static libraries and then linked into this module's shared object.
# (The stock NDK toolchain sets the same variable for ANDROID_PIE.) Without it
# the final link fails with "relocation R_AARCH64_ADR_PREL_PG_HI21 cannot be
# used against symbol ... recompile with -fPIC".
if(NOT DEFINED CMAKE_POSITION_INDEPENDENT_CODE)
  set(CMAKE_POSITION_INDEPENDENT_CODE TRUE)
endif()

# Binutils: the NDK's llvm-* tools are x86_64 binaries, use the host's.
# The host clang defaults to linking a static libunwind.a (a Termux-ism); the
# NDK does not ship one for Android targets and Android's bionic libc.so
# provides the _Unwind_* entry points (they exist in the API >= 30 stubs), so
# official NDK builds link no unwinder either. Match that, otherwise every link
# fails with "unable to find library -l:libunwind.a".
set(CMAKE_EXE_LINKER_FLAGS_INIT    "-unwindlib=none")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-unwindlib=none")
set(CMAKE_MODULE_LINKER_FLAGS_INIT "-unwindlib=none")

# Static C++ runtime, so the packaged .so is self-contained (no libc++_shared.so
# to ship or resolve at runtime). The host clang defaults to -lc++_shared and
# ignores -static-libstdc++ for Android targets, so opt out of the driver
# default and name the NDK archives explicitly. Absolute paths keep the linker
# from ever picking Termux's own libc.a/libc++.so out of a search path.
set(WAAQTI_NDK_CXX_LIBDIR "${WAAQTI_NDK_SYSROOT}/usr/lib/aarch64-linux-android")
set(_waqti_cxx_stdlibs
    "-nostdlib++ ${WAAQTI_NDK_CXX_LIBDIR}/libc++_static.a ${WAAQTI_NDK_CXX_LIBDIR}/libc++abi.a")
set(CMAKE_CXX_STANDARD_LIBRARIES_INIT "${_waqti_cxx_stdlibs}")
set(CMAKE_CXX_STANDARD_LIBRARIES "${_waqti_cxx_stdlibs}")

set(CMAKE_AR      "${WAAQTI_HOST_LLVM_BIN}/llvm-ar")
set(CMAKE_RANLIB  "${WAAQTI_HOST_LLVM_BIN}/llvm-ranlib")
set(CMAKE_STRIP   "${WAAQTI_HOST_LLVM_BIN}/llvm-strip")
set(CMAKE_NM      "${WAAQTI_HOST_LLVM_BIN}/llvm-nm")
set(CMAKE_OBJCOPY "${WAAQTI_HOST_LLVM_BIN}/llvm-objcopy")
set(CMAKE_OBJDUMP "${WAAQTI_HOST_LLVM_BIN}/llvm-objdump")
set(CMAKE_READELF "${WAAQTI_HOST_LLVM_BIN}/llvm-readelf")
# CMAKE_LINKER must be a *cache* entry, not a plain variable: with only a
# normal set(), CMake skips linker detection and never publishes it in the
# cache — and the Android Gradle Plugin reads exactly this value
# (cache.getCacheString(CMAKE_LINKER)!!) while parsing the CMake File API
# reply. A plain set() here made AGP fail with "There was an error parsing
# CMake File API result".
set(CMAKE_LINKER "${WAAQTI_HOST_LLVM_BIN}/ld.lld" CACHE FILEPATH "linker used by the Android build" FORCE)

# Search only the Android sysroot — never the host's Termux prefix, so nothing
# from the build environment leaks into the packaged library.
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
