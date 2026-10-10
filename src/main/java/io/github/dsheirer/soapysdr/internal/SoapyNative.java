/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr.internal;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Low-level Foreign Function &amp; Memory bindings to the SoapySDR 0.8 C API (SoapySDR/*.h).
 *
 * <p>This class is internal and is not exported from the module. It exposes one {@link MethodHandle} per C function
 * plus a small set of marshalling helpers for the SoapySDR C structures. All handles are bound when this class is
 * initialized; a missing library results in an {@link UnsatisfiedLinkError} from the first access.</p>
 *
 * <p>Only 64-bit platforms are supported ({@code size_t} is mapped to a Java {@code long}). The C {@code long} type,
 * which is 32 bits on Windows and 64 bits elsewhere, is always presented to Java as a {@code long}.</p>
 */
public final class SoapyNative
{
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP = NativeLibrary.load();

    public static final AddressLayout C_POINTER = ValueLayout.ADDRESS;
    public static final ValueLayout.OfInt C_INT = ValueLayout.JAVA_INT;
    public static final ValueLayout.OfLong C_LONG_LONG = ValueLayout.JAVA_LONG;
    public static final ValueLayout.OfDouble C_DOUBLE = ValueLayout.JAVA_DOUBLE;
    public static final ValueLayout.OfBoolean C_BOOL = ValueLayout.JAVA_BOOLEAN;
    public static final ValueLayout.OfLong C_SIZE_T = ValueLayout.JAVA_LONG;
    public static final ValueLayout C_LONG = (ValueLayout)LINKER.canonicalLayouts().get("long");

    static
    {
        if(LINKER.canonicalLayouts().get("size_t").byteSize() != 8)
        {
            throw new UnsupportedOperationException("jSoapySDR requires a 64-bit platform");
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Structure layouts
    // ---------------------------------------------------------------------------------------------------------------

    /** typedef struct { double minimum; double maximum; double step; } SoapySDRRange; */
    public static final StructLayout RANGE = MemoryLayout.structLayout(
        C_DOUBLE.withName("minimum"),
        C_DOUBLE.withName("maximum"),
        C_DOUBLE.withName("step")).withName("SoapySDRRange");

    /** typedef struct { size_t size; char **keys; char **vals; } SoapySDRKwargs; */
    public static final StructLayout KWARGS = MemoryLayout.structLayout(
        C_SIZE_T.withName("size"),
        C_POINTER.withName("keys"),
        C_POINTER.withName("vals")).withName("SoapySDRKwargs");

    /** SoapySDRArgInfo */
    public static final StructLayout ARG_INFO = MemoryLayout.structLayout(
        C_POINTER.withName("key"),
        C_POINTER.withName("value"),
        C_POINTER.withName("name"),
        C_POINTER.withName("description"),
        C_POINTER.withName("units"),
        C_INT.withName("type"),
        MemoryLayout.paddingLayout(4),
        RANGE.withName("range"),
        C_SIZE_T.withName("numOptions"),
        C_POINTER.withName("options"),
        C_POINTER.withName("optionNames")).withName("SoapySDRArgInfo");

    private static final long KWARGS_SIZE = KWARGS.byteOffset(MemoryLayout.PathElement.groupElement("size"));
    private static final long KWARGS_KEYS = KWARGS.byteOffset(MemoryLayout.PathElement.groupElement("keys"));
    private static final long KWARGS_VALS = KWARGS.byteOffset(MemoryLayout.PathElement.groupElement("vals"));

    // ---------------------------------------------------------------------------------------------------------------
    // Version.h / Errors.h / Formats.h / Time.h / Types.h
    // ---------------------------------------------------------------------------------------------------------------

    public static final MethodHandle getAPIVersion = fn("SoapySDR_getAPIVersion", C_POINTER);
    public static final MethodHandle getABIVersion = fn("SoapySDR_getABIVersion", C_POINTER);
    public static final MethodHandle getLibVersion = fn("SoapySDR_getLibVersion", C_POINTER);
    public static final MethodHandle errToStr = fn("SoapySDR_errToStr", C_POINTER, C_INT);
    public static final MethodHandle formatToSize = fn("SoapySDR_formatToSize", C_SIZE_T, C_POINTER);
    public static final MethodHandle ticksToTimeNs = fn("SoapySDR_ticksToTimeNs", C_LONG_LONG, C_LONG_LONG, C_DOUBLE);
    public static final MethodHandle timeNsToTicks = fn("SoapySDR_timeNsToTicks", C_LONG_LONG, C_LONG_LONG, C_DOUBLE);

    public static final MethodHandle free = fnVoid("SoapySDR_free", C_POINTER);
    public static final MethodHandle stringsClear = fnVoid("SoapySDRStrings_clear", C_POINTER, C_SIZE_T);
    public static final MethodHandle kwargsFromString = fn("SoapySDRKwargs_fromString", KWARGS, C_POINTER);
    public static final MethodHandle kwargsToString = fn("SoapySDRKwargs_toString", C_POINTER, C_POINTER);
    public static final MethodHandle kwargsClear = fnVoid("SoapySDRKwargs_clear", C_POINTER);
    public static final MethodHandle kwargsListClear = fnVoid("SoapySDRKwargsList_clear", C_POINTER, C_SIZE_T);
    public static final MethodHandle argInfoClear = fnVoid("SoapySDRArgInfo_clear", C_POINTER);
    public static final MethodHandle argInfoListClear = fnVoid("SoapySDRArgInfoList_clear", C_POINTER, C_SIZE_T);

    // ---------------------------------------------------------------------------------------------------------------
    // Logger.h
    // ---------------------------------------------------------------------------------------------------------------

    public static final FunctionDescriptor LOG_HANDLER = FunctionDescriptor.ofVoid(C_INT, C_POINTER);
    public static final MethodHandle log = fnVoid("SoapySDR_log", C_INT, C_POINTER);
    public static final MethodHandle registerLogHandler = fnVoid("SoapySDR_registerLogHandler", C_POINTER);
    public static final MethodHandle setLogLevel = fnVoid("SoapySDR_setLogLevel", C_INT);

    // ---------------------------------------------------------------------------------------------------------------
    // Modules.h
    // ---------------------------------------------------------------------------------------------------------------

    public static final MethodHandle getRootPath = fn("SoapySDR_getRootPath", C_POINTER);
    public static final MethodHandle listSearchPaths = fn("SoapySDR_listSearchPaths", C_POINTER, C_POINTER);
    public static final MethodHandle listModules = fn("SoapySDR_listModules", C_POINTER, C_POINTER);
    public static final MethodHandle listModulesPath = fn("SoapySDR_listModulesPath", C_POINTER, C_POINTER, C_POINTER);
    public static final MethodHandle loadModule = fn("SoapySDR_loadModule", C_POINTER, C_POINTER);
    public static final MethodHandle getLoaderResult = fn("SoapySDR_getLoaderResult", KWARGS, C_POINTER);
    public static final MethodHandle getModuleVersion = fn("SoapySDR_getModuleVersion", C_POINTER, C_POINTER);
    public static final MethodHandle unloadModule = fn("SoapySDR_unloadModule", C_POINTER, C_POINTER);
    public static final MethodHandle loadModules = fnVoid("SoapySDR_loadModules");
    public static final MethodHandle unloadModules = fnVoid("SoapySDR_unloadModules");

    // ---------------------------------------------------------------------------------------------------------------
    // Device.h
    // ---------------------------------------------------------------------------------------------------------------

    private static final ValueLayout P = C_POINTER;
    private static final ValueLayout I = C_INT;
    private static final ValueLayout S = C_SIZE_T;
    private static final ValueLayout D = C_DOUBLE;
    private static final ValueLayout B = C_BOOL;
    private static final ValueLayout LL = C_LONG_LONG;
    private static final ValueLayout U = C_INT; //unsigned int, same width

    public static final MethodHandle lastStatus = fn("SoapySDRDevice_lastStatus", I);
    public static final MethodHandle lastError = fn("SoapySDRDevice_lastError", P);

    public static final MethodHandle enumerate = fn("SoapySDRDevice_enumerate", P, P, P);
    public static final MethodHandle enumerateStrArgs = fn("SoapySDRDevice_enumerateStrArgs", P, P, P);
    public static final MethodHandle make = fn("SoapySDRDevice_make", P, P);
    public static final MethodHandle makeStrArgs = fn("SoapySDRDevice_makeStrArgs", P, P);
    public static final MethodHandle unmake = fn("SoapySDRDevice_unmake", I, P);

    public static final MethodHandle getDriverKey = fn("SoapySDRDevice_getDriverKey", P, P);
    public static final MethodHandle getHardwareKey = fn("SoapySDRDevice_getHardwareKey", P, P);
    public static final MethodHandle getHardwareInfo = fn("SoapySDRDevice_getHardwareInfo", KWARGS, P);

    public static final MethodHandle setFrontendMapping = fn("SoapySDRDevice_setFrontendMapping", I, P, I, P);
    public static final MethodHandle getFrontendMapping = fn("SoapySDRDevice_getFrontendMapping", P, P, I);
    public static final MethodHandle getNumChannels = fn("SoapySDRDevice_getNumChannels", S, P, I);
    public static final MethodHandle getChannelInfo = fn("SoapySDRDevice_getChannelInfo", KWARGS, P, I, S);
    public static final MethodHandle getFullDuplex = fn("SoapySDRDevice_getFullDuplex", B, P, I, S);

    public static final MethodHandle getStreamFormats = fn("SoapySDRDevice_getStreamFormats", P, P, I, S, P);
    public static final MethodHandle getNativeStreamFormat = fn("SoapySDRDevice_getNativeStreamFormat", P, P, I, S, P);
    public static final MethodHandle getStreamArgsInfo = fn("SoapySDRDevice_getStreamArgsInfo", P, P, I, S, P);
    public static final MethodHandle setupStream = fn("SoapySDRDevice_setupStream", P, P, I, P, P, S, P);
    public static final MethodHandle closeStream = fn("SoapySDRDevice_closeStream", I, P, P);
    public static final MethodHandle getStreamMTU = fn("SoapySDRDevice_getStreamMTU", S, P, P);
    public static final MethodHandle activateStream = fn("SoapySDRDevice_activateStream", I, P, P, I, LL, S);
    public static final MethodHandle deactivateStream = fn("SoapySDRDevice_deactivateStream", I, P, P, I, LL);
    public static final MethodHandle readStream = fnLongAdapted("SoapySDRDevice_readStream", I, P, P, P, S, P, P, C_LONG);
    public static final MethodHandle writeStream = fnLongAdapted("SoapySDRDevice_writeStream", I, P, P, P, S, P, LL, C_LONG);
    public static final MethodHandle readStreamStatus = fnLongAdapted("SoapySDRDevice_readStreamStatus", I, P, P, P, P, P, C_LONG);

    public static final MethodHandle listAntennas = fn("SoapySDRDevice_listAntennas", P, P, I, S, P);
    public static final MethodHandle setAntenna = fn("SoapySDRDevice_setAntenna", I, P, I, S, P);
    public static final MethodHandle getAntenna = fn("SoapySDRDevice_getAntenna", P, P, I, S);

    public static final MethodHandle hasDCOffsetMode = fn("SoapySDRDevice_hasDCOffsetMode", B, P, I, S);
    public static final MethodHandle setDCOffsetMode = fn("SoapySDRDevice_setDCOffsetMode", I, P, I, S, B);
    public static final MethodHandle getDCOffsetMode = fn("SoapySDRDevice_getDCOffsetMode", B, P, I, S);
    public static final MethodHandle hasDCOffset = fn("SoapySDRDevice_hasDCOffset", B, P, I, S);
    public static final MethodHandle setDCOffset = fn("SoapySDRDevice_setDCOffset", I, P, I, S, D, D);
    public static final MethodHandle getDCOffset = fn("SoapySDRDevice_getDCOffset", I, P, I, S, P, P);
    public static final MethodHandle hasIQBalance = fn("SoapySDRDevice_hasIQBalance", B, P, I, S);
    public static final MethodHandle setIQBalance = fn("SoapySDRDevice_setIQBalance", I, P, I, S, D, D);
    public static final MethodHandle getIQBalance = fn("SoapySDRDevice_getIQBalance", I, P, I, S, P, P);
    public static final MethodHandle hasIQBalanceMode = fn("SoapySDRDevice_hasIQBalanceMode", B, P, I, S);
    public static final MethodHandle setIQBalanceMode = fn("SoapySDRDevice_setIQBalanceMode", I, P, I, S, B);
    public static final MethodHandle getIQBalanceMode = fn("SoapySDRDevice_getIQBalanceMode", B, P, I, S);
    public static final MethodHandle hasFrequencyCorrection = fn("SoapySDRDevice_hasFrequencyCorrection", B, P, I, S);
    public static final MethodHandle setFrequencyCorrection = fn("SoapySDRDevice_setFrequencyCorrection", I, P, I, S, D);
    public static final MethodHandle getFrequencyCorrection = fn("SoapySDRDevice_getFrequencyCorrection", D, P, I, S);

    public static final MethodHandle listGains = fn("SoapySDRDevice_listGains", P, P, I, S, P);
    public static final MethodHandle hasGainMode = fn("SoapySDRDevice_hasGainMode", B, P, I, S);
    public static final MethodHandle setGainMode = fn("SoapySDRDevice_setGainMode", I, P, I, S, B);
    public static final MethodHandle getGainMode = fn("SoapySDRDevice_getGainMode", B, P, I, S);
    public static final MethodHandle setGain = fn("SoapySDRDevice_setGain", I, P, I, S, D);
    public static final MethodHandle setGainElement = fn("SoapySDRDevice_setGainElement", I, P, I, S, P, D);
    public static final MethodHandle getGain = fn("SoapySDRDevice_getGain", D, P, I, S);
    public static final MethodHandle getGainElement = fn("SoapySDRDevice_getGainElement", D, P, I, S, P);
    public static final MethodHandle getGainRange = fn("SoapySDRDevice_getGainRange", RANGE, P, I, S);
    public static final MethodHandle getGainElementRange = fn("SoapySDRDevice_getGainElementRange", RANGE, P, I, S, P);

    public static final MethodHandle setFrequency = fn("SoapySDRDevice_setFrequency", I, P, I, S, D, P);
    public static final MethodHandle setFrequencyComponent = fn("SoapySDRDevice_setFrequencyComponent", I, P, I, S, P, D, P);
    public static final MethodHandle getFrequency = fn("SoapySDRDevice_getFrequency", D, P, I, S);
    public static final MethodHandle getFrequencyComponent = fn("SoapySDRDevice_getFrequencyComponent", D, P, I, S, P);
    public static final MethodHandle listFrequencies = fn("SoapySDRDevice_listFrequencies", P, P, I, S, P);
    public static final MethodHandle getFrequencyRange = fn("SoapySDRDevice_getFrequencyRange", P, P, I, S, P);
    public static final MethodHandle getFrequencyRangeComponent = fn("SoapySDRDevice_getFrequencyRangeComponent", P, P, I, S, P, P);
    public static final MethodHandle getFrequencyArgsInfo = fn("SoapySDRDevice_getFrequencyArgsInfo", P, P, I, S, P);

    public static final MethodHandle setSampleRate = fn("SoapySDRDevice_setSampleRate", I, P, I, S, D);
    public static final MethodHandle getSampleRate = fn("SoapySDRDevice_getSampleRate", D, P, I, S);
    public static final MethodHandle listSampleRates = fn("SoapySDRDevice_listSampleRates", P, P, I, S, P);
    public static final MethodHandle getSampleRateRange = fn("SoapySDRDevice_getSampleRateRange", P, P, I, S, P);

    public static final MethodHandle setBandwidth = fn("SoapySDRDevice_setBandwidth", I, P, I, S, D);
    public static final MethodHandle getBandwidth = fn("SoapySDRDevice_getBandwidth", D, P, I, S);
    public static final MethodHandle listBandwidths = fn("SoapySDRDevice_listBandwidths", P, P, I, S, P);
    public static final MethodHandle getBandwidthRange = fn("SoapySDRDevice_getBandwidthRange", P, P, I, S, P);

    public static final MethodHandle setMasterClockRate = fn("SoapySDRDevice_setMasterClockRate", I, P, D);
    public static final MethodHandle getMasterClockRate = fn("SoapySDRDevice_getMasterClockRate", D, P);
    public static final MethodHandle getMasterClockRates = fn("SoapySDRDevice_getMasterClockRates", P, P, P);
    public static final MethodHandle setReferenceClockRate = fn("SoapySDRDevice_setReferenceClockRate", I, P, D);
    public static final MethodHandle getReferenceClockRate = fn("SoapySDRDevice_getReferenceClockRate", D, P);
    public static final MethodHandle getReferenceClockRates = fn("SoapySDRDevice_getReferenceClockRates", P, P, P);
    public static final MethodHandle listClockSources = fn("SoapySDRDevice_listClockSources", P, P, P);
    public static final MethodHandle setClockSource = fn("SoapySDRDevice_setClockSource", I, P, P);
    public static final MethodHandle getClockSource = fn("SoapySDRDevice_getClockSource", P, P);

    public static final MethodHandle listTimeSources = fn("SoapySDRDevice_listTimeSources", P, P, P);
    public static final MethodHandle setTimeSource = fn("SoapySDRDevice_setTimeSource", I, P, P);
    public static final MethodHandle getTimeSource = fn("SoapySDRDevice_getTimeSource", P, P);
    public static final MethodHandle hasHardwareTime = fn("SoapySDRDevice_hasHardwareTime", B, P, P);
    public static final MethodHandle getHardwareTime = fn("SoapySDRDevice_getHardwareTime", LL, P, P);
    public static final MethodHandle setHardwareTime = fn("SoapySDRDevice_setHardwareTime", I, P, LL, P);
    public static final MethodHandle setCommandTime = fn("SoapySDRDevice_setCommandTime", I, P, LL, P);

    public static final MethodHandle listSensors = fn("SoapySDRDevice_listSensors", P, P, P);
    public static final MethodHandle getSensorInfo = fn("SoapySDRDevice_getSensorInfo", ARG_INFO, P, P);
    public static final MethodHandle readSensor = fn("SoapySDRDevice_readSensor", P, P, P);
    public static final MethodHandle listChannelSensors = fn("SoapySDRDevice_listChannelSensors", P, P, I, S, P);
    public static final MethodHandle getChannelSensorInfo = fn("SoapySDRDevice_getChannelSensorInfo", ARG_INFO, P, I, S, P);
    public static final MethodHandle readChannelSensor = fn("SoapySDRDevice_readChannelSensor", P, P, I, S, P);

    public static final MethodHandle listRegisterInterfaces = fn("SoapySDRDevice_listRegisterInterfaces", P, P, P);
    public static final MethodHandle writeRegister = fn("SoapySDRDevice_writeRegister", I, P, P, U, U);
    public static final MethodHandle readRegister = fn("SoapySDRDevice_readRegister", U, P, P, U);
    public static final MethodHandle writeRegisters = fn("SoapySDRDevice_writeRegisters", I, P, P, U, P, S);
    public static final MethodHandle readRegisters = fn("SoapySDRDevice_readRegisters", P, P, P, U, P);

    public static final MethodHandle getSettingInfo = fn("SoapySDRDevice_getSettingInfo", P, P, P);
    public static final MethodHandle writeSetting = fn("SoapySDRDevice_writeSetting", I, P, P, P);
    public static final MethodHandle readSetting = fn("SoapySDRDevice_readSetting", P, P, P);
    public static final MethodHandle getChannelSettingInfo = fn("SoapySDRDevice_getChannelSettingInfo", P, P, I, S, P);
    public static final MethodHandle writeChannelSetting = fn("SoapySDRDevice_writeChannelSetting", I, P, I, S, P, P);
    public static final MethodHandle readChannelSetting = fn("SoapySDRDevice_readChannelSetting", P, P, I, S, P);

    public static final MethodHandle listGPIOBanks = fn("SoapySDRDevice_listGPIOBanks", P, P, P);
    public static final MethodHandle writeGPIO = fn("SoapySDRDevice_writeGPIO", I, P, P, U);
    public static final MethodHandle writeGPIOMasked = fn("SoapySDRDevice_writeGPIOMasked", I, P, P, U, U);
    public static final MethodHandle readGPIO = fn("SoapySDRDevice_readGPIO", U, P, P);
    public static final MethodHandle writeGPIODir = fn("SoapySDRDevice_writeGPIODir", I, P, P, U);
    public static final MethodHandle writeGPIODirMasked = fn("SoapySDRDevice_writeGPIODirMasked", I, P, P, U, U);
    public static final MethodHandle readGPIODir = fn("SoapySDRDevice_readGPIODir", U, P, P);

    public static final MethodHandle writeI2C = fn("SoapySDRDevice_writeI2C", I, P, I, P, S);
    public static final MethodHandle readI2C = fn("SoapySDRDevice_readI2C", P, P, I, P);
    public static final MethodHandle transactSPI = fn("SoapySDRDevice_transactSPI", U, P, I, U, S);

    public static final MethodHandle listUARTs = fn("SoapySDRDevice_listUARTs", P, P, P);
    public static final MethodHandle writeUART = fn("SoapySDRDevice_writeUART", I, P, P, P);
    public static final MethodHandle readUART = fnLongAdapted("SoapySDRDevice_readUART", P, P, P, C_LONG);

    public static final MethodHandle getNativeDeviceHandle = fn("SoapySDRDevice_getNativeDeviceHandle", P, P);

    private SoapyNative()
    {
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Binding helpers
    // ---------------------------------------------------------------------------------------------------------------

    private static MemorySegment symbol(String name)
    {
        return LOOKUP.find(name).orElseThrow(() -> new UnsatisfiedLinkError("SoapySDR symbol not found: " + name +
            " (jSoapySDR requires SoapySDR 0.8.x)"));
    }

    private static MethodHandle fn(String name, MemoryLayout returnLayout, MemoryLayout... args)
    {
        return LINKER.downcallHandle(symbol(name), FunctionDescriptor.of(returnLayout, args));
    }

    private static MethodHandle fnVoid(String name, MemoryLayout... args)
    {
        return LINKER.downcallHandle(symbol(name), FunctionDescriptor.ofVoid(args));
    }

    /**
     * Binds a function whose trailing argument is a C {@code long} and adapts that argument to a Java {@code long}
     * regardless of the platform width of C {@code long}.
     */
    private static MethodHandle fnLongAdapted(String name, MemoryLayout returnLayout, MemoryLayout... args)
    {
        MethodHandle handle = fn(name, returnLayout, args);
        MethodType type = handle.type();
        int last = type.parameterCount() - 1;
        if(type.parameterType(last) == int.class)
        {
            type = type.changeParameterType(last, long.class);
        }
        return MethodHandles.explicitCastArguments(handle, type);
    }

    /**
     * Creates the upcall stub for a SoapySDR log handler, bound to the global arena.
     */
    public static MemorySegment upcall(MethodHandle target, FunctionDescriptor descriptor, Arena arena)
    {
        return LINKER.upcallStub(target, descriptor, arena);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Invocation helpers
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Invokes a native method handle, rethrowing any {@link Throwable} unchecked.
     */
    public static Object call(MethodHandle handle, Object... args)
    {
        try
        {
            return handle.invokeWithArguments(args);
        }
        catch(RuntimeException | Error e)
        {
            throw e;
        }
        catch(Throwable t)
        {
            throw new IllegalStateException("Native call failed", t);
        }
    }

    /**
     * Last status code reported by a SoapySDRDevice_* call on the current native thread (0 means success).
     */
    public static int lastStatus()
    {
        return (int)call(lastStatus);
    }

    /**
     * Last error message reported by a SoapySDRDevice_* call on the current native thread.
     */
    public static String lastError()
    {
        return toJavaString((MemorySegment)call(lastError));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Marshalling helpers
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Reads a NUL terminated C string, returning an empty string for a null pointer. Does not free the memory.
     */
    public static String toJavaString(MemorySegment address)
    {
        if(address == null || address.equals(MemorySegment.NULL))
        {
            return "";
        }
        return address.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /**
     * Reads a C string that was allocated by SoapySDR and must be released with SoapySDR_free(), then frees it.
     * Returns null when the pointer is null.
     */
    public static String toJavaStringAndFree(MemorySegment address)
    {
        if(address == null || address.equals(MemorySegment.NULL))
        {
            return null;
        }
        try
        {
            return address.reinterpret(Long.MAX_VALUE).getString(0);
        }
        finally
        {
            call(free, address);
        }
    }

    /**
     * Allocates a NUL terminated copy of the string in the arena, or returns NULL for a null string.
     */
    public static MemorySegment toCString(Arena arena, String value)
    {
        return value == null ? MemorySegment.NULL : arena.allocateFrom(value);
    }

    /**
     * Reads a {@code char**} array of the specified length and releases it with SoapySDRStrings_clear().
     */
    public static List<String> toStringListAndClear(Arena arena, MemorySegment array, long length)
    {
        if(array == null || array.equals(MemorySegment.NULL) || length <= 0)
        {
            if(array != null && !array.equals(MemorySegment.NULL))
            {
                clearStrings(arena, array, 0);
            }
            return Collections.emptyList();
        }

        MemorySegment pointers = array.reinterpret(length * C_POINTER.byteSize());
        List<String> strings = new ArrayList<>((int)length);
        for(long x = 0; x < length; x++)
        {
            strings.add(toJavaString(pointers.getAtIndex(C_POINTER, x)));
        }
        clearStrings(arena, array, length);
        return Collections.unmodifiableList(strings);
    }

    private static void clearStrings(Arena arena, MemorySegment array, long length)
    {
        MemorySegment holder = arena.allocate(C_POINTER);
        holder.set(C_POINTER, 0, array);
        call(stringsClear, holder, length);
    }

    /**
     * Reads a {@code char**} array of the specified length without releasing it.
     */
    public static List<String> toStringList(MemorySegment array, long length)
    {
        if(array == null || array.equals(MemorySegment.NULL) || length <= 0)
        {
            return Collections.emptyList();
        }
        MemorySegment pointers = array.reinterpret(length * C_POINTER.byteSize());
        List<String> strings = new ArrayList<>((int)length);
        for(long x = 0; x < length; x++)
        {
            strings.add(toJavaString(pointers.getAtIndex(C_POINTER, x)));
        }
        return strings;
    }

    /**
     * Reads a {@code double*} array of the specified length and releases it with SoapySDR_free().
     */
    public static double[] toDoubleArrayAndFree(MemorySegment array, long length)
    {
        if(array == null || array.equals(MemorySegment.NULL))
        {
            return new double[0];
        }
        try
        {
            if(length <= 0)
            {
                return new double[0];
            }
            return array.reinterpret(length * C_DOUBLE.byteSize()).toArray(C_DOUBLE);
        }
        finally
        {
            call(free, array);
        }
    }

    /**
     * Allocates a native SoapySDRKwargs structure in the arena with the contents of the map. A null or empty map
     * produces a structure with a size of zero.
     */
    public static MemorySegment toKwargs(Arena arena, Map<String, String> map)
    {
        MemorySegment kwargs = arena.allocate(KWARGS);
        int size = map == null ? 0 : map.size();
        kwargs.set(C_SIZE_T, KWARGS_SIZE, size);

        if(size == 0)
        {
            kwargs.set(C_POINTER, KWARGS_KEYS, MemorySegment.NULL);
            kwargs.set(C_POINTER, KWARGS_VALS, MemorySegment.NULL);
            return kwargs;
        }

        MemorySegment keys = arena.allocate(C_POINTER, size);
        MemorySegment vals = arena.allocate(C_POINTER, size);
        int index = 0;
        for(Map.Entry<String, String> entry : map.entrySet())
        {
            keys.setAtIndex(C_POINTER, index, arena.allocateFrom(entry.getKey()));
            vals.setAtIndex(C_POINTER, index, arena.allocateFrom(entry.getValue() == null ? "" : entry.getValue()));
            index++;
        }
        kwargs.set(C_POINTER, KWARGS_KEYS, keys);
        kwargs.set(C_POINTER, KWARGS_VALS, vals);
        return kwargs;
    }

    /**
     * Reads a SoapySDRKwargs structure into an insertion-ordered map. Does not free native memory.
     */
    public static Map<String, String> fromKwargs(MemorySegment kwargs)
    {
        MemorySegment struct = kwargs.reinterpret(KWARGS.byteSize());
        long size = struct.get(C_SIZE_T, KWARGS_SIZE);
        Map<String, String> map = new LinkedHashMap<>();
        if(size > 0)
        {
            List<String> keys = toStringList(struct.get(C_POINTER, KWARGS_KEYS), size);
            List<String> vals = toStringList(struct.get(C_POINTER, KWARGS_VALS), size);
            for(int x = 0; x < size; x++)
            {
                map.put(keys.get(x), vals.get(x));
            }
        }
        return map;
    }

    /**
     * Reads a SoapySDRKwargs structure that was returned by value into arena memory and then releases the native
     * key/value strings with SoapySDRKwargs_clear().
     */
    public static Map<String, String> fromKwargsAndClear(MemorySegment kwargs)
    {
        try
        {
            return Collections.unmodifiableMap(fromKwargs(kwargs));
        }
        finally
        {
            call(kwargsClear, kwargs);
        }
    }

    /**
     * Reads an array of SoapySDRKwargs and releases it with SoapySDRKwargsList_clear().
     */
    public static List<Map<String, String>> fromKwargsListAndClear(MemorySegment array, long length)
    {
        if(array == null || array.equals(MemorySegment.NULL))
        {
            return Collections.emptyList();
        }
        try
        {
            MemorySegment list = array.reinterpret(Math.max(length, 0) * KWARGS.byteSize());
            List<Map<String, String>> results = new ArrayList<>((int)Math.max(length, 0));
            for(long x = 0; x < length; x++)
            {
                results.add(Collections.unmodifiableMap(fromKwargs(list.asSlice(x * KWARGS.byteSize(), KWARGS))));
            }
            return Collections.unmodifiableList(results);
        }
        finally
        {
            call(kwargsListClear, array, length);
        }
    }

    /**
     * Reads the minimum, maximum and step fields of a SoapySDRRange structure.
     */
    public static double[] fromRange(MemorySegment range)
    {
        MemorySegment struct = range.reinterpret(RANGE.byteSize());
        return new double[]{struct.get(C_DOUBLE, 0), struct.get(C_DOUBLE, 8), struct.get(C_DOUBLE, 16)};
    }

    /**
     * Reads an array of SoapySDRRange structures and releases it with SoapySDR_free().
     */
    public static List<double[]> fromRangeListAndFree(MemorySegment array, long length)
    {
        if(array == null || array.equals(MemorySegment.NULL))
        {
            return Collections.emptyList();
        }
        try
        {
            List<double[]> ranges = new ArrayList<>();
            if(length > 0)
            {
                MemorySegment list = array.reinterpret(length * RANGE.byteSize());
                for(long x = 0; x < length; x++)
                {
                    ranges.add(fromRange(list.asSlice(x * RANGE.byteSize(), RANGE)));
                }
            }
            return ranges;
        }
        finally
        {
            call(free, array);
        }
    }

    /**
     * Raw field values of a SoapySDRArgInfo structure.
     */
    public record RawArgInfo(String key, String value, String name, String description, String units, int type,
                             double[] range, List<String> options, List<String> optionNames)
    {
    }

    /**
     * Reads a single SoapySDRArgInfo structure. Does not free native memory.
     */
    public static RawArgInfo fromArgInfo(MemorySegment info)
    {
        MemorySegment s = info.reinterpret(ARG_INFO.byteSize());
        long rangeOffset = ARG_INFO.byteOffset(MemoryLayout.PathElement.groupElement("range"));
        long numOptionsOffset = ARG_INFO.byteOffset(MemoryLayout.PathElement.groupElement("numOptions"));
        long optionsOffset = ARG_INFO.byteOffset(MemoryLayout.PathElement.groupElement("options"));
        long optionNamesOffset = ARG_INFO.byteOffset(MemoryLayout.PathElement.groupElement("optionNames"));
        long typeOffset = ARG_INFO.byteOffset(MemoryLayout.PathElement.groupElement("type"));

        long numOptions = s.get(C_SIZE_T, numOptionsOffset);

        return new RawArgInfo(
            toJavaString(s.get(C_POINTER, 0)),
            toJavaString(s.get(C_POINTER, 8)),
            toJavaString(s.get(C_POINTER, 16)),
            toJavaString(s.get(C_POINTER, 24)),
            toJavaString(s.get(C_POINTER, 32)),
            s.get(C_INT, typeOffset),
            fromRange(s.asSlice(rangeOffset, RANGE)),
            toStringList(s.get(C_POINTER, optionsOffset), numOptions),
            toStringList(s.get(C_POINTER, optionNamesOffset), numOptions));
    }

    /**
     * Reads a single SoapySDRArgInfo returned by value into arena memory and releases its fields.
     */
    public static RawArgInfo fromArgInfoAndClear(MemorySegment info)
    {
        try
        {
            return fromArgInfo(info);
        }
        finally
        {
            call(argInfoClear, info);
        }
    }

    /**
     * Reads an array of SoapySDRArgInfo structures and releases it with SoapySDRArgInfoList_clear().
     */
    public static List<RawArgInfo> fromArgInfoListAndClear(MemorySegment array, long length)
    {
        if(array == null || array.equals(MemorySegment.NULL))
        {
            return Collections.emptyList();
        }
        try
        {
            List<RawArgInfo> infos = new ArrayList<>();
            if(length > 0)
            {
                MemorySegment list = array.reinterpret(length * ARG_INFO.byteSize());
                for(long x = 0; x < length; x++)
                {
                    infos.add(fromArgInfo(list.asSlice(x * ARG_INFO.byteSize(), ARG_INFO)));
                }
            }
            return infos;
        }
        finally
        {
            call(argInfoListClear, array, length);
        }
    }
}
