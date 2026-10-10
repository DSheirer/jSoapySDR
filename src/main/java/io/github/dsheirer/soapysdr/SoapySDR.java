/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

import io.github.dsheirer.soapysdr.internal.SoapyNative;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;

import static io.github.dsheirer.soapysdr.internal.SoapyNative.C_SIZE_T;
import static io.github.dsheirer.soapysdr.internal.SoapyNative.call;
import static io.github.dsheirer.soapysdr.internal.SoapyNative.toCString;
import static io.github.dsheirer.soapysdr.internal.SoapyNative.toJavaString;
import static io.github.dsheirer.soapysdr.internal.SoapyNative.toJavaStringAndFree;

/**
 * Entry point for the SoapySDR library: version information, device discovery, logging and module management.
 *
 * <pre>{@code
 * for(Map<String, String> args : SoapySDR.enumerate())
 * {
 *     System.out.println(args);
 * }
 *
 * try(Device device = SoapySDR.open("driver=rtlsdr"))
 * {
 *     device.setSampleRate(Direction.RX, 0, 2.4e6);
 *     device.setFrequency(Direction.RX, 0, 162.55e6);
 * }
 * }</pre>
 *
 * <p>The native library is loaded on first use. See the README for how the library is located.</p>
 */
public final class SoapySDR
{
    /** Minimum supported SoapySDR API version (major.minor). */
    public static final String REQUIRED_API = "0.8";

    private static volatile LogHandler sLogHandler;
    private static volatile MemorySegment sLogStub;

    static
    {
        String api = toJavaString((MemorySegment)call(SoapyNative.getAPIVersion));
        if(!api.startsWith(REQUIRED_API + ".") && !api.equals(REQUIRED_API))
        {
            throw new UnsatisfiedLinkError("jSoapySDR requires SoapySDR API " + REQUIRED_API + ".x but the loaded " +
                "library reports API version " + api);
        }
    }

    private SoapySDR()
    {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Version
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * SoapySDR API version string, e.g. "0.8.0".
     */
    public static String getAPIVersion()
    {
        return toJavaString((MemorySegment)call(SoapyNative.getAPIVersion));
    }

    /**
     * SoapySDR ABI version string, e.g. "0.8-3". Driver modules must be built against the same ABI.
     */
    public static String getABIVersion()
    {
        return toJavaString((MemorySegment)call(SoapyNative.getABIVersion));
    }

    /**
     * SoapySDR library version string, e.g. "0.8.1-gabcdef".
     */
    public static String getLibVersion()
    {
        return toJavaString((MemorySegment)call(SoapyNative.getLibVersion));
    }

    /**
     * Describes the native library that was loaded: the bundled image for this platform or a system installation.
     */
    public static NativeLibraryInfo getNativeLibraryInfo()
    {
        getAPIVersion(); // ensure loaded
        io.github.dsheirer.soapysdr.internal.NativeLibrary.Info info =
            io.github.dsheirer.soapysdr.internal.NativeLibrary.info();
        return new NativeLibraryInfo(info.platform(), info.bundled(), info.library(), info.root());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Human readable description of a SoapySDR error code.
     */
    public static String errorToString(int errorCode)
    {
        return toJavaString((MemorySegment)call(SoapyNative.errToStr, errorCode));
    }

    /**
     * Size in bytes of one element in the specified stream format, e.g. 8 for "CF32".
     *
     * @throws IllegalArgumentException for an unrecognized format
     */
    public static int formatToSize(String format)
    {
        try(Arena arena = Arena.ofConfined())
        {
            long size = (long)call(SoapyNative.formatToSize, arena.allocateFrom(format));
            if(size <= 0)
            {
                throw new IllegalArgumentException("Unrecognized stream format: " + format);
            }
            return (int)size;
        }
    }

    /**
     * Converts a tick count at the specified rate to nanoseconds.
     */
    public static long ticksToTimeNs(long ticks, double rate)
    {
        return (long)call(SoapyNative.ticksToTimeNs, ticks, rate);
    }

    /**
     * Converts nanoseconds to a tick count at the specified rate.
     */
    public static long timeNsToTicks(long timeNs, double rate)
    {
        return (long)call(SoapyNative.timeNsToTicks, timeNs, rate);
    }

    /**
     * Parses SoapySDR markup ("key0=value0, key1=value1") into an ordered map using the native parser.
     */
    public static Map<String, String> parseKwargs(String markup)
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment kwargs = (MemorySegment)call(SoapyNative.kwargsFromString, arena,
                arena.allocateFrom(markup == null ? "" : markup));
            return SoapyNative.fromKwargsAndClear(kwargs);
        }
    }

    /**
     * Formats a map as SoapySDR markup ("key0=value0, key1=value1") using the native formatter.
     */
    public static String formatKwargs(Map<String, String> args)
    {
        try(Arena arena = Arena.ofConfined())
        {
            String result = toJavaStringAndFree((MemorySegment)call(SoapyNative.kwargsToString,
                SoapyNative.toKwargs(arena, args)));
            return result == null ? "" : result;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Device discovery
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Enumerates all available devices.
     *
     * @return list of device argument maps, each of which can be passed to {@link #open(Map)}
     */
    public static List<Map<String, String>> enumerate()
    {
        return enumerate((Map<String, String>)null);
    }

    /**
     * Enumerates the devices matching the filter markup, e.g. "driver=rtlsdr".
     */
    public static List<Map<String, String>> enumerate(String args)
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment length = arena.allocate(C_SIZE_T);
            MemorySegment result = (MemorySegment)call(SoapyNative.enumerateStrArgs,
                arena.allocateFrom(args == null ? "" : args), length);
            return SoapyNative.fromKwargsListAndClear(result, length.get(C_SIZE_T, 0));
        }
    }

    /**
     * Enumerates the devices matching the filter arguments.
     */
    public static List<Map<String, String>> enumerate(Map<String, String> args)
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment length = arena.allocate(C_SIZE_T);
            MemorySegment result = (MemorySegment)call(SoapyNative.enumerate, SoapyNative.toKwargs(arena, args),
                length);
            return SoapyNative.fromKwargsListAndClear(result, length.get(C_SIZE_T, 0));
        }
    }

    /**
     * Opens a device using markup arguments, e.g. "driver=rtlsdr,serial=00000001".
     *
     * @throws SoapySDRException if no device matches or the driver fails to open it
     */
    public static Device open(String args)
    {
        return Device.open(args);
    }

    /**
     * Opens a device using an argument map, typically one returned by {@link #enumerate()}.
     *
     * @throws SoapySDRException if no device matches or the driver fails to open it
     */
    public static Device open(Map<String, String> args)
    {
        return Device.open(args);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Sets the log level threshold. Messages with a level numerically greater (less severe) are dropped.
     */
    public static void setLogLevel(LogLevel level)
    {
        call(SoapyNative.setLogLevel, level.value());
    }

    /**
     * Sends a message to the SoapySDR logger.
     */
    public static void log(LogLevel level, String message)
    {
        try(Arena arena = Arena.ofConfined())
        {
            call(SoapyNative.log, level.value(), toCString(arena, message));
        }
    }

    /**
     * Registers a handler that receives all SoapySDR log messages, replacing the default handler which writes to
     * stderr. Pass null to restore the default handler.
     */
    public static synchronized void setLogHandler(LogHandler handler)
    {
        sLogHandler = handler;

        if(handler == null)
        {
            call(SoapyNative.registerLogHandler, MemorySegment.NULL);
            return;
        }

        if(sLogStub == null)
        {
            try
            {
                MethodHandle target = MethodHandles.lookup().findStatic(SoapySDR.class, "dispatchLog",
                    MethodType.methodType(void.class, int.class, MemorySegment.class));
                sLogStub = SoapyNative.upcall(target, SoapyNative.LOG_HANDLER, Arena.global());
            }
            catch(ReflectiveOperationException e)
            {
                throw new IllegalStateException("Unable to create log handler upcall", e);
            }
        }

        call(SoapyNative.registerLogHandler, sLogStub);
    }

    /**
     * Upcall target for native log messages. Must never throw.
     */
    private static void dispatchLog(int level, MemorySegment message)
    {
        LogHandler handler = sLogHandler;
        if(handler != null)
        {
            try
            {
                handler.log(LogLevel.fromValue(level), toJavaString(message));
            }
            catch(Throwable ignored)
            {
                //Exceptions must not propagate into native code
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Modules
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Root installation path of the SoapySDR library.
     */
    public static String getRootPath()
    {
        return toJavaString((MemorySegment)call(SoapyNative.getRootPath));
    }

    /**
     * Directories searched for driver modules.
     */
    public static List<String> listSearchPaths()
    {
        return stringList(SoapyNative.listSearchPaths);
    }

    /**
     * Paths of all driver modules found in the search paths.
     */
    public static List<String> listModules()
    {
        return stringList(SoapyNative.listModules);
    }

    /**
     * Paths of all driver modules found in the specified directory.
     */
    public static List<String> listModules(String path)
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment length = arena.allocate(C_SIZE_T);
            MemorySegment result = (MemorySegment)call(SoapyNative.listModulesPath, arena.allocateFrom(path), length);
            return SoapyNative.toStringListAndClear(arena, result, length.get(C_SIZE_T, 0));
        }
    }

    /**
     * Loads a single driver module.
     *
     * @return an empty string on success, otherwise an error message
     */
    public static String loadModule(String path)
    {
        try(Arena arena = Arena.ofConfined())
        {
            String result = toJavaStringAndFree((MemorySegment)call(SoapyNative.loadModule, arena.allocateFrom(path)));
            return result == null ? "" : result;
        }
    }

    /**
     * Unloads a single driver module.
     *
     * @return an empty string on success, otherwise an error message
     */
    public static String unloadModule(String path)
    {
        try(Arena arena = Arena.ofConfined())
        {
            String result = toJavaStringAndFree((MemorySegment)call(SoapyNative.unloadModule,
                arena.allocateFrom(path)));
            return result == null ? "" : result;
        }
    }

    /**
     * Results reported by the registry when the module was loaded (driver name to error message).
     */
    public static Map<String, String> getLoaderResult(String path)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return SoapyNative.fromKwargsAndClear((MemorySegment)call(SoapyNative.getLoaderResult, arena,
                arena.allocateFrom(path)));
        }
    }

    /**
     * Version string reported by a loaded module.
     */
    public static String getModuleVersion(String path)
    {
        try(Arena arena = Arena.ofConfined())
        {
            String result = toJavaStringAndFree((MemorySegment)call(SoapyNative.getModuleVersion,
                arena.allocateFrom(path)));
            return result == null ? "" : result;
        }
    }

    /**
     * Loads all driver modules found in the search paths. Called automatically on first enumerate/make.
     */
    public static void loadModules()
    {
        call(SoapyNative.loadModules);
    }

    /**
     * Unloads all driver modules. All devices must be closed first.
     */
    public static void unloadModules()
    {
        call(SoapyNative.unloadModules);
    }

    private static List<String> stringList(MethodHandle handle)
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment length = arena.allocate(C_SIZE_T);
            MemorySegment result = (MemorySegment)call(handle, length);
            return SoapyNative.toStringListAndClear(arena, result, length.get(C_SIZE_T, 0));
        }
    }
}
