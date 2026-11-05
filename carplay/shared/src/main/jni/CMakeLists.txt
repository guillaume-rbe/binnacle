cmake_minimum_required(VERSION 3.22.1)
project(xcertplay_i2c C)

# CMake instead of ndk-build: ndk-build cannot build from a path that contains spaces.
add_library(xcertplay_i2c SHARED linux_i2c_jni.c)
