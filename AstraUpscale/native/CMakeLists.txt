cmake_minimum_required(VERSION 3.16)
project(astraupscale)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fvisibility=hidden")

# ncnn'in onceden derlenmis Android paketi (Vulkan destekli)
set(ncnn_DIR "${NCNN_PREBUILT}/${ANDROID_ABI}/lib/cmake/ncnn")
find_package(ncnn REQUIRED)

find_library(log-lib log)
find_library(android-lib android)

add_library(astraupscale SHARED sr_engine.cpp jni_bridge.cpp)
target_include_directories(astraupscale PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
# Android 15+ cihazlardaki 16 KB sayfa boyutu icin hizalama
target_link_options(astraupscale PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
target_link_libraries(astraupscale ncnn ${log-lib} ${android-lib})
