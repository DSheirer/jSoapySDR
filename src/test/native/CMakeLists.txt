# Builds the jsoapytest SoapySDR driver module used by the jSoapySDR unit tests.
#
#   cmake -S src/test/native -B build/native && cmake --build build/native
#
# Then run the tests with SOAPY_SDR_PLUGIN_PATH pointing at build/native (the Gradle build does this automatically
# when the module exists).
cmake_minimum_required(VERSION 3.10)
project(jsoapytest CXX)
find_package(SoapySDR CONFIG REQUIRED)
SOAPY_SDR_MODULE_UTIL(
    TARGET jsoapytest
    SOURCES TestDevice.cpp
)
