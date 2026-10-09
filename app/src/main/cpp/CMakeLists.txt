cmake_minimum_required(VERSION 3.22.1)
project(thakaan_native C CXX)

# lwbgt (Liljegren WBGT, Argonne licence) is staged under assets/lwbgt/src; only its two C files are built here.
set(LWBGT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../assets/lwbgt/src)

add_library(thakaan_wbgt SHARED
    thakaan_wbgt.cpp
    ${LWBGT_DIR}/src/wbgt.c
    ${LWBGT_DIR}/src/lwbgt_ffi.c)
target_include_directories(thakaan_wbgt PRIVATE ${LWBGT_DIR}/include)
target_compile_options(thakaan_wbgt PRIVATE -O2 -fno-fast-math -ffp-contract=off)
target_link_libraries(thakaan_wbgt PRIVATE m)
