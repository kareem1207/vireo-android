cmake_minimum_required(VERSION 3.22.1)

# AGP passes -DCMAKE_BUILD_TYPE=Debug for the debug APK, which builds ggml/llama.cpp
# unoptimized with assertions on (10-30x slower on-device). Force Release for the
# whole tree — we never want an unoptimized inference engine.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)

project(vireo_llm CXX C)

# --- llama.cpp build options (set before add_subdirectory) ---
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE        OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS     OFF CACHE BOOL "" FORCE)

# third_party/llama.cpp is 4 levels up from app/src/main/cpp
add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp
    ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp
)

add_library(vireo_llm SHARED vireo_llm.cpp)

find_library(log-lib log)
target_link_libraries(vireo_llm PRIVATE llama ggml android ${log-lib})
target_compile_options(vireo_llm PRIVATE -O3)
