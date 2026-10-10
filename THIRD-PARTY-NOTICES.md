# Third-party notices

jSoapySDR itself is licensed under the Apache License 2.0. The jSoapySDR jar also contains prebuilt native images
(`io/github/dsheirer/soapysdr/natives/<platform>/`) for Linux, macOS and Windows on x86_64 and aarch64. Those images
contain the third-party software listed below. The full license texts are included in the jar under
`META-INF/licenses/`.

The images are built by [`native/build.py`](native/build.py) from the exact sources listed here; each source is
fetched by tag and the build fails if the tag does not resolve to the listed commit. Anyone can rebuild the images,
including with modified versions of these components, by following [Building the native images](README.md#building-the-native-images).

| Component | Version | License | Source | How it is included |
|---|---|---|---|---|
| SoapySDR | 0.8.1 | BSL-1.0 | https://github.com/pothosware/SoapySDR (`1cf5a539`) | Shared library |
| SoapyRTLSDR | 0.3.3 | MIT | https://github.com/pothosware/SoapyRTLSDR (`80c93fbe`) | Driver module |
| SoapyAirspy | 0.2.0 | MIT | https://github.com/pothosware/SoapyAirspy (`411f73e1`) | Driver module |
| SoapyAirspyHF | 0.2.0 | MIT | https://github.com/pothosware/SoapyAirspyHF (`d6825331`) | Driver module |
| SoapyHackRF | 0.3.4 | MIT | https://github.com/pothosware/SoapyHackRF (`79077f04`) | Driver module |
| SoapyHydraSDR | 1.0.1 | MIT | https://github.com/hydrasdr/SoapyHydraSDR (`253ce508`) | Driver module |
| SoapyBladeRF | 0.4.2 | LGPL-2.1 | https://github.com/pothosware/SoapyBladeRF (`f214a6d2`) | Driver module |
| librtlsdr (rtl-sdr) | 2.0.2 | GPL-2.0-or-later | https://github.com/osmocom/rtl-sdr (`619ac318`) | Statically linked into the RTL-SDR module |
| libairspy (airspyone_host) | 1.0.10 | BSD-3-Clause | https://github.com/airspy/airspyone_host (`229d5a50`) | Statically linked into the Airspy module |
| libairspyhf | 1.6.8 | BSD-3-Clause | https://github.com/airspy/airspyhf (`8891387e`) | Statically linked into the Airspy HF+ module |
| libhackrf | 2024.02.1 | BSD-3-Clause | https://github.com/greatscottgadgets/hackrf (`18b485e3`) | Statically linked into the HackRF module (see patch below) |
| libhydrasdr (hydrasdr-host) | 1.1.3 | MIT and BSD-3-Clause (per file) | https://github.com/hydrasdr/hydrasdr-host (`16942cbc`) | Statically linked into the HydraSDR module |
| libbladeRF | 2.6.0 | LGPL-2.1 | https://github.com/Nuand/bladeRF (`09c82087`) | Shared library (`libbladeRF.so.2`, `libbladeRF.2.dylib`, `bladeRF.dll`) |
| AD9361 driver (Analog Devices no-OS) | as pinned by libbladeRF 2.6.0 | Analog Devices BSD-style license (see below) | https://github.com/analogdevicesinc/no-OS (`0bba46e6`), with libbladeRF's patches | Compiled into libbladeRF |
| libusb | 1.0.29 | LGPL-2.1-or-later | https://github.com/libusb/libusb (`15a7ebb4`) | Statically linked into each driver module and libbladeRF |
| pthreads4w (Windows only) | via vcpkg 2025.09.17 | Apache-2.0 | https://sourceforge.net/projects/pthreads4w/ | Statically linked into the Windows driver modules |
| Microsoft Visual C++ runtime (Windows only) | Visual Studio 2022 | Microsoft Visual C++ Redistributable license | Visual Studio | `vcruntime140*.dll`, `msvcp140*.dll` deployed app-locally |

## Modifications

- **libhackrf**: `hackrf_device_list()` returns an empty list when `hackrf_init()` failed, instead of passing a NULL
  libusb context to `libusb_get_device_list()` (which crashes the process when no USB subsystem is available, e.g.
  in containers). The change is applied at build time in [`native/CMakeLists.txt`](native/CMakeLists.txt).
- The vendor libraries other than libbladeRF are compiled from their upstream sources with a jSoapySDR CMake build
  instead of their own build systems; their sources are otherwise unmodified.
- **libbladeRF** is built with its own CMake project, with `host/utilities/CMakeLists.txt` emptied so that only the
  library is configured, and linked against the static libusb above. The AD9361 driver gets libbladeRF's own patches
  from `thirdparty/analogdevicesinc/no-OS_local/patches`, applied by libbladeRF's build as usual.
- No bladeRF FPGA images or FX3 firmware are included.

## GPL and LGPL components

The RTL-SDR driver module contains librtlsdr, which is licensed under the GPL version 2 or later, so that module as
distributed is covered by the GPL. libbladeRF and SoapyBladeRF are licensed under the LGPL 2.1; libbladeRF ships as a
separate shared library. libusb is licensed under the LGPL 2.1 and is statically linked into the driver modules and
libbladeRF; the complete corresponding source and build scripts needed to rebuild and relink them are the sources
listed above together with this repository.

## Analog Devices AD9361 driver

The AD9361 driver compiled into libbladeRF is distributed under Analog Devices' BSD-style license. In addition to the
usual BSD conditions, it states that the license does not grant patent rights and that the software, in source or
binary form, must be run on or directly connected to an Analog Devices component. The bladeRF 2.0 micro uses the
Analog Devices AD9361 transceiver. The full text is in `META-INF/licenses/ad9361-no-OS-ADI.txt`.

## Redistribution

If you redistribute the jSoapySDR jar (for example inside an application), you redistribute these components and
their license obligations apply to you as well.
