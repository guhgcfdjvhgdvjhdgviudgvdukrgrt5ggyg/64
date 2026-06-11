cmake_minimum_required(VERSION 3.22.1)
project("nativeguard")

add_library(nativeguard SHARED native_check.cpp)

find_library(log-lib log)
target_link_libraries(nativeguard ${log-lib})

# Required for JNI headers
find_library(jnigraphics-lib jnigraphics)
