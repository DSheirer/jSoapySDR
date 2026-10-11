// jSoapySDR test fixture: a fake SDRplay API library.
//
// SPDX-License-Identifier: Apache-2.0
//
// Implements the discovery subset of the SDRplay API 3.x (open, version, device list) and reports one RSP1A with
// serial "JSOAPYTEST1". The tests point the bundled SDRplay module's runtime loader at this library through the
// SOAPY_SDR_SDRPLAY_API environment variable, exercising the load-and-forward path without SDRplay's software.

#ifdef _WIN32
#include <windows.h>
#define _SDRPLAY_DLL_QUALIFIER __declspec(dllexport)
#else
#define _SDRPLAY_DLL_QUALIFIER __attribute__((visibility("default")))
#endif

#include <sdrplay_api.h>
#include <cstring>

extern "C"
{
sdrplay_api_ErrT sdrplay_api_Open(void) { return sdrplay_api_Success; }
sdrplay_api_ErrT sdrplay_api_Close(void) { return sdrplay_api_Success; }
sdrplay_api_ErrT sdrplay_api_LockDeviceApi(void) { return sdrplay_api_Success; }
sdrplay_api_ErrT sdrplay_api_UnlockDeviceApi(void) { return sdrplay_api_Success; }

sdrplay_api_ErrT sdrplay_api_ApiVersion(float *apiVer)
{
    *apiVer = SDRPLAY_API_VERSION;
    return sdrplay_api_Success;
}

sdrplay_api_ErrT sdrplay_api_GetDevices(sdrplay_api_DeviceT *devices, unsigned int *numDevs, unsigned int maxDevs)
{
    *numDevs = 0;
    if (maxDevs < 1) return sdrplay_api_Success;
    std::memset(&devices[0], 0, sizeof(sdrplay_api_DeviceT));
    std::strcpy(devices[0].SerNo, "JSOAPYTEST1");
    devices[0].hwVer = SDRPLAY_RSP1A_ID;
    devices[0].tuner = sdrplay_api_Tuner_A;
    devices[0].valid = 1;
    *numDevs = 1;
    return sdrplay_api_Success;
}

const char *sdrplay_api_GetErrorString(sdrplay_api_ErrT err) { return "fake SDRplay API"; }
}
