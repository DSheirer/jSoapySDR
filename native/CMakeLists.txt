# jSoapySDR native bundle
#
# Builds the RTL-SDR, Airspy, Airspy HF+, HackRF, HydraSDR and bladeRF SoapySDR driver modules for one platform and
# installs them, along with the SoapySDR core library, into a relocatable layout:
#
#   <prefix>/lib/libSoapySDR.so.0.8 | lib/libSoapySDR.0.8.dylib | bin/SoapySDR.dll
#   <prefix>/lib/libbladeRF.so.2    | lib/libbladeRF.2.dylib    | bin/bladeRF.dll
#   <prefix>/lib/SoapySDR/modules0.8/<driver modules>
#   <prefix>/bin/<MSVC runtime DLLs>                  (Windows only)
#   <prefix>/licenses/<third party license texts>
#
# build.py first builds SoapySDR (all platforms) and a static libusb (Linux, macOS) from pinned sources into a
# dependency prefix found through CMAKE_PREFIX_PATH; on Windows libusb and pthreads-win32 come from vcpkg (vcpkg.json).
# The vendor driver libraries are compiled here as static libraries and linked into their Soapy module, so each module
# depends only on the SoapySDR core library (and, on Windows, the MSVC runtime). The exception is libbladeRF, which
# only supports a shared build: build.py builds it and passes JS_BLADERF_LIBRARY (to link), JS_BLADERF_RUNTIME (to
# install), JS_BLADERF_INCLUDE and JS_BLADERF_SOURCE.
#
# Use build.py rather than invoking this file directly.

cmake_minimum_required(VERSION 3.24)
project(jsoapysdr_natives VERSION 0.1.0 LANGUAGES C CXX)

include(FetchContent)
include(GNUInstallDirs)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

if(MSVC)
    # Shared CRT (/MD) is required because C++ objects cross the SoapySDR core/module DLL boundary
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreadedDLL")
    add_compile_definitions(_CRT_SECURE_NO_WARNINGS _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR NOMINMAX)
    add_compile_options(/W1 /utf-8)
else()
    add_compile_options(-w)
endif()

# Driver modules locate libSoapySDR in the directory two levels up (lib/SoapySDR/modules0.8 -> lib)
if(APPLE)
    set(MODULE_RPATH "@loader_path/../..")
else()
    set(MODULE_RPATH "$ORIGIN/../..")
endif()

# ---------------------------------------------------------------------------------------------------------------------
# Dependencies (SoapySDR + libusb from the build.py dependency prefix, or libusb/pthreads from vcpkg on Windows)
# ---------------------------------------------------------------------------------------------------------------------

find_package(SoapySDR CONFIG REQUIRED)
find_package(Threads REQUIRED)

# Static libusb only: from the build.py prefix (Linux, macOS) or vcpkg (Windows)
find_path(LIBUSB_INCLUDE_DIR libusb.h PATH_SUFFIXES libusb-1.0 REQUIRED)
if(WIN32)
    find_library(LIBUSB_LIBRARY NAMES libusb-1.0 usb-1.0 REQUIRED)
else()
    find_library(LIBUSB_LIBRARY NAMES libusb-1.0.a REQUIRED)
endif()
add_library(usb STATIC IMPORTED)
set_target_properties(usb PROPERTIES IMPORTED_LOCATION "${LIBUSB_LIBRARY}"
    INTERFACE_INCLUDE_DIRECTORIES "${LIBUSB_INCLUDE_DIR}")
if(APPLE)
    set_property(TARGET usb APPEND PROPERTY INTERFACE_LINK_LIBRARIES
        "-framework CoreFoundation" "-framework IOKit" "-framework Security" objc)
elseif(WIN32)
    set_property(TARGET usb APPEND PROPERTY INTERFACE_LINK_LIBRARIES advapi32 setupapi)
else()
    set_property(TARGET usb APPEND PROPERTY INTERFACE_LINK_LIBRARIES Threads::Threads)
endif()

if(WIN32)
    # vcpkg's static pthreads4w does not tell consumers it is static; without these its header declares the API
    # __declspec(dllimport)
    find_package(PThreads4W REQUIRED)
    add_library(pthreads_static INTERFACE)
    target_link_libraries(pthreads_static INTERFACE PThreads4W::PThreads4W)
    target_compile_definitions(pthreads_static INTERFACE __PTW32_STATIC_LIB PTW32_STATIC_LIB HAVE_STRUCT_TIMESPEC)
    set(PTHREADS pthreads_static)
else()
    set(PTHREADS Threads::Threads)
endif()

# ---------------------------------------------------------------------------------------------------------------------
# Pinned upstream sources: tag plus the commit it must resolve to
# ---------------------------------------------------------------------------------------------------------------------

function(fetch_source name repo tag commit)
    FetchContent_Declare(${name}
        GIT_REPOSITORY ${repo}
        GIT_TAG ${tag}
        GIT_SHALLOW TRUE
        GIT_PROGRESS FALSE
        SOURCE_SUBDIR _no_cmake_)
    FetchContent_MakeAvailable(${name})
    string(TOLOWER ${name} lname)
    set(dir "${${lname}_SOURCE_DIR}")
    find_package(Git REQUIRED)
    execute_process(COMMAND ${GIT_EXECUTABLE} -C "${dir}" rev-parse HEAD
        OUTPUT_VARIABLE actual OUTPUT_STRIP_TRAILING_WHITESPACE)
    if(NOT actual STREQUAL commit)
        message(FATAL_ERROR "${name} ${tag} resolved to ${actual}, expected ${commit}")
    endif()
    set(${name}_DIR "${dir}" PARENT_SCOPE)
endfunction()

fetch_source(rtlsdr        https://github.com/osmocom/rtl-sdr              v2.0.2                619ac3186ea0ffc092615e1f59f7397e5e6f668c)
fetch_source(airspy        https://github.com/airspy/airspyone_host        v1.0.10               229d5a5045b890621cd9cc200a19b1541c6021ae)
fetch_source(airspyhf      https://github.com/airspy/airspyhf              1.6.8                 8891387edddcd185e2949e9814e9ef35f46f0722)
fetch_source(hackrf        https://github.com/greatscottgadgets/hackrf     v2024.02.1            18b485e3b6d2031c15a79ba89cdb42b5fa245f24)
fetch_source(soapyrtlsdr   https://github.com/pothosware/SoapyRTLSDR      soapy-rtl-sdr-0.3.3   80c93fbe189def3ab68d47a6ad3f813f96d3cb99)
fetch_source(soapyairspy   https://github.com/pothosware/SoapyAirspy      soapy-airspy-0.2.0    411f73e16532cf6c0fe40209b83e7115ad43c108)
fetch_source(soapyairspyhf https://github.com/pothosware/SoapyAirspyHF    soapy-airspyhf-0.2.0  d6825331a6bfc3578dc6fe5091fce3d550b6af3c)
fetch_source(soapyhackrf   https://github.com/pothosware/SoapyHackRF      soapy-hackrf-0.3.4    79077f0425f732954c1d74dbbacd788b0ac7d733)
fetch_source(hydrasdr      https://github.com/hydrasdr/hydrasdr-host      v1.1.3                16942cbcbde47198abc6b7968c700ed0cbb8cc87)
fetch_source(soapyhydrasdr https://github.com/hydrasdr/SoapyHydraSDR      v1.0.1                253ce5089faab6fbe66c220e853b1f39f974153a)
fetch_source(soapybladerf  https://github.com/pothosware/SoapyBladeRF     soapy-bladerf-0.4.2   f214a6d28d16e20ec77bb87bc6104cce6165c836)

# ---------------------------------------------------------------------------------------------------------------------
# Vendor libraries (static)
# ---------------------------------------------------------------------------------------------------------------------

add_library(rtlsdr_static STATIC
    ${rtlsdr_DIR}/src/librtlsdr.c
    ${rtlsdr_DIR}/src/tuner_e4k.c
    ${rtlsdr_DIR}/src/tuner_fc0012.c
    ${rtlsdr_DIR}/src/tuner_fc0013.c
    ${rtlsdr_DIR}/src/tuner_fc2580.c
    ${rtlsdr_DIR}/src/tuner_r82xx.c)
target_include_directories(rtlsdr_static PUBLIC ${rtlsdr_DIR}/include)
target_compile_definitions(rtlsdr_static PUBLIC rtlsdr_STATIC)
target_link_libraries(rtlsdr_static PUBLIC usb ${PTHREADS})

add_library(airspy_static STATIC
    ${airspy_DIR}/libairspy/src/airspy.c
    ${airspy_DIR}/libairspy/src/iqconverter_float.c
    ${airspy_DIR}/libairspy/src/iqconverter_int16.c)
# The Soapy modules include the installed-header paths <libairspy/airspy.h> and <libairspyhf/airspyhf.h>
set(VENDOR_INCLUDE "${CMAKE_CURRENT_BINARY_DIR}/vendor-include")
configure_file(${airspy_DIR}/libairspy/src/airspy.h ${VENDOR_INCLUDE}/libairspy/airspy.h COPYONLY)
configure_file(${airspy_DIR}/libairspy/src/airspy_commands.h ${VENDOR_INCLUDE}/libairspy/airspy_commands.h COPYONLY)
configure_file(${airspyhf_DIR}/libairspyhf/src/airspyhf.h ${VENDOR_INCLUDE}/libairspyhf/airspyhf.h COPYONLY)
configure_file(${airspyhf_DIR}/libairspyhf/src/airspyhf_commands.h ${VENDOR_INCLUDE}/libairspyhf/airspyhf_commands.h COPYONLY)

target_include_directories(airspy_static PUBLIC ${airspy_DIR}/libairspy/src ${VENDOR_INCLUDE})
target_link_libraries(airspy_static PUBLIC usb ${PTHREADS})

add_library(airspyhf_static STATIC
    ${airspyhf_DIR}/libairspyhf/src/airspyhf.c
    ${airspyhf_DIR}/libairspyhf/src/iqbalancer.c)
target_include_directories(airspyhf_static PUBLIC ${airspyhf_DIR}/libairspyhf/src ${VENDOR_INCLUDE})
target_link_libraries(airspyhf_static PUBLIC usb ${PTHREADS})
if(NOT MSVC)
    target_link_libraries(airspyhf_static PUBLIC m)
endif()

# Patch: libhackrf's hackrf_device_list() passes a NULL libusb context to libusb_get_device_list() when hackrf_init()
# failed (e.g. no USB subsystem in a container), which crashes the process during SoapySDR enumeration. Return an empty
# list instead.
file(READ ${hackrf_DIR}/host/libhackrf/src/hackrf.c HACKRF_C)
set(HACKRF_ORIGINAL "\tlist->usb_devicecount = (int) libusb_get_device_list(\n\t\tg_libusb_context,\n\t\t(libusb_device***) &list->usb_devices);\n")
set(HACKRF_PATCHED "\tif (g_libusb_context == NULL) {\n\t\treturn list; /* jSoapySDR: hackrf_init() failed */\n\t}\n${HACKRF_ORIGINAL}\tif (list->usb_devicecount < 0) {\n\t\tlist->usb_devicecount = 0;\n\t\tlist->usb_devices = NULL;\n\t\treturn list;\n\t}\n")
string(FIND "${HACKRF_C}" "${HACKRF_ORIGINAL}" HACKRF_POS)
if(HACKRF_POS EQUAL -1)
    message(FATAL_ERROR "libhackrf patch no longer applies to hackrf.c")
endif()
string(REPLACE "${HACKRF_ORIGINAL}" "${HACKRF_PATCHED}" HACKRF_C "${HACKRF_C}")
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/patched/hackrf.c "${HACKRF_C}")

add_library(hackrf_static STATIC ${CMAKE_CURRENT_BINARY_DIR}/patched/hackrf.c)
target_include_directories(hackrf_static PUBLIC ${hackrf_DIR}/host/libhackrf/src)
target_compile_definitions(hackrf_static PRIVATE LIBRARY_VERSION="2024.02.1" LIBRARY_RELEASE="2024.02.1")
target_link_libraries(hackrf_static PUBLIC usb ${PTHREADS})

set(HYDRASDR_SRC ${hydrasdr_DIR}/libhydrasdr/src)
add_library(hydrasdr_static STATIC
    ${HYDRASDR_SRC}/hydrasdr.c
    ${HYDRASDR_SRC}/hydrasdr_shared.c
    ${HYDRASDR_SRC}/hydrasdr_rfone.c
    ${HYDRASDR_SRC}/iqconverter.c
    ${HYDRASDR_SRC}/iqconverter_lut.c
    ${HYDRASDR_SRC}/iqconverter_float.c
    ${HYDRASDR_SRC}/iqconverter_int16.c
    ${HYDRASDR_SRC}/iqconverter_float_opt.c
    ${HYDRASDR_SRC}/iqconverter_int16_opt.c
    ${HYDRASDR_SRC}/iqconverter_float_opt33.c
    ${HYDRASDR_SRC}/iqconverter_int16_opt33.c
    ${HYDRASDR_SRC}/iqconverter_float_opt65.c
    ${HYDRASDR_SRC}/iqconverter_int16_opt65.c
    ${HYDRASDR_SRC}/iqconverter_float_opt83.c
    ${HYDRASDR_SRC}/iqconverter_int16_opt83.c
    ${HYDRASDR_SRC}/iqconverter_decimator.c
    ${HYDRASDR_SRC}/iqconverter_float_opt_dec.c
    ${HYDRASDR_SRC}/iqconverter_int16_opt_dec.c)
configure_file(${HYDRASDR_SRC}/hydrasdr.h ${VENDOR_INCLUDE}/libhydrasdr/hydrasdr.h COPYONLY)
configure_file(${HYDRASDR_SRC}/hydrasdr_commands.h ${VENDOR_INCLUDE}/libhydrasdr/hydrasdr_commands.h COPYONLY)
target_include_directories(hydrasdr_static PUBLIC ${HYDRASDR_SRC} ${VENDOR_INCLUDE})
# ADD_EXPORTS: the header otherwise declares the API dllimport on Windows
target_compile_definitions(hydrasdr_static PUBLIC ADD_EXPORTS)
if(MSVC)
    target_include_directories(hydrasdr_static PRIVATE ${HYDRASDR_SRC}/win32)
    target_compile_options(hydrasdr_static PRIVATE /fp:fast)
else()
    set_target_properties(hydrasdr_static PROPERTIES C_STANDARD 11 C_EXTENSIONS ON)
    target_compile_options(hydrasdr_static PRIVATE -fno-math-errno -fno-trapping-math)
endif()
target_link_libraries(hydrasdr_static PUBLIC usb ${PTHREADS})
if(NOT MSVC)
    target_link_libraries(hydrasdr_static PUBLIC m)
endif()

# libbladeRF (shared, built by build.py)
add_library(bladerf SHARED IMPORTED)
target_include_directories(bladerf INTERFACE "${JS_BLADERF_INCLUDE}")
if(WIN32)
    set_target_properties(bladerf PROPERTIES IMPORTED_IMPLIB "${JS_BLADERF_LIBRARY}"
        IMPORTED_LOCATION "${JS_BLADERF_RUNTIME}")
else()
    set_target_properties(bladerf PROPERTIES IMPORTED_LOCATION "${JS_BLADERF_LIBRARY}")
endif()

# The airspy/airspyhf/hackrf headers declare their API dllexport on Windows; with static linking that only means the
# functions are also exported from the module DLL, which is harmless.

# ---------------------------------------------------------------------------------------------------------------------
# Soapy driver modules
# ---------------------------------------------------------------------------------------------------------------------

set(MODULE_DEST "lib/SoapySDR/modules${SOAPY_SDR_ABI_VERSION}")

function(soapy_module target version dir)
    # SOAPY_SDR_MODULE_UTIL writes one Version.cpp per binary directory, so with several modules in this directory
    # they would all report the last module's version. Suppress it (no VERSION/PROJECT_VERSION) and generate our own.
    set(PROJECT_VERSION "")
    set(version_file "${CMAKE_CURRENT_BINARY_DIR}/${target}_Version.cpp")
    file(WRITE "${version_file}" "#include <SoapySDR/Modules.hpp>\nstatic const SoapySDR::ModuleVersion register${target}Version(\"${version}\");\n")
    SOAPY_SDR_MODULE_UTIL(
        TARGET ${target}
        DESTINATION ${MODULE_DEST}
        SOURCES ${ARGN} "${version_file}")
    target_include_directories(${target} PRIVATE ${dir})
    set_target_properties(${target} PROPERTIES INSTALL_RPATH "${MODULE_RPATH}" BUILD_WITH_INSTALL_RPATH OFF)
endfunction()

soapy_module(rtlsdrSupport 0.3.3 ${soapyrtlsdr_DIR}
    ${soapyrtlsdr_DIR}/Registration.cpp ${soapyrtlsdr_DIR}/Settings.cpp ${soapyrtlsdr_DIR}/Streaming.cpp)
target_compile_definitions(rtlsdrSupport PRIVATE HAS_RTLSDR_SET_BIAS_TEE)
target_link_libraries(rtlsdrSupport PRIVATE rtlsdr_static)

soapy_module(airspySupport 0.2.0 ${soapyairspy_DIR}
    ${soapyairspy_DIR}/Registration.cpp ${soapyairspy_DIR}/Settings.cpp ${soapyairspy_DIR}/Streaming.cpp)
target_link_libraries(airspySupport PRIVATE airspy_static)

soapy_module(airspyhfSupport 0.2.0 ${soapyairspyhf_DIR}
    ${soapyairspyhf_DIR}/Registration.cpp ${soapyairspyhf_DIR}/Settings.cpp ${soapyairspyhf_DIR}/Streaming.cpp)
target_link_libraries(airspyhfSupport PRIVATE airspyhf_static)

soapy_module(HackRFSupport 0.3.4 ${soapyhackrf_DIR}
    ${soapyhackrf_DIR}/HackRF_Registration.cpp ${soapyhackrf_DIR}/HackRF_Settings.cpp
    ${soapyhackrf_DIR}/HackRF_Streaming.cpp ${soapyhackrf_DIR}/HackRF_Session.cpp)
target_link_libraries(HackRFSupport PRIVATE hackrf_static)

soapy_module(SoapyHydraSDR 1.0.1 ${soapyhydrasdr_DIR}
    ${soapyhydrasdr_DIR}/Registration.cpp ${soapyhydrasdr_DIR}/Settings.cpp ${soapyhydrasdr_DIR}/Streaming.cpp)
target_compile_definitions(SoapyHydraSDR PRIVATE SOAPYHYDRASDR_VERSION="1.0.1")
target_link_libraries(SoapyHydraSDR PRIVATE hydrasdr_static)

soapy_module(bladeRFSupport 0.4.2 ${soapybladerf_DIR}
    ${soapybladerf_DIR}/bladeRF_Registration.cpp ${soapybladerf_DIR}/bladeRF_Settings.cpp
    ${soapybladerf_DIR}/bladeRF_Streaming.cpp)
target_link_libraries(bladeRFSupport PRIVATE bladerf)

# ---------------------------------------------------------------------------------------------------------------------
# SoapySDR core library and runtime files
# ---------------------------------------------------------------------------------------------------------------------

get_target_property(SOAPY_LOCATION SoapySDR IMPORTED_LOCATION_RELEASE)
if(NOT SOAPY_LOCATION)
    get_target_property(SOAPY_LOCATION SoapySDR IMPORTED_LOCATION)
endif()

if(WIN32)
    install(FILES "${SOAPY_LOCATION}" "${JS_BLADERF_RUNTIME}" DESTINATION bin)

    # MSVC runtime DLLs, deployed app-locally. Located relative to the compiler
    # (<VS>/VC/Tools/MSVC/<ver>/bin/Host<arch>/<arch>/cl.exe -> <VS>/VC/Redist/MSVC/<ver>/<arch>/Microsoft.VC*.CRT)
    # because InstallRequiredSystemLibraries does not handle every Visual Studio release.
    get_filename_component(JS_VC_DIR "${CMAKE_CXX_COMPILER}" DIRECTORY)
    foreach(i RANGE 5)
        get_filename_component(JS_VC_DIR "${JS_VC_DIR}" DIRECTORY)
    endforeach()
    if(CMAKE_GENERATOR_PLATFORM MATCHES "^[Aa][Rr][Mm]64$" OR CMAKE_SYSTEM_PROCESSOR MATCHES "^(ARM64|arm64|aarch64)$")
        set(JS_CRT_ARCH arm64)
    else()
        set(JS_CRT_ARCH x64)
    endif()
    file(GLOB JS_CRT_DIRS LIST_DIRECTORIES true "${JS_VC_DIR}/Redist/MSVC/*/${JS_CRT_ARCH}/Microsoft.VC*.CRT")
    if(NOT JS_CRT_DIRS)
        message(FATAL_ERROR "MSVC runtime redistributables not found under ${JS_VC_DIR}/Redist/MSVC")
    endif()
    list(SORT JS_CRT_DIRS COMPARE NATURAL ORDER DESCENDING)
    list(GET JS_CRT_DIRS 0 JS_CRT_DIR)
    message(STATUS "MSVC runtime: ${JS_CRT_DIR}")
    file(GLOB JS_CRT_DLLS "${JS_CRT_DIR}/*.dll")
    install(FILES ${JS_CRT_DLLS} DESTINATION bin)
else()
    # Install the real file under its SONAME / install name so the modules' dependency resolves
    file(REAL_PATH "${SOAPY_LOCATION}" SOAPY_REAL)
    get_filename_component(SOAPY_DIR "${SOAPY_LOCATION}" DIRECTORY)
    if(APPLE)
        set(SOAPY_NAME "libSoapySDR.${SOAPY_SDR_ABI_VERSION}.dylib")
    else()
        set(SOAPY_NAME "libSoapySDR.so.${SOAPY_SDR_ABI_VERSION}")
    endif()
    install(FILES "${SOAPY_REAL}" DESTINATION lib RENAME "${SOAPY_NAME}")
    # Copied by build.py under its SONAME / install name file name
    install(FILES "${JS_BLADERF_RUNTIME}" DESTINATION lib)
endif()

# ---------------------------------------------------------------------------------------------------------------------
# Licenses
# ---------------------------------------------------------------------------------------------------------------------

# JS_LICENSE_SOAPYSDR and (except on Windows) JS_LICENSE_LIBUSB are passed in by build.py
file(TO_CMAKE_PATH "${JS_LICENSE_SOAPYSDR}" JS_LICENSE_SOAPYSDR)
file(TO_CMAKE_PATH "${JS_LICENSE_LIBUSB}" JS_LICENSE_LIBUSB)
if(WIN32 AND NOT JS_LICENSE_LIBUSB)
    set(JS_LICENSE_LIBUSB "${_VCPKG_INSTALLED_DIR}/${VCPKG_TARGET_TRIPLET}/share/libusb/copyright")
endif()
install(FILES "${JS_LICENSE_SOAPYSDR}" DESTINATION licenses RENAME SoapySDR-BSL-1.0.txt)
install(FILES "${JS_LICENSE_LIBUSB}" DESTINATION licenses RENAME libusb-LGPL-2.1.txt)
if(WIN32)
    install(FILES "${_VCPKG_INSTALLED_DIR}/${VCPKG_TARGET_TRIPLET}/share/pthreads/copyright"
        DESTINATION licenses RENAME pthreads4w-Apache-2.0.txt)
endif()
install(FILES "${rtlsdr_DIR}/COPYING" DESTINATION licenses RENAME rtl-sdr-GPL-2.0.txt)
install(FILES "${airspyhf_DIR}/LICENSE" DESTINATION licenses RENAME airspyhf-BSD-3-Clause.txt)

# libairspy and libhackrf carry their BSD-3-Clause license in the source file header
function(license_from_header source output)
    file(READ "${source}" text LIMIT 4096)
    string(REGEX MATCH "/\\*[^*]*(\\*[^/][^*]*)*\\*/" header "${text}")
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/licenses/${output}" "${header}\n")
    install(FILES "${CMAKE_CURRENT_BINARY_DIR}/licenses/${output}" DESTINATION licenses)
endfunction()
license_from_header("${airspy_DIR}/libairspy/src/airspy.c" libairspy-BSD-3-Clause.txt)
license_from_header("${hackrf_DIR}/host/libhackrf/src/hackrf.c" libhackrf-BSD-3-Clause.txt)
license_from_header("${JS_BLADERF_SOURCE}/thirdparty/analogdevicesinc/no-OS/ad9361/sw/ad9361.c" ad9361-no-OS-ADI.txt)
install(FILES "${hydrasdr_DIR}/libhydrasdr/LICENSE.md" DESTINATION licenses RENAME libhydrasdr-MIT-BSD-3-Clause.md)
install(FILES "${soapyhydrasdr_DIR}/LICENSE.txt" DESTINATION licenses RENAME SoapyHydraSDR-MIT.txt)
install(FILES "${JS_BLADERF_SOURCE}/legal/licenses/LICENSE.LGPLv2.1" DESTINATION licenses RENAME libbladeRF-LGPL-2.1.txt)
install(FILES "${soapybladerf_DIR}/LICENSE.LGPLv2.1" DESTINATION licenses RENAME SoapyBladeRF-LGPL-2.1.txt)
install(FILES "${soapyrtlsdr_DIR}/LICENSE.txt" DESTINATION licenses RENAME SoapyRTLSDR-MIT.txt)
install(FILES "${soapyairspy_DIR}/LICENSE.txt" DESTINATION licenses RENAME SoapyAirspy-MIT.txt)
install(FILES "${soapyairspyhf_DIR}/LICENSE.txt" DESTINATION licenses RENAME SoapyAirspyHF-MIT.txt)
install(FILES "${soapyhackrf_DIR}/LICENSE" DESTINATION licenses RENAME SoapyHackRF-MIT.txt)

# ---------------------------------------------------------------------------------------------------------------------
# Sign (macOS) and verify the installed bundle is self-contained
# ---------------------------------------------------------------------------------------------------------------------

if(WIN32)
    set(JS_CORE "bin/SoapySDR.dll")
else()
    set(JS_CORE "lib/${SOAPY_NAME}")
endif()
# Tools used by file(GET_RUNTIME_DEPENDENCIES); dumpbin is not on PATH with the Visual Studio generators
set(JS_DEPENDENCY_TOOL_CODE "")
if(MSVC)
    get_filename_component(JS_LINKER_DIR "${CMAKE_LINKER}" DIRECTORY)
    find_program(JS_DUMPBIN dumpbin HINTS "${JS_LINKER_DIR}" REQUIRED)
    set(JS_DEPENDENCY_TOOL_CODE "set(CMAKE_GET_RUNTIME_DEPENDENCIES_PLATFORM windows+pe)
    set(CMAKE_GET_RUNTIME_DEPENDENCIES_TOOL dumpbin)
    set(CMAKE_GET_RUNTIME_DEPENDENCIES_COMMAND \"${JS_DUMPBIN}\")")
elseif(APPLE)
    find_program(JS_OTOOL otool REQUIRED)
    set(JS_DEPENDENCY_TOOL_CODE "set(CMAKE_GET_RUNTIME_DEPENDENCIES_PLATFORM macos+macho)
    set(CMAKE_GET_RUNTIME_DEPENDENCIES_TOOL otool)
    set(CMAKE_GET_RUNTIME_DEPENDENCIES_COMMAND \"${JS_OTOOL}\")")
endif()

install(CODE "
    if(POLICY CMP0207)
        cmake_policy(SET CMP0207 NEW)
    endif()
    ${JS_DEPENDENCY_TOOL_CODE}
    set(JS_STAGE \"\${CMAKE_INSTALL_PREFIX}\")
    set(JS_CORE \"${JS_CORE}\")
    set(JS_OS \"${CMAKE_SYSTEM_NAME}\")
    include(\"${CMAKE_CURRENT_SOURCE_DIR}/cmake/finalize.cmake\")
")

