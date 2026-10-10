# Builds the jsoapytest SoapySDR driver module used by the jSoapySDR unit tests.
#
#   cmake -S src/test/native -B build/native -DCMAKE_PREFIX_PATH=<SoapySDR prefix> && cmake --build build/native
#
# The module is written to build/native/modules; the Gradle test task adds that directory to SOAPY_SDR_PLUGIN_PATH.
# It must bind to the SoapySDR core that the JVM has already loaded (normally the bundled image), not to the copy it
# was compiled against:
#   - Linux: DT_NEEDED libSoapySDR.so.0.8 matches the loaded library by SONAME
#   - Windows: the SoapySDR.dll import matches the loaded module by name
#   - macOS: the module is linked with -undefined dynamic_lookup so symbols resolve from the loaded core
cmake_minimum_required(VERSION 3.16)
project(jsoapytest CXX)

set(CMAKE_CXX_STANDARD 14)
if(MSVC)
    add_compile_definitions(_DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR)
endif()

find_package(SoapySDR CONFIG REQUIRED)

add_library(jsoapytest MODULE TestDevice.cpp)
set(output "${CMAKE_BINARY_DIR}/modules")
set_target_properties(jsoapytest PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${output}"
    LIBRARY_OUTPUT_DIRECTORY_RELEASE "${output}"
    LIBRARY_OUTPUT_DIRECTORY_DEBUG "${output}"
    RUNTIME_OUTPUT_DIRECTORY "${output}"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${output}"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${output}"
    CXX_VISIBILITY_PRESET hidden)

if(APPLE)
    get_target_property(soapy_includes SoapySDR INTERFACE_INCLUDE_DIRECTORIES)
    target_include_directories(jsoapytest PRIVATE ${soapy_includes})
    target_link_options(jsoapytest PRIVATE -undefined dynamic_lookup)
else()
    target_link_libraries(jsoapytest PRIVATE SoapySDR)
endif()
