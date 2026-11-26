cmake_minimum_required(VERSION 3.16)
project(meshnet_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(meshnet_c SHARED
    ../c/mesh_core_c.cpp
)
target_include_directories(meshnet_c PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../c
)

add_library(meshnet_c_static STATIC
    ../c/mesh_core_c.cpp
)
target_include_directories(meshnet_c_static PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../c
)

add_executable(test_core test_core.cpp)
target_include_directories(test_core PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

add_executable(bench bench.cpp)
target_include_directories(bench PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

enable_testing()
add_test(NAME conformance_and_unit COMMAND test_core ${CMAKE_CURRENT_SOURCE_DIR}/../../conformance/vectors.txt)
