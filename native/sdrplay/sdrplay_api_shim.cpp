// jSoapySDR - SDRplay API runtime loader
//
// SPDX-License-Identifier: Apache-2.0
//
// The SDRplay API is proprietary and installed separately by the user (with its sdrplay_apiService). Rather than
// link the SoapySDRPlay3 module against it - which would make the module fail to load wherever the API is not
// installed - this shim defines the sdrplay_api_* functions the module uses and forwards each call to the installed
// library, which is located and loaded on first use. When the library cannot be found, every call fails with
// sdrplay_api_Fail and jsoapysdr_sdrplay_api_available() returns false, so device discovery quietly finds nothing.
//
// Library search order:
//   1. The SOAPY_SDR_SDRPLAY_API environment variable (set by jSoapySDR from -Dsoapysdr.sdrplay.library)
//   2. Windows: <Install_Dir>\<arch>\sdrplay_api.dll from HKLM\SOFTWARE\SDRplay\Service\API, the default install
//      directory, then the DLL search path
//      Linux/macOS: libsdrplay_api.so.3 from the loader path, /usr/local/lib and /usr/lib

#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif

#include <sdrplay_api.h>
#include <SoapySDR/Logger.h>

#include <cstdlib>
#include <mutex>
#include <string>
#include <vector>

namespace
{
#ifdef _WIN32
using LibraryHandle = HMODULE;
#else
using LibraryHandle = void *;
#endif

std::once_flag gLoadOnce;
LibraryHandle gLibrary = nullptr;
std::string gLibraryPath;

std::vector<std::string> candidates()
{
    std::vector<std::string> paths;
    const char *configured = std::getenv("SOAPY_SDR_SDRPLAY_API");
    if (configured != nullptr && configured[0] != '\0')
    {
        paths.push_back(configured);
    }

#ifdef _WIN32
#if defined(_M_ARM64)
    const std::string arch = "arm64";
#else
    const std::string arch = "x64";
#endif
    char installDir[MAX_PATH] = {0};
    DWORD size = sizeof(installDir);
    if (RegGetValueA(HKEY_LOCAL_MACHINE, "SOFTWARE\\SDRplay\\Service\\API", "Install_Dir",
                     RRF_RT_REG_SZ | RRF_SUBKEY_WOW6464KEY, nullptr, installDir, &size) == ERROR_SUCCESS)
    {
        paths.push_back(std::string(installDir) + "\\" + arch + "\\sdrplay_api.dll");
    }
    paths.push_back("C:\\Program Files\\SDRplay\\API\\" + arch + "\\sdrplay_api.dll");
    paths.push_back("sdrplay_api.dll");
#else
    paths.push_back("libsdrplay_api.so.3");
    paths.push_back("/usr/local/lib/libsdrplay_api.so.3");
    paths.push_back("/usr/lib/libsdrplay_api.so.3");
    paths.push_back("libsdrplay_api.so");
#endif
    return paths;
}

LibraryHandle open(const std::string &path)
{
#ifdef _WIN32
    // LOAD_WITH_ALTERED_SEARCH_PATH (find the DLL's own dependencies next to it) requires backslashes
    std::string native = path;
    for (char &c : native)
    {
        if (c == '/') c = '\\';
    }
    const bool absolute = native.find('\\') != std::string::npos;
    return LoadLibraryExA(native.c_str(), nullptr, absolute ? LOAD_WITH_ALTERED_SEARCH_PATH : 0);
#else
    return dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
#endif
}

void load()
{
    for (const std::string &path : candidates())
    {
        gLibrary = open(path);
        if (gLibrary != nullptr)
        {
            gLibraryPath = path;
            SoapySDR_logf(SOAPY_SDR_DEBUG, "SDRplay API library loaded from %s", path.c_str());
            return;
        }
    }
    SoapySDR_log(SOAPY_SDR_DEBUG, "SDRplay API library not found; install the SDRplay API from sdrplay.com to use "
                                  "SDRplay devices");
}

void *symbol(const char *name)
{
    std::call_once(gLoadOnce, load);
    if (gLibrary == nullptr)
    {
        return nullptr;
    }
#ifdef _WIN32
    return reinterpret_cast<void *>(GetProcAddress(gLibrary, name));
#else
    return dlsym(gLibrary, name);
#endif
}

template <typename Function>
Function resolve(const char *name)
{
    return reinterpret_cast<Function>(symbol(name));
}
} // namespace

// Indicates if the installed SDRplay API library was found and loaded (checked by the patched findSDRPlay()).
extern "C" bool jsoapysdr_sdrplay_api_available(void)
{
    std::call_once(gLoadOnce, load);
    return gLibrary != nullptr;
}

#define JS_FORWARD(name, params, args)                                                         \
    sdrplay_api_ErrT name params                                                               \
    {                                                                                          \
        using Function = sdrplay_api_ErrT (*) params;                                          \
        static const Function function = resolve<Function>(#name);                             \
        return function != nullptr ? function args : sdrplay_api_Fail;                         \
    }

extern "C"
{
JS_FORWARD(sdrplay_api_Open, (void), ())
JS_FORWARD(sdrplay_api_Close, (void), ())
JS_FORWARD(sdrplay_api_ApiVersion, (float *apiVer), (apiVer))
JS_FORWARD(sdrplay_api_LockDeviceApi, (void), ())
JS_FORWARD(sdrplay_api_UnlockDeviceApi, (void), ())
JS_FORWARD(sdrplay_api_GetDevices, (sdrplay_api_DeviceT *devices, unsigned int *numDevs, unsigned int maxDevs),
           (devices, numDevs, maxDevs))
JS_FORWARD(sdrplay_api_SelectDevice, (sdrplay_api_DeviceT *device), (device))
JS_FORWARD(sdrplay_api_ReleaseDevice, (sdrplay_api_DeviceT *device), (device))
JS_FORWARD(sdrplay_api_DebugEnable, (HANDLE dev, sdrplay_api_DbgLvl_t enable), (dev, enable))
JS_FORWARD(sdrplay_api_GetDeviceParams, (HANDLE dev, sdrplay_api_DeviceParamsT **deviceParams), (dev, deviceParams))
JS_FORWARD(sdrplay_api_Init, (HANDLE dev, sdrplay_api_CallbackFnsT *callbackFns, void *cbContext),
           (dev, callbackFns, cbContext))
JS_FORWARD(sdrplay_api_Uninit, (HANDLE dev), (dev))
JS_FORWARD(sdrplay_api_Update,
           (HANDLE dev, sdrplay_api_TunerSelectT tuner, sdrplay_api_ReasonForUpdateT reasonForUpdate,
            sdrplay_api_ReasonForUpdateExtension1T reasonForUpdateExt1),
           (dev, tuner, reasonForUpdate, reasonForUpdateExt1))
JS_FORWARD(sdrplay_api_SwapRspDuoActiveTuner,
           (HANDLE dev, sdrplay_api_TunerSelectT *currentTuner, sdrplay_api_RspDuo_AmPortSelectT tuner1AmPortSel),
           (dev, currentTuner, tuner1AmPortSel))

const char *sdrplay_api_GetErrorString(sdrplay_api_ErrT err)
{
    using Function = const char *(*)(sdrplay_api_ErrT);
    static const Function function = resolve<Function>("sdrplay_api_GetErrorString");
    if (function != nullptr)
    {
        return function(err);
    }
    return "SDRplay API library not installed";
}
}
