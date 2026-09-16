cmake_minimum_required(VERSION 3.22.1)
project(questxr CXX)

find_package(OpenXR REQUIRED CONFIG)

add_library(questxr SHARED quest_xr.cpp controller_model.cpp)
target_compile_features(questxr PRIVATE cxx_std_17)
target_link_libraries(questxr OpenXR::headers OpenXR::openxr_loader EGL GLESv3 log android)
