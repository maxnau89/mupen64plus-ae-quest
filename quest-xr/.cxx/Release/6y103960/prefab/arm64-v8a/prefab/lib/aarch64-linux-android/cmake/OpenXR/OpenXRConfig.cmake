if(NOT TARGET OpenXR::headers)
add_library(OpenXR::headers INTERFACE IMPORTED)
set_target_properties(OpenXR::headers PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "/Volumes/MaxSSD2TB/Users/maxnau/.gradle/caches/9.5.0/transforms/662639155046c63354e12bf5a87f7f8d/transformed/jetified-openxr_loader_for_android-1.1.63/prefab/modules/headers/include"
    INTERFACE_LINK_LIBRARIES ""
)
endif()

if(NOT TARGET OpenXR::openxr_loader)
add_library(OpenXR::openxr_loader SHARED IMPORTED)
set_target_properties(OpenXR::openxr_loader PROPERTIES
    IMPORTED_LOCATION "/Volumes/MaxSSD2TB/Users/maxnau/.gradle/caches/9.5.0/transforms/662639155046c63354e12bf5a87f7f8d/transformed/jetified-openxr_loader_for_android-1.1.63/prefab/modules/openxr_loader/libs/android.arm64-v8a/libopenxr_loader.so"
    INTERFACE_LINK_LIBRARIES "OpenXR::headers"
)
endif()

