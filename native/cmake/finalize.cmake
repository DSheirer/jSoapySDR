# Runs at install time (install(CODE ...)) after all files are in place.
#
# 1. macOS: ad-hoc signs every Mach-O file (required on Apple silicon after install_name/rpath edits)
# 2. Verifies that the core library and every driver module resolve all of their runtime dependencies either inside the
#    bundle or from the base operating system, so the bundle works on a machine with nothing else installed.
#
# Inputs: JS_STAGE (install prefix), JS_CORE (core library path relative to JS_STAGE), JS_OS (CMAKE_SYSTEM_NAME)

set(stage "${JS_STAGE}")
file(GLOB modules "${stage}/lib/SoapySDR/modules*/*")
set(core "${stage}/${JS_CORE}")
# Other bundled shared libraries (libbladeRF)
if(JS_OS STREQUAL "Windows")
    file(GLOB libraries "${stage}/bin/*.dll")
    list(FILTER libraries EXCLUDE REGEX "/(msvcp|vcruntime|concrt|vccorlib)[^/]*$")
else()
    file(GLOB libraries "${stage}/lib/*.so*" "${stage}/lib/*.dylib")
endif()
list(REMOVE_ITEM libraries "${core}")

if(JS_OS STREQUAL "Darwin")
    foreach(binary IN LISTS core libraries modules)
        execute_process(COMMAND codesign --force --sign - "${binary}" RESULT_VARIABLE rc ERROR_VARIABLE err)
        if(NOT rc EQUAL 0)
            message(FATAL_ERROR "codesign failed for ${binary}: ${err}")
        endif()
    endforeach()
endif()

if(JS_OS STREQUAL "Windows")
    set(search_dirs "${stage}/bin")
    set(allowed_system "^[A-Za-z]:[/\\\\][Ww][Ii][Nn][Dd][Oo][Ww][Ss][/\\\\]")
    set(pre_exclude "^api-ms-" "^ext-ms-")
    # Do not descend into the operating system's own DLLs
    set(post_exclude "^[A-Za-z]:[/\\\\][Ww][Ii][Nn][Dd][Oo][Ww][Ss][/\\\\]")
    # ...but these must be resolved from the bundle
    set(required_bundled soapysdr.dll bladerf.dll msvcp140.dll vcruntime140.dll)
elseif(JS_OS STREQUAL "Darwin")
    set(search_dirs "${stage}/lib")
    set(allowed_system "^/usr/lib/|^/System/Library/")
    set(pre_exclude "")
    set(post_exclude "")
    set(required_bundled "")
else()
    set(search_dirs "${stage}/lib")
    set(allowed_system "/(ld-linux[^/]*|libc|libm|libdl|libpthread|librt|libstdc\\+\\+|libgcc_s)\\.so[^/]*$")
    set(pre_exclude "^linux-vdso")
    set(post_exclude "")
    set(required_bundled "")
endif()

file(GET_RUNTIME_DEPENDENCIES
    LIBRARIES "${core}" ${libraries}
    MODULES ${modules}
    DIRECTORIES ${search_dirs}
    PRE_EXCLUDE_REGEXES ${pre_exclude}
    POST_EXCLUDE_REGEXES ${post_exclude}
    RESOLVED_DEPENDENCIES_VAR resolved
    UNRESOLVED_DEPENDENCIES_VAR unresolved
    CONFLICTING_DEPENDENCIES_PREFIX conflicts)

file(TO_CMAKE_PATH "${stage}" stage_cmake)
set(problems "")
foreach(dep IN LISTS resolved)
    file(TO_CMAKE_PATH "${dep}" dep_cmake)
    string(FIND "${dep_cmake}" "${stage_cmake}/" in_stage)
    get_filename_component(dep_name "${dep_cmake}" NAME)
    string(TOLOWER "${dep_name}" dep_lower)
    if(in_stage EQUAL 0)
        message(STATUS "  bundled dependency: ${dep_name}")
        list(REMOVE_ITEM required_bundled "${dep_lower}")
    elseif(dep_cmake MATCHES "${allowed_system}" AND NOT dep_lower MATCHES "^(msvcp|vcruntime|concrt)")
        message(STATUS "  system dependency:  ${dep_cmake}")
    else()
        list(APPEND problems "unexpected dependency ${dep_cmake}")
    endif()
endforeach()
foreach(dep IN LISTS unresolved)
    list(APPEND problems "unresolved dependency ${dep}")
endforeach()
foreach(dep IN LISTS required_bundled)
    list(APPEND problems "${dep} was not resolved from the bundle")
endforeach()

if(problems)
    list(JOIN problems "\n  " text)
    message(FATAL_ERROR "Native bundle is not self-contained:\n  ${text}")
endif()
message(STATUS "Native bundle dependencies verified (${stage})")
