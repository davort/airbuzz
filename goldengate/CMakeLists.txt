# Builds Golden Gate for Android from the third_party/golden-gate submodule:
#   libgg.so - the C core, configured with upstream's Android profile
#   libxp.so - upstream's JNI bindings (platform/android/.../GoldenGateBindings/src/main/cpp)
cmake_minimum_required(VERSION 3.18)
project(airbuzz_goldengate C CXX)

get_filename_component(GG_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/golden-gate ABSOLUTE)
# Align LOAD segments to 16 KB so libgg and libxp load on 16 KB page-size phones (Android 15+).
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
include(${GG_ROOT}/xp/config/profiles/android/android.cmake)
add_subdirectory(${GG_ROOT}/xp ${CMAKE_BINARY_DIR}/gg)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=gnu++11")
add_library(xp SHARED "")
set(gg_DIR ${GG_ROOT})
set(libs_to_link gg android log)
include(${GG_ROOT}/platform/android/goldengate/GoldenGateBindings/src/main/cpp/CMakeLists.txt)
target_include_directories(xp PRIVATE ${GG_ROOT} ${GG_ROOT}/external/smo/c/lib/inc ${CMAKE_BINARY_DIR})
