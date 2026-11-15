# Copied from arcboxlabs/expo-libghostty (MIT) android/CMakeLists.txt at f53920f; library name and
# libghostty-vt paths adapted. See THIRD_PARTY_NOTICES.md.
cmake_minimum_required(VERSION 3.22.1)
project(pohunekterminal CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# libghostty-vt staged by scripts/build-libghostty-vt (output_dir of config/libghostty-vt.toml).
set(GHOSTTY_VT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../vendor/libghostty-vt)
set(GHOSTTY_VT_LIB ${GHOSTTY_VT_DIR}/${ANDROID_ABI}/libghostty-vt.a)
if(NOT EXISTS ${GHOSTTY_VT_LIB})
  message(FATAL_ERROR "Missing ${GHOSTTY_VT_LIB}: run scripts/build-libghostty-vt first")
endif()

add_library(ghostty-vt STATIC IMPORTED)
set_target_properties(ghostty-vt PROPERTIES IMPORTED_LOCATION ${GHOSTTY_VT_LIB})

add_library(pohunekterminal SHARED src/main/cpp/ghostty_jni.cpp)

target_include_directories(pohunekterminal PRIVATE ${GHOSTTY_VT_DIR}/include)
# Static linking: keep GHOSTTY_API a no-op (see ghostty/vt/types.h).
target_compile_definitions(pohunekterminal PRIVATE GHOSTTY_STATIC)
# Neither the checkout path nor the CMake build directory may reach the object file, so a rebuild
# from any checkout location produces the same library.
target_compile_options(pohunekterminal PRIVATE -Wall -fvisibility=hidden
  -ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.
  -ffile-prefix-map=${CMAKE_CURRENT_BINARY_DIR}=.)
# Android 15+ devices require 16 KB page alignment.
target_link_options(pohunekterminal PRIVATE -Wl,-z,max-page-size=16384)
# Omit the GNU build-id note so the library carries no content-derived identifier.
target_link_options(pohunekterminal PRIVATE -Wl,--build-id=none)

if(ANDROID_ABI STREQUAL "x86_64")
  # Zig emits general-dynamic TLS on x86_64, which needs __tls_get_addr;
  # bionic only exports it from API 29. Let it stay undefined at link time
  # (overrides AGP's --no-undefined): x86_64 is an emulator-only ABI and
  # API 29+ emulators resolve it from libc.so at load.
  target_link_options(pohunekterminal PRIVATE -Wl,-z,undefs)
endif()

target_link_libraries(pohunekterminal ghostty-vt log)
