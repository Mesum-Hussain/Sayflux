# The app's native code. Setting this file in build.gradle replaces React Native's default
# app CMakeLists, so it builds both:
# - libappmodules.so: React Native's app library, which connects App.tsx to the Kotlin
#   module (WhistlefluxModule). Without it the setup screen can't start.
# - libwhistleflux_speech.so: a small JNI bridge (speech_jni.c) linked against Cactus's
#   prebuilt Needle engine (libneedle.a), which runs the Whistle speech model.
#   build.gradle downloads the engine and passes its folder as NEEDLE_DIR.
cmake_minimum_required(VERSION 3.22.1)
project(appmodules C CXX)

# React Native's Gradle plugin passes REACT_ANDROID_DIR.
include(${REACT_ANDROID_DIR}/cmake-utils/ReactNative-application.cmake)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Cactus only publishes the speech engine for arm64-v8a, not ${ANDROID_ABI}")
endif()

add_library(needle STATIC IMPORTED)
set_target_properties(needle PROPERTIES IMPORTED_LOCATION "${NEEDLE_DIR}/libneedle.a")

add_library(whistleflux_speech SHARED speech_jni.c)
target_include_directories(whistleflux_speech PRIVATE "${NEEDLE_DIR}")
# The engine is C++, so link with the C++ runtime.
set_target_properties(whistleflux_speech PROPERTIES LINKER_LANGUAGE CXX)
target_link_libraries(whistleflux_speech needle log m)
