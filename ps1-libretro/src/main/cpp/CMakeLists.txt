cmake_minimum_required(VERSION 3.22.1)
project(ps1frontend CXX)

# Minimal libretro frontend for the PS1 spike. The core is dlopen()ed at runtime, so nothing
# from Beetle PSX (GPLv2) is linked in here; only the MIT licensed libretro.h is used.
add_library(ps1frontend SHARED ps1_frontend.cpp)
target_include_directories(ps1frontend PRIVATE include)
target_compile_features(ps1frontend PRIVATE cxx_std_17)
target_link_libraries(ps1frontend EGL GLESv3 aaudio android log dl)
