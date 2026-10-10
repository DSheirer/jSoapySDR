# jSoapySDR

Java bindings for [SoapySDR](https://github.com/pothosware/SoapySDR), the vendor-neutral SDR hardware abstraction library, built on Java's **Foreign Function & Memory API** (FFM, [JEP 454](https://openjdk.org/jeps/454)).

There is no JNI glue, no generated code and no native library of its own: jSoapySDR binds directly to the SoapySDR C API (`SoapySDR/Device.h` and friends) at runtime. Any device with a SoapySDR driver module — RTL-SDR, Airspy, HackRF, SDRplay, LimeSDR, PlutoSDR, USRP, and many more — is available from Java.

## Requirements

| | |
|---|---|
| Java | 22 or newer at runtime (the build uses a JDK 25 toolchain and targets Java 22 bytecode) |
| SoapySDR | 0.8.x (API 0.8), plus the driver modules for your hardware |
| Platform | 64-bit Linux, macOS or Windows |

Installing SoapySDR:

- **Debian/Ubuntu:** `sudo apt install libsoapysdr0.8 soapysdr-module-all` (add `libsoapysdr-dev` to build the test driver)
- **macOS (Homebrew):** `brew install soapysdr` plus modules such as `soapyrtlsdr`
- **Windows:** [radioconda](https://github.com/ryanvolz/radioconda) or the PothosSDR installer

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
| `SoapySDR` | Versions, `enumerate()`, `open()`, logging (`setLogHandler`, `setLogLevel`), module loading, kwargs and time helpers |
| `Device` | Everything in `SoapySDRDevice_*`: channels, antennas, gain, frequency, sample rate, bandwidth, corrections, clocks, time, sensors, settings, registers, GPIO, I2C, SPI, UART |
| `Stream` | `activate`/`deactivate`, `read`/`write`, `readStatus`, `getMTU` |
| `StreamResult` | Element count or `ErrorCode`, flags, time stamp |
| `Direction`, `Formats`, `StreamFlags`, `ErrorCode`, `LogLevel` | SoapySDR constants |
| `Range`, `ArgInfo`, `NativeStreamFormat` | SoapySDR structures as Java records |

Device arguments and keyword maps are plain `Map<String, String>`. Every native allocation is released before a call returns — strings, lists and structures returned by SoapySDR are copied into Java objects and freed with the matching `SoapySDR*_clear`/`SoapySDR_free` call.

### Errors

Control calls throw `SoapySDRException` carrying the driver's message (SoapySDR's C API converts C++ driver exceptions into a status and `lastError()` string; jSoapySDR checks both after every call). Streaming calls return a `StreamResult` instead of throwing, because timeouts and overflows are routine.

### Streaming

- **Java arrays** (`read(float[])`, `read(short[])`, `read(byte[])`, …, and the matching `write` methods) for single-channel streams. The stream owns a reusable native buffer, so steady-state reads don't allocate native memory. The element count is derived from the array length and the stream format (e.g. a `float[2048]` holds 1024 `CF32` elements).
- **`MemorySegment`s** for zero-copy and multi-channel streams: pass one native segment per channel.

Read and write use exact-typed method handle invocation, so the per-call overhead is a downcall plus a couple of small native writes.

### Threading

- A `Device` may be controlled from one thread while another thread reads its stream. `close()` waits for an in-progress read or write to return before releasing native resources, and closing a device closes its streams.
- Read a given stream from one thread at a time.
- SoapySDR reports errors through thread-local state on the native thread. Use platform threads (not virtual threads) for device calls so the status check runs on the same OS thread as the call.
- Log handlers are invoked from native driver threads; keep them fast and thread-safe.

### Native library lookup

The library is located on first use, in this order:

1. The `soapysdr.library` system property — a library name or an absolute path, e.g. `-Dsoapysdr.library=/opt/soapy/lib/libSoapySDR.so.0.8`
2. The `SOAPYSDR_LIBRARY` environment variable, with the same meaning
3. Platform defaults: `libSoapySDR.so.0.8` / `libSoapySDR.so` and common lib directories on Linux; `libSoapySDR.0.8.dylib` in Homebrew and `/usr/local` on macOS; `SoapySDR.dll` on the `PATH`, in PothosSDR or radioconda on Windows

jSoapySDR checks that the loaded library reports API 0.8.x, since the 0.7 `setupStream` signature is not compatible. Driver modules are found by SoapySDR itself; use `SOAPY_SDR_PLUGIN_PATH` to add search directories.

## Building

```
./gradlew build
```

The Gradle toolchain resolver downloads a JDK 25 if one isn't installed.

### Tests

Unit tests run against the real SoapySDR library. Most of them use a small, deterministic fake device — the **jsoapytest** driver module in `src/test/native` — so that streaming, struct marshalling and driver error propagation are verified without hardware. Building it needs CMake, a C++ compiler and the SoapySDR headers:

```
./gradlew testDriver test
```

Tests that need the library or the test driver are skipped when they are unavailable. Pass `-Psoapysdr.tests.required=true` (as CI does) to make that a failure instead.

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

Apache License 2.0 — see [LICENSE](LICENSE). SoapySDR itself is distributed under the Boost Software License 1.0.
