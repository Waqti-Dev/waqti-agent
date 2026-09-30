cmake_minimum_required(VERSION 3.22.1)

# Native code is always built in release mode: AGP passes the Gradle variant's
# build type, which would otherwise link an unusably large debug .so.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(waqti_local_runtime LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)

# Path to the llama.cpp source tree, read from local.properties by Gradle
# (same mechanism as sdk.dir). The revision used for a build is recorded in
# docs/ON_DEVICE_INFERENCE.md.
if (NOT LLAMA_CPP_DIR)
    message(FATAL_ERROR "LLAMA_CPP_DIR is not set (add llama.cpp.dir=... to local.properties)")
endif()

# Correctness-first configuration: only the libraries the app links are built,
# no accelerators are enabled yet (they are an optimisation, not a prerequisite).
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_TOOLS_INSTALL  OFF CACHE BOOL "" FORCE)
set(LLAMA_TESTS_INSTALL  OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS     OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI    OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/llama_cpp" EXCLUDE_FROM_ALL)

# The only JNI-visible target: a single .so exposing llama.cpp to Kotlin.
add_library(waqti_local_runtime SHARED waqti_local_runtime.cpp)
target_include_directories(waqti_local_runtime PRIVATE "${LLAMA_CPP_DIR}/include")
target_link_libraries(waqti_local_runtime PRIVATE llama android log)
