# jSoapySDR

Java bindings for [SoapySDR](https://github.com/pothosware/SoapySDR), the vendor-neutral SDR hardware abstraction library, built on Java's **Foreign Function & Memory API** (FFM, [JEP 454](https://openjdk.org/jeps/454)).

There is no JNI glue and no generated code: jSoapySDR binds directly to the SoapySDR C API at runtime. The jar ships with prebuilt SoapySDR images for six platforms, including drivers for the most common receivers, so on those platforms nothing else needs to be installed.

## Requirements

| | |
|---|---|
| Java | 22 or newer (the build uses a JDK 25 toolchain and targets Java 22 bytecode) |
| Platforms with a bundled image | Linux x86_64 and aarch64 (glibc 2.28+), macOS 11+ x86_64 and Apple silicon, Windows 10+ x86_64 and ARM64 |
| Other platforms | A SoapySDR 0.8.x installation (see [Native library lookup](#native-library-lookup)) |

The image is chosen by the JVM's architecture. On Windows on Arm, use an ARM64 JDK (for example the Microsoft Build of OpenJDK) to get the native ARM64 image. An x64 JDK running under emulation also works, using the x86_64 image.

### Bundled image

Each platform image contains:

| Component | Version | Hardware |
|---|---|---|
| SoapySDR | 0.8.1 | core library |
| SoapyRTLSDR + librtlsdr | 0.3.3 + 2.0.2 | RTL2832U dongles (RTL-SDR Blog, NooElec, …) |
| SoapyAirspy + libairspy | 0.2.0 + 1.0.10 | Airspy R2, Airspy Mini |
| SoapyAirspyHF + libairspyhf | 0.2.0 + 1.6.8 | Airspy HF+ Discovery, HF+ Dual Port |
| SoapyHackRF + libhackrf | 0.3.4 + 2024.02.1 | HackRF One |

libusb is linked into each driver, so the image has no dependencies beyond the base operating system (and the Microsoft C++ runtime, which is included on Windows). The build verifies this for every platform.

Other hardware (SDRplay, LimeSDR, USRP, PlutoSDR, …) can be used alongside the bundled drivers by installing that vendor's SoapySDR 0.8 module and pointing jSoapySDR at it with `-Dsoapysdr.plugin.path=<directory>`, or by using a system SoapySDR installation instead of the bundle (`-Dsoapysdr.bundled=false`).

### USB access

The bundled drivers talk to the radio over USB, so the usual per-OS setup still applies:

- **Linux:** install udev rules so your user can open the devices (for example the `rtl-sdr`, `airspy`, `airspyhf` and `hackrf` packages' rules, or the rules files from each project), and blacklist the `dvb_usb_rtl28xxu` kernel module for RTL-SDR dongles.
- **Windows:** assign the WinUSB driver to the device with [Zadig](https://zadig.akeo.ie/) (RTL-SDR, Airspy, HackRF).
- **macOS:** no setup needed.

## Quick start

```java
import io.github.dsheirer.soapysdr.*;

for(Map<String, String> args : SoapySDR.enumerate())
{
    System.out.println(args);
}

try(Device device = SoapySDR.open("driver=rtlsdr"))
{
    device.setSampleRate(Direction.RX, 0, 2.4e6);
    device.setFrequency(Direction.RX, 0, 162.55e6);
    device.setGainMode(Direction.RX, 0, true);

    try(Stream stream = device.setupStream(Direction.RX, Formats.CF32, 0))
    {
        float[] samples = new float[stream.getMTU() * 2]; // interleaved I/Q
        stream.activate();

        for(int x = 0; x < 100; x++)
        {
            StreamResult result = stream.read(samples, 100_000);
            if(result.isSuccess())
            {
                process(samples, result.elements());
            }
            else if(result.errorCode() != ErrorCode.TIMEOUT && result.errorCode() != ErrorCode.OVERFLOW)
            {
                throw new SoapySDRException("Stream error: " + result, result.status());
            }
        }

        stream.deactivate();
    }
}
```

Run with native access enabled to avoid JVM warnings (and, in a future JDK, errors):

```
java --enable-native-access=io.github.dsheirer.soapysdr ...   # module path
java --enable-native-access=ALL-UNNAMED ...                   # class path
```

## API overview

| Class | Purpose |
|---|---|
| `SoapySDR` | Versions, `enumerate()`, `open()`, logging (`setLogHandler`, `setLogLevel`), module loading, `getNativeLibraryInfo()`, kwargs and time helpers |
| `Device` | Everything in `SoapySDRDevice_*`: channels, antennas, gain, frequency, sample rate, bandwidth, corrections, clocks, time, sensors, settings, registers, GPIO, I2C, SPI, UART |
| `Stream` | `activate`/`deactivate`, `read`/`write`, `readStatus`, `getMTU` |
| `StreamResult` | Element count or `ErrorCode`, flags, time stamp |
| `Direction`, `Formats`, `StreamFlags`, `ErrorCode`, `LogLevel` | SoapySDR constants |
| `Range`, `ArgInfo`, `NativeStreamFormat`, `NativeLibraryInfo` | SoapySDR structures and load details as Java records |

Device arguments and keyword maps are plain `Map<String, String>`. Every native allocation is released before a call returns — strings, lists and structures returned by SoapySDR are copied into Java objects and freed with the matching `SoapySDR*_clear`/`SoapySDR_free` call.

### Errors

Control calls throw `SoapySDRException` carrying the driver's message (SoapySDR's C API converts C++ driver exceptions into a status and `lastError()` string; jSoapySDR checks both after every call). Streaming calls return a `StreamResult` instead of throwing, because timeouts and overflows are routine.

### Streaming

- **Java arrays** (`read(float[])`, `read(short[])`, `read(byte[])`, …, and the matching `write` methods) for single-channel streams. The stream owns a reusable native buffer, so steady-state reads don't allocate native memory. The element count is derived from the array length and the stream format (e.g. a `float[2048]` holds 1024 `CF32` elements).
- **`MemorySegment`s** for zero-copy and multi-channel streams: pass one native segment per channel.

### Threading

- A `Device` may be controlled from one thread while another thread reads its stream. `close()` waits for an in-progress read or write to return before releasing native resources, and closing a device closes its streams.
- Read a given stream from one thread at a time.
- SoapySDR reports errors through thread-local state on the native thread. Use platform threads (not virtual threads) for device calls so the status check runs on the same OS thread as the call.
- Log handlers are invoked from native driver threads; keep them fast and thread-safe.

## Native library lookup

The native library is loaded on first use:

1. **Explicit library** — if the `soapysdr.library` system property (or `SOAPYSDR_LIBRARY` environment variable) is set to a library name or path, that SoapySDR 0.8 library is loaded and the bundled image is not used.
2. **Bundled image** (default) — the image matching the JVM's OS and architecture is extracted to a per-user cache and loaded:
   - Linux: `$XDG_CACHE_HOME/jsoapysdr/natives` or `~/.cache/jsoapysdr/natives`
   - macOS: `~/Library/Caches/jsoapysdr/natives`
   - Windows: `%LOCALAPPDATA%\jsoapysdr\natives`

   Each image is extracted once into a directory named after the hash of its manifest, with every file checked against its SHA-256. A lock file makes simultaneous first runs from several JVMs safe, and later runs reuse the extracted copy. Override the location with `-Dsoapysdr.natives.dir=<dir>`. SoapySDR's root (`SOAPY_SDR_ROOT`) is pointed at the extracted image so that its driver modules are found.
3. **System installation** — used when there is no bundled image for the platform (e.g. musl-based Linux) or when `-Dsoapysdr.bundled=false` is set: `libSoapySDR.so.0.8` / `libSoapySDR.dylib` / `SoapySDR.dll` from the OS loader path and the usual Homebrew, `/usr/local`, PothosSDR and radioconda locations.

`SoapySDR.getNativeLibraryInfo()` reports which library was loaded and from where. The platform is chosen from the JVM's architecture, so an x64 JVM running under emulation on Arm hardware uses the x86_64 image.

| System property | Effect |
|---|---|
| `soapysdr.library` | Load this SoapySDR library (name or path) instead of the bundled image |
| `soapysdr.bundled=false` | Skip the bundled image and search for a system installation |
| `soapysdr.natives.dir` | Directory to extract bundled images to |
| `soapysdr.plugin.path` | Extra directories of SoapySDR 0.8 driver modules to load alongside the bundled drivers (`:`-separated, `;` on Windows) |

## Building

```
./gradlew build
```

The Gradle toolchain resolver downloads a JDK 25 if one isn't installed. The jar includes whichever platform images are present under `natives/` (none in a fresh clone, in which case a system SoapySDR is used). Release builds use `-Pnatives.required=all`, which fails unless all six are present; CI produces such a jar as the `jsoapysdr-jar` artifact.

### Building the native images

```
python3 native/build.py                          # the current platform, into natives/<platform>
python3 native/build.py --platform linux-aarch64 # must match the machine; there is no cross compilation
```

The script fetches every component by tag and fails if a tag does not resolve to the pinned commit. It builds SoapySDR and a static libusb, compiles the vendor libraries and Soapy modules, and installs them into a relocatable layout. It then verifies that each binary depends only on the bundle and the base OS, and writes `natives/<platform>/bundle.txt`, which lists every file with its SHA-256. On Windows, vcpkg supplies libusb and pthreads-win32.

Prerequisites: git, CMake 3.24+, Python 3.8+ and a C/C++ toolchain. On Linux and macOS you also need autoconf, automake, libtool and make, and Ninja is used if present. On Windows you need Visual Studio 2022 or newer with the C++ workload. CI builds the Linux images inside the `manylinux_2_28` containers so they run on glibc 2.28 and newer.

### Tests

Unit tests run against the real SoapySDR library. Most use a small deterministic fake device, the **jsoapytest** driver module in `src/test/native`, so streaming, struct marshalling and driver error propagation are verified without hardware. `BundledImageTest` checks the extracted image and its four drivers.

```
./gradlew testDriver test -Psoapysdr.prefix=native/build/<platform>/deps
```

`-Psoapysdr.prefix` points the test driver at SoapySDR headers (here, those from the native build); omit it to use a system `libsoapysdr-dev`. Tests that need the library, the test driver or the bundled image are skipped when unavailable. `-Psoapysdr.tests.required=true` and `-Psoapysdr.tests.bundled=true` (as CI uses on all six platforms) turn those skips into failures.

### Examples

```
./gradlew probe                                        # list devices and their capabilities
./gradlew probe --args="driver=rtlsdr"
./gradlew rxPower --args="driver=rtlsdr 162.55e6 2.4e6" # stream for 3 s, print power per block
```

### Publishing

```
./gradlew publishToMavenLocal     # io.github.dsheirer:jsoapysdr:0.1.0-SNAPSHOT
```

## License

jSoapySDR is licensed under the Apache License 2.0 — see [LICENSE](LICENSE). The bundled native images contain third-party components under their own licenses, including the GPL-2.0 (librtlsdr) and the LGPL-2.1 (libusb); see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). The license texts are also packaged in the jar under `META-INF/licenses/`.
