cmake_minimum_required(VERSION 3.22)
project(spcore LANGUAGES CXX)
add_library(spcore SHARED native-lib.cpp)
target_compile_options(spcore PRIVATE -O2 -fvisibility=hidden -fno-exceptions -fno-rtti)