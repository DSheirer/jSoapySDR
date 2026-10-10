# jSoapySDR: static libraries linked against the shared CRT (/MD), release only
set(VCPKG_TARGET_ARCHITECTURE arm64)
set(VCPKG_CRT_LINKAGE dynamic)
set(VCPKG_LIBRARY_LINKAGE static)
set(VCPKG_BUILD_TYPE release)
# Keep the binaries compatible with an older msvcp140.dll that may already be loaded in the JVM
set(VCPKG_CXX_FLAGS "/D_DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR")
set(VCPKG_C_FLAGS "")
