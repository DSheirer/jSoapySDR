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
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static io.github.dsheirer.soapysdr.internal.SoapyNative.C_DOUBLE;
import static io.github.dsheirer.soapysdr.internal.SoapyNative.C_INT;
import static io.github.dsheirer.soapysdr.internal.SoapyNative.C_SIZE_T;
import static io.github.dsheirer.soapysdr.internal.SoapyNative.call;
import static io.github.dsheirer.soapysdr.internal.SoapyNative.toCString;
import static io.github.dsheirer.soapysdr.internal.SoapyNative.toJavaStringAndFree;

/**
 * An open SoapySDR device (SoapySDRDevice).
 *
 * <p>Create with {@link SoapySDR#open(String)} or {@link SoapySDR#open(Map)} and release with {@link #close()},
 * preferably via try-with-resources. Closing a device closes any streams that are still open on it.</p>
 *
 * <p>Methods that fail throw {@link SoapySDRException} carrying the driver's error message. SoapySDR records the
 * status of each call in thread-local storage on the calling native thread. Avoid calling device methods from
 * virtual threads that may be rescheduled between the native call and the status check; use platform threads for
 * device control (this is the normal case for SDR applications).</p>
 *
 * <p>Instances are safe to use from multiple threads to the same extent as the underlying driver. Many drivers
 * allow a streaming thread to read while another thread adjusts frequency and gain.</p>
 */
public final class Device implements AutoCloseable
{
    private final MemorySegment mHandle;
    private final String mDriverKey;
    private final List<Stream> mStreams = Collections.synchronizedList(new ArrayList<>());
    private final ReentrantReadWriteLock mCloseLock = new ReentrantReadWriteLock();
    private boolean mClosed;

    private Device(MemorySegment handle)
    {
        mHandle = handle;
        mDriverKey = toJavaStringAndFree((MemorySegment)call(SoapyNative.getDriverKey, handle));
    }

    /**
     * Opens a device using markup arguments, e.g. "driver=rtlsdr,serial=00000001".
     */
    public static Device open(String args)
    {
        // Ensure the library version has been validated
        SoapySDR.getAPIVersion();
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment handle = (MemorySegment)call(SoapyNative.makeStrArgs, toCString(arena, args == null ? "" : args));
            return created(handle, args);
        }
    }

    /**
     * Opens a device using an argument map, typically one returned by {@link SoapySDR#enumerate()}.
     */
    public static Device open(Map<String, String> args)
    {
        SoapySDR.getAPIVersion();
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment handle = (MemorySegment)call(SoapyNative.make, SoapyNative.toKwargs(arena, args));
            return created(handle, String.valueOf(args));
        }
    }

    private static Device created(MemorySegment handle, String args)
    {
        if(handle == null || handle.equals(MemorySegment.NULL))
        {
            String error = SoapyNative.lastError();
            throw new SoapySDRException("Unable to open device [" + args + "]: " +
                (error.isEmpty() ? "no matching device" : error), SoapyNative.lastStatus());
        }
        return new Device(handle);
    }

    /**
     * Raw native SoapySDRDevice pointer. Valid only until {@link #close()}.
     */
    public MemorySegment nativeHandle()
    {
        return mHandle;
    }

    /**
     * Indicates if this device has been closed.
     */
    public boolean isClosed()
    {
        mCloseLock.readLock().lock();
        try
        {
            return mClosed;
        }
        finally
        {
            mCloseLock.readLock().unlock();
        }
    }

    /**
     * Closes all open streams and releases the device (SoapySDRDevice_unmake). Calling close more than once has no
     * effect.
     */
    @Override
    public void close()
    {
        List<Stream> streams;
        synchronized(mStreams)
        {
            streams = new ArrayList<>(mStreams);
        }
        for(Stream stream : streams)
        {
            try
            {
                stream.close();
            }
            catch(RuntimeException ignored)
            {
                //Best effort while closing the device
            }
        }

        mCloseLock.writeLock().lock();
        try
        {
            if(mClosed)
            {
                return;
            }
            mClosed = true;
            int status = (int)call(SoapyNative.unmake, mHandle);
            if(status != 0)
            {
                throw new SoapySDRException("Error closing device: " + SoapyNative.lastError(), status);
            }
        }
        finally
        {
            mCloseLock.writeLock().unlock();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Invocation helpers
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Runs a native operation while holding the read side of the close lock so the handle cannot be released
     * concurrently.
     */
    private <T> T guarded(java.util.function.Supplier<T> operation)
    {
        mCloseLock.readLock().lock();
        try
        {
            if(mClosed)
            {
                throw new IllegalStateException("Device has been closed");
            }
            return operation.get();
        }
        finally
        {
            mCloseLock.readLock().unlock();
        }
    }

    private Object[] withHandle(Object... args)
    {
        Object[] all = new Object[args.length + 1];
        all[0] = mHandle;
        System.arraycopy(args, 0, all, 1, args.length);
        return all;
    }

    /**
     * Invokes a function returning an int status and throws if it is non-zero.
     */
    private void status(String operation, MethodHandle handle, Object... args)
    {
        guarded(() -> {
            int status = (int)call(handle, withHandle(args));
            if(status != 0)
            {
                throw failure(operation, status);
            }
            return null;
        });
    }

    /**
     * Invokes a function returning a value and throws if the thread-local last status is non-zero.
     */
    private Object value(String operation, MethodHandle handle, Object... args)
    {
        return guarded(() -> {
            Object result = call(handle, withHandle(args));
            int status = SoapyNative.lastStatus();
            if(status != 0)
            {
                throw failure(operation, status);
            }
            return result;
        });
    }

    private Object structValue(String operation, MethodHandle handle, Arena arena, Object... args)
    {
        return guarded(() -> {
            Object[] all = new Object[args.length + 2];
            all[0] = arena;
            all[1] = mHandle;
            System.arraycopy(args, 0, all, 2, args.length);
            Object result = call(handle, all);
            int status = SoapyNative.lastStatus();
            if(status != 0)
            {
                throw failure(operation, status);
            }
            return result;
        });
    }

    private SoapySDRException failure(String operation, int status)
    {
        String error = SoapyNative.lastError();
        String message = mDriverKey + " " + operation + " failed" + (error.isEmpty() ? " (" + status + ")" : ": " + error);
        return new SoapySDRException(message, status);
    }

    private String string(String operation, MethodHandle handle, Object... args)
    {
        return toJavaStringAndFree((MemorySegment)value(operation, handle, args));
    }

    /**
     * Invokes a function with a trailing size_t* length argument that returns a char** array.
     */
    private List<String> strings(String operation, MethodHandle handle, Arena arena, Object... args)
    {
        MemorySegment length = arena.allocate(C_SIZE_T);
        Object[] all = append(args, length);
        MemorySegment result = (MemorySegment)value(operation, handle, all);
        return SoapyNative.toStringListAndClear(arena, result, length.get(C_SIZE_T, 0));
    }

    private List<Range> ranges(String operation, MethodHandle handle, Arena arena, Object... args)
    {
        MemorySegment length = arena.allocate(C_SIZE_T);
        MemorySegment result = (MemorySegment)value(operation, handle, append(args, length));
        List<Range> ranges = new ArrayList<>();
        for(double[] range : SoapyNative.fromRangeListAndFree(result, length.get(C_SIZE_T, 0)))
        {
            ranges.add(Range.of(range));
        }
        return Collections.unmodifiableList(ranges);
    }

    private double[] doubles(String operation, MethodHandle handle, Arena arena, Object... args)
    {
        MemorySegment length = arena.allocate(C_SIZE_T);
        MemorySegment result = (MemorySegment)value(operation, handle, append(args, length));
        return SoapyNative.toDoubleArrayAndFree(result, length.get(C_SIZE_T, 0));
    }

    private List<ArgInfo> argInfos(String operation, MethodHandle handle, Arena arena, Object... args)
    {
        MemorySegment length = arena.allocate(C_SIZE_T);
        MemorySegment result = (MemorySegment)value(operation, handle, append(args, length));
        List<ArgInfo> infos = new ArrayList<>();
        for(SoapyNative.RawArgInfo raw : SoapyNative.fromArgInfoListAndClear(result, length.get(C_SIZE_T, 0)))
        {
            infos.add(ArgInfo.of(raw));
        }
        return Collections.unmodifiableList(infos);
    }

    private static Object[] append(Object[] args, Object last)
    {
        Object[] all = new Object[args.length + 1];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = last;
        return all;
    }

    private static int dir(Direction direction)
    {
        return Objects.requireNonNull(direction, "direction").value();
    }

    private static long ch(int channel)
    {
        if(channel < 0)
        {
            throw new IllegalArgumentException("Channel must be zero or positive: " + channel);
        }
        return channel;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Identification API
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Driver key, e.g. "rtlsdr" or "hackrf".
     */
    public String getDriverKey()
    {
        return mDriverKey;
    }

    /**
     * Hardware key, a string identifying the hardware model.
     */
    public String getHardwareKey()
    {
        return string("getHardwareKey", SoapyNative.getHardwareKey);
    }

    /**
     * Additional hardware information (serial number, firmware version, etc.).
     */
    public Map<String, String> getHardwareInfo()
    {
        try(Arena arena = Arena.ofConfined())
        {
            return SoapyNative.fromKwargsAndClear((MemorySegment)structValue("getHardwareInfo",
                SoapyNative.getHardwareInfo, arena));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Channels API
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Sets the frontend mapping of available DSP units to RF frontends.
     */
    public void setFrontendMapping(Direction direction, String mapping)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("setFrontendMapping", SoapyNative.setFrontendMapping, dir(direction), toCString(arena, mapping));
        }
    }

    /**
     * Frontend mapping of available DSP units to RF frontends.
     */
    public String getFrontendMapping(Direction direction)
    {
        return string("getFrontendMapping", SoapyNative.getFrontendMapping, dir(direction));
    }

    /**
     * Number of channels in the specified direction.
     */
    public int getNumChannels(Direction direction)
    {
        return (int)(long)value("getNumChannels", SoapyNative.getNumChannels, dir(direction));
    }

    /**
     * Channel-specific information.
     */
    public Map<String, String> getChannelInfo(Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return SoapyNative.fromKwargsAndClear((MemorySegment)structValue("getChannelInfo",
                SoapyNative.getChannelInfo, arena, dir(direction), ch(channel)));
        }
    }

    /**
     * Indicates if the channel supports full duplex operation.
     */
    public boolean getFullDuplex(Direction direction, int channel)
    {
        return (boolean)value("getFullDuplex", SoapyNative.getFullDuplex, dir(direction), ch(channel));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Stream API
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Stream formats supported by the channel, e.g. ["CS16", "CF32"].
     */
    public List<String> getStreamFormats(Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return strings("getStreamFormats", SoapyNative.getStreamFormats, arena, dir(direction), ch(channel));
        }
    }

    /**
     * Native stream format of the channel and its full scale value. Using the native format avoids conversion.
     */
    public NativeStreamFormat getNativeStreamFormat(Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment fullScale = arena.allocate(C_DOUBLE);
            String format = string("getNativeStreamFormat", SoapyNative.getNativeStreamFormat, dir(direction),
                ch(channel), fullScale);
            return new NativeStreamFormat(format, fullScale.get(C_DOUBLE, 0));
        }
    }

    /**
     * Description of the optional arguments accepted by {@link #setupStream(Direction, String, int[], Map)}.
     */
    public List<ArgInfo> getStreamArgsInfo(Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return argInfos("getStreamArgsInfo", SoapyNative.getStreamArgsInfo, arena, dir(direction), ch(channel));
        }
    }

    /**
     * Creates a single-channel stream with the specified format and no stream arguments.
     */
    public Stream setupStream(Direction direction, String format, int channel)
    {
        return setupStream(direction, format, new int[]{channel}, null);
    }

    /**
     * Creates a stream. The stream must be activated before reading or writing and closed when no longer needed.
     *
     * @param direction RX or TX
     * @param format sample format, see {@link Formats}
     * @param channels channel indexes, or an empty array for channel 0
     * @param args optional driver-specific stream arguments, may be null
     */
    public Stream setupStream(Direction direction, String format, int[] channels, Map<String, String> args)
    {
        Objects.requireNonNull(format, "format");
        int[] channelList = channels == null || channels.length == 0 ? new int[]{0} : channels.clone();
        int elementSize = Formats.sizeOf(format);

        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment channelArray = arena.allocate(C_SIZE_T, channelList.length);
            for(int x = 0; x < channelList.length; x++)
            {
                channelArray.setAtIndex(C_SIZE_T, x, ch(channelList[x]));
            }

            MemorySegment handle = (MemorySegment)guarded(() -> call(SoapyNative.setupStream, mHandle,
                dir(direction), arena.allocateFrom(format), channelArray, (long)channelList.length,
                SoapyNative.toKwargs(arena, args)));

            if(handle == null || handle.equals(MemorySegment.NULL))
            {
                throw failure("setupStream", SoapyNative.lastStatus() == 0 ? -1 : SoapyNative.lastStatus());
            }

            Stream stream = new Stream(this, handle, direction, format, channelList, elementSize);
            mStreams.add(stream);
            return stream;
        }
    }

    void streamClosed(Stream stream)
    {
        mStreams.remove(stream);
    }

    /**
     * Closes a stream on the native side. Called by {@link Stream#close()}.
     */
    int closeNativeStream(MemorySegment stream)
    {
        return guarded(() -> (int)call(SoapyNative.closeStream, mHandle, stream));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Antenna API
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Available antenna names.
     */
    public List<String> listAntennas(Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return strings("listAntennas", SoapyNative.listAntennas, arena, dir(direction), ch(channel));
        }
    }

    /**
     * Selects the antenna.
     */
    public void setAntenna(Direction direction, int channel, String name)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("setAntenna", SoapyNative.setAntenna, dir(direction), ch(channel), toCString(arena, name));
        }
    }

    /**
     * Currently selected antenna.
     */
    public String getAntenna(Direction direction, int channel)
    {
        return string("getAntenna", SoapyNative.getAntenna, dir(direction), ch(channel));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Frontend corrections API
    // ---------------------------------------------------------------------------------------------------------------

    public boolean hasDCOffsetMode(Direction direction, int channel)
    {
        return (boolean)value("hasDCOffsetMode", SoapyNative.hasDCOffsetMode, dir(direction), ch(channel));
    }

    /**
     * Enables or disables automatic DC offset correction.
     */
    public void setDCOffsetMode(Direction direction, int channel, boolean automatic)
    {
        status("setDCOffsetMode", SoapyNative.setDCOffsetMode, dir(direction), ch(channel), automatic);
    }

    public boolean getDCOffsetMode(Direction direction, int channel)
    {
        return (boolean)value("getDCOffsetMode", SoapyNative.getDCOffsetMode, dir(direction), ch(channel));
    }

    public boolean hasDCOffset(Direction direction, int channel)
    {
        return (boolean)value("hasDCOffset", SoapyNative.hasDCOffset, dir(direction), ch(channel));
    }

    /**
     * Sets the DC offset correction as an I/Q pair.
     */
    public void setDCOffset(Direction direction, int channel, double offsetI, double offsetQ)
    {
        status("setDCOffset", SoapyNative.setDCOffset, dir(direction), ch(channel), offsetI, offsetQ);
    }

    /**
     * DC offset correction as an {I, Q} pair.
     */
    public double[] getDCOffset(Direction direction, int channel)
    {
        return pair("getDCOffset", SoapyNative.getDCOffset, direction, channel);
    }

    public boolean hasIQBalance(Direction direction, int channel)
    {
        return (boolean)value("hasIQBalance", SoapyNative.hasIQBalance, dir(direction), ch(channel));
    }

    /**
     * Sets the IQ balance correction as an I/Q pair.
     */
    public void setIQBalance(Direction direction, int channel, double balanceI, double balanceQ)
    {
        status("setIQBalance", SoapyNative.setIQBalance, dir(direction), ch(channel), balanceI, balanceQ);
    }

    /**
     * IQ balance correction as an {I, Q} pair.
     */
    public double[] getIQBalance(Direction direction, int channel)
    {
        return pair("getIQBalance", SoapyNative.getIQBalance, direction, channel);
    }

    public boolean hasIQBalanceMode(Direction direction, int channel)
    {
        return (boolean)value("hasIQBalanceMode", SoapyNative.hasIQBalanceMode, dir(direction), ch(channel));
    }

    public void setIQBalanceMode(Direction direction, int channel, boolean automatic)
    {
        status("setIQBalanceMode", SoapyNative.setIQBalanceMode, dir(direction), ch(channel), automatic);
    }

    public boolean getIQBalanceMode(Direction direction, int channel)
    {
        return (boolean)value("getIQBalanceMode", SoapyNative.getIQBalanceMode, dir(direction), ch(channel));
    }

    public boolean hasFrequencyCorrection(Direction direction, int channel)
    {
        return (boolean)value("hasFrequencyCorrection", SoapyNative.hasFrequencyCorrection, dir(direction),
            ch(channel));
    }

    /**
     * Sets the frequency correction in parts per million.
     */
    public void setFrequencyCorrection(Direction direction, int channel, double ppm)
    {
        status("setFrequencyCorrection", SoapyNative.setFrequencyCorrection, dir(direction), ch(channel), ppm);
    }

    /**
     * Frequency correction in parts per million.
     */
    public double getFrequencyCorrection(Direction direction, int channel)
    {
        return (double)value("getFrequencyCorrection", SoapyNative.getFrequencyCorrection, dir(direction),
            ch(channel));
    }

    private double[] pair(String operation, MethodHandle handle, Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment i = arena.allocate(C_DOUBLE);
            MemorySegment q = arena.allocate(C_DOUBLE);
            status(operation, handle, dir(direction), ch(channel), i, q);
            return new double[]{i.get(C_DOUBLE, 0), q.get(C_DOUBLE, 0)};
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Gain API
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Names of the individual amplification elements, in order from the RF to baseband.
     */
    public List<String> listGains(Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return strings("listGains", SoapyNative.listGains, arena, dir(direction), ch(channel));
        }
    }

    /**
     * Indicates if automatic gain control is supported.
     */
    public boolean hasGainMode(Direction direction, int channel)
    {
        return (boolean)value("hasGainMode", SoapyNative.hasGainMode, dir(direction), ch(channel));
    }

    /**
     * Enables or disables automatic gain control.
     */
    public void setGainMode(Direction direction, int channel, boolean automatic)
    {
        status("setGainMode", SoapyNative.setGainMode, dir(direction), ch(channel), automatic);
    }

    /**
     * Indicates if automatic gain control is enabled.
     */
    public boolean getGainMode(Direction direction, int channel)
    {
        return (boolean)value("getGainMode", SoapyNative.getGainMode, dir(direction), ch(channel));
    }

    /**
     * Sets the overall gain in dB, distributed across the gain elements by the driver.
     */
    public void setGain(Direction direction, int channel, double gain)
    {
        status("setGain", SoapyNative.setGain, dir(direction), ch(channel), gain);
    }

    /**
     * Sets the gain of a named element in dB.
     */
    public void setGain(Direction direction, int channel, String name, double gain)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("setGainElement", SoapyNative.setGainElement, dir(direction), ch(channel),
                toCString(arena, name), gain);
        }
    }

    /**
     * Overall gain in dB.
     */
    public double getGain(Direction direction, int channel)
    {
        return (double)value("getGain", SoapyNative.getGain, dir(direction), ch(channel));
    }

    /**
     * Gain of a named element in dB.
     */
    public double getGain(Direction direction, int channel, String name)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return (double)value("getGainElement", SoapyNative.getGainElement, dir(direction), ch(channel),
                toCString(arena, name));
        }
    }

    /**
     * Overall gain range in dB.
     */
    public Range getGainRange(Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return Range.of(SoapyNative.fromRange((MemorySegment)structValue("getGainRange",
                SoapyNative.getGainRange, arena, dir(direction), ch(channel))));
        }
    }

    /**
     * Gain range of a named element in dB.
     */
    public Range getGainRange(Direction direction, int channel, String name)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return Range.of(SoapyNative.fromRange((MemorySegment)structValue("getGainElementRange",
                SoapyNative.getGainElementRange, arena, dir(direction), ch(channel), toCString(arena, name))));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Frequency API
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Tunes the channel to the center frequency in Hz, distributing the tuning across components as needed.
     */
    public void setFrequency(Direction direction, int channel, double frequency)
    {
        setFrequency(direction, channel, frequency, null);
    }

    /**
     * Tunes the channel to the center frequency in Hz with optional tuning arguments (e.g. "OFFSET", "CORR").
     */
    public void setFrequency(Direction direction, int channel, double frequency, Map<String, String> args)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("setFrequency", SoapyNative.setFrequency, dir(direction), ch(channel), frequency,
                SoapyNative.toKwargs(arena, args));
        }
    }

    /**
     * Tunes a named frequency component (e.g. "RF" or "BB") in Hz.
     */
    public void setFrequency(Direction direction, int channel, String name, double frequency,
                             Map<String, String> args)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("setFrequencyComponent", SoapyNative.setFrequencyComponent, dir(direction), ch(channel),
                toCString(arena, name), frequency, SoapyNative.toKwargs(arena, args));
        }
    }

    /**
     * Overall center frequency in Hz.
     */
    public double getFrequency(Direction direction, int channel)
    {
        return (double)value("getFrequency", SoapyNative.getFrequency, dir(direction), ch(channel));
    }

    /**
     * Frequency of a named component in Hz.
     */
    public double getFrequency(Direction direction, int channel, String name)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return (double)value("getFrequencyComponent", SoapyNative.getFrequencyComponent, dir(direction),
                ch(channel), toCString(arena, name));
        }
    }

    /**
     * Names of the tunable frequency components, in order from RF to baseband.
     */
    public List<String> listFrequencies(Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return strings("listFrequencies", SoapyNative.listFrequencies, arena, dir(direction), ch(channel));
        }
    }

    /**
     * Overall tunable frequency ranges in Hz.
     */
    public List<Range> getFrequencyRange(Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return ranges("getFrequencyRange", SoapyNative.getFrequencyRange, arena, dir(direction), ch(channel));
        }
    }

    /**
     * Tunable frequency ranges of a named component in Hz.
     */
    public List<Range> getFrequencyRange(Direction direction, int channel, String name)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return ranges("getFrequencyRangeComponent", SoapyNative.getFrequencyRangeComponent, arena,
                dir(direction), ch(channel), toCString(arena, name));
        }
    }

    /**
     * Description of the optional tuning arguments accepted by setFrequency.
     */
    public List<ArgInfo> getFrequencyArgsInfo(Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return argInfos("getFrequencyArgsInfo", SoapyNative.getFrequencyArgsInfo, arena, dir(direction),
                ch(channel));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sample rate API
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Sets the baseband sample rate in samples per second.
     */
    public void setSampleRate(Direction direction, int channel, double rate)
    {
        status("setSampleRate", SoapyNative.setSampleRate, dir(direction), ch(channel), rate);
    }

    /**
     * Baseband sample rate in samples per second.
     */
    public double getSampleRate(Direction direction, int channel)
    {
        return (double)value("getSampleRate", SoapyNative.getSampleRate, dir(direction), ch(channel));
    }

    /**
     * Discrete sample rates supported by the channel (deprecated in SoapySDR in favor of
     * {@link #getSampleRateRange(Direction, int)}).
     */
    public double[] listSampleRates(Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return doubles("listSampleRates", SoapyNative.listSampleRates, arena, dir(direction), ch(channel));
        }
    }

    /**
     * Supported sample rate ranges.
     */
    public List<Range> getSampleRateRange(Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return ranges("getSampleRateRange", SoapyNative.getSampleRateRange, arena, dir(direction), ch(channel));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Bandwidth API
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Sets the baseband filter bandwidth in Hz.
     */
    public void setBandwidth(Direction direction, int channel, double bandwidth)
    {
        status("setBandwidth", SoapyNative.setBandwidth, dir(direction), ch(channel), bandwidth);
    }

    /**
     * Baseband filter bandwidth in Hz.
     */
    public double getBandwidth(Direction direction, int channel)
    {
        return (double)value("getBandwidth", SoapyNative.getBandwidth, dir(direction), ch(channel));
    }

    /**
     * Discrete bandwidths supported by the channel (deprecated in SoapySDR in favor of
     * {@link #getBandwidthRange(Direction, int)}).
     */
    public double[] listBandwidths(Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return doubles("listBandwidths", SoapyNative.listBandwidths, arena, dir(direction), ch(channel));
        }
    }

    /**
     * Supported bandwidth ranges.
     */
    public List<Range> getBandwidthRange(Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return ranges("getBandwidthRange", SoapyNative.getBandwidthRange, arena, dir(direction), ch(channel));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Clocking API
    // ---------------------------------------------------------------------------------------------------------------

    public void setMasterClockRate(double rate)
    {
        status("setMasterClockRate", SoapyNative.setMasterClockRate, rate);
    }

    public double getMasterClockRate()
    {
        return (double)value("getMasterClockRate", SoapyNative.getMasterClockRate);
    }

    public List<Range> getMasterClockRates()
    {
        try(Arena arena = Arena.ofConfined())
        {
            return ranges("getMasterClockRates", SoapyNative.getMasterClockRates, arena);
        }
    }

    public void setReferenceClockRate(double rate)
    {
        status("setReferenceClockRate", SoapyNative.setReferenceClockRate, rate);
    }

    public double getReferenceClockRate()
    {
        return (double)value("getReferenceClockRate", SoapyNative.getReferenceClockRate);
    }

    public List<Range> getReferenceClockRates()
    {
        try(Arena arena = Arena.ofConfined())
        {
            return ranges("getReferenceClockRates", SoapyNative.getReferenceClockRates, arena);
        }
    }

    public List<String> listClockSources()
    {
        try(Arena arena = Arena.ofConfined())
        {
            return strings("listClockSources", SoapyNative.listClockSources, arena);
        }
    }

    public void setClockSource(String source)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("setClockSource", SoapyNative.setClockSource, toCString(arena, source));
        }
    }

    public String getClockSource()
    {
        return string("getClockSource", SoapyNative.getClockSource);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Time API
    // ---------------------------------------------------------------------------------------------------------------

    public List<String> listTimeSources()
    {
        try(Arena arena = Arena.ofConfined())
        {
            return strings("listTimeSources", SoapyNative.listTimeSources, arena);
        }
    }

    public void setTimeSource(String source)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("setTimeSource", SoapyNative.setTimeSource, toCString(arena, source));
        }
    }

    public String getTimeSource()
    {
        return string("getTimeSource", SoapyNative.getTimeSource);
    }

    /**
     * Indicates if the device has a hardware clock. Pass null or "" for the default time source.
     */
    public boolean hasHardwareTime(String what)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return (boolean)value("hasHardwareTime", SoapyNative.hasHardwareTime, toCString(arena, what));
        }
    }

    /**
     * Hardware time in nanoseconds. Pass null or "" for the default time source.
     */
    public long getHardwareTime(String what)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return (long)value("getHardwareTime", SoapyNative.getHardwareTime, toCString(arena, what));
        }
    }

    /**
     * Sets the hardware time in nanoseconds. Pass null or "" for the default time source.
     */
    public void setHardwareTime(long timeNs, String what)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("setHardwareTime", SoapyNative.setHardwareTime, timeNs, toCString(arena, what));
        }
    }

    /**
     * Sets the time for subsequent timed commands in nanoseconds.
     */
    public void setCommandTime(long timeNs, String what)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("setCommandTime", SoapyNative.setCommandTime, timeNs, toCString(arena, what));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sensor API
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Global sensor names, e.g. "ref_locked".
     */
    public List<String> listSensors()
    {
        try(Arena arena = Arena.ofConfined())
        {
            return strings("listSensors", SoapyNative.listSensors, arena);
        }
    }

    public ArgInfo getSensorInfo(String key)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return ArgInfo.of(SoapyNative.fromArgInfoAndClear((MemorySegment)structValue("getSensorInfo",
                SoapyNative.getSensorInfo, arena, toCString(arena, key))));
        }
    }

    /**
     * Reads a global sensor value as a string.
     */
    public String readSensor(String key)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return string("readSensor", SoapyNative.readSensor, toCString(arena, key));
        }
    }

    public List<String> listSensors(Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return strings("listChannelSensors", SoapyNative.listChannelSensors, arena, dir(direction), ch(channel));
        }
    }

    public ArgInfo getSensorInfo(Direction direction, int channel, String key)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return ArgInfo.of(SoapyNative.fromArgInfoAndClear((MemorySegment)structValue("getChannelSensorInfo",
                SoapyNative.getChannelSensorInfo, arena, dir(direction), ch(channel), toCString(arena, key))));
        }
    }

    /**
     * Reads a channel sensor value as a string, e.g. "lo_locked".
     */
    public String readSensor(Direction direction, int channel, String key)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return string("readChannelSensor", SoapyNative.readChannelSensor, dir(direction), ch(channel),
                toCString(arena, key));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Register API (unsigned 32-bit values are returned as Java ints; use Integer.toUnsignedLong() as needed)
    // ---------------------------------------------------------------------------------------------------------------

    public List<String> listRegisterInterfaces()
    {
        try(Arena arena = Arena.ofConfined())
        {
            return strings("listRegisterInterfaces", SoapyNative.listRegisterInterfaces, arena);
        }
    }

    public void writeRegister(String name, int address, int value)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("writeRegister", SoapyNative.writeRegister, toCString(arena, name), address, value);
        }
    }

    public int readRegister(String name, int address)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return (int)value("readRegister", SoapyNative.readRegister, toCString(arena, name), address);
        }
    }

    public void writeRegisters(String name, int address, int[] values)
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment array = arena.allocateFrom(C_INT, values);
            status("writeRegisters", SoapyNative.writeRegisters, toCString(arena, name), address, array,
                (long)values.length);
        }
    }

    public int[] readRegisters(String name, int address, int length)
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment lengthSegment = arena.allocate(C_SIZE_T);
            lengthSegment.set(C_SIZE_T, 0, length);
            MemorySegment result = (MemorySegment)value("readRegisters", SoapyNative.readRegisters,
                toCString(arena, name), address, lengthSegment);
            long count = lengthSegment.get(C_SIZE_T, 0);
            if(result.equals(MemorySegment.NULL))
            {
                return new int[0];
            }
            try
            {
                return result.reinterpret(count * C_INT.byteSize()).toArray(C_INT);
            }
            finally
            {
                call(SoapyNative.free, result);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Settings API
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Description of the device-wide settings supported by the driver.
     */
    public List<ArgInfo> getSettingInfo()
    {
        try(Arena arena = Arena.ofConfined())
        {
            return argInfos("getSettingInfo", SoapyNative.getSettingInfo, arena);
        }
    }

    public void writeSetting(String key, String value)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("writeSetting", SoapyNative.writeSetting, toCString(arena, key), toCString(arena, value));
        }
    }

    public String readSetting(String key)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return string("readSetting", SoapyNative.readSetting, toCString(arena, key));
        }
    }

    /**
     * Description of the channel-specific settings supported by the driver.
     */
    public List<ArgInfo> getSettingInfo(Direction direction, int channel)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return argInfos("getChannelSettingInfo", SoapyNative.getChannelSettingInfo, arena, dir(direction),
                ch(channel));
        }
    }

    public void writeSetting(Direction direction, int channel, String key, String value)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("writeChannelSetting", SoapyNative.writeChannelSetting, dir(direction), ch(channel),
                toCString(arena, key), toCString(arena, value));
        }
    }

    public String readSetting(Direction direction, int channel, String key)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return string("readChannelSetting", SoapyNative.readChannelSetting, dir(direction), ch(channel),
                toCString(arena, key));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // GPIO API
    // ---------------------------------------------------------------------------------------------------------------

    public List<String> listGPIOBanks()
    {
        try(Arena arena = Arena.ofConfined())
        {
            return strings("listGPIOBanks", SoapyNative.listGPIOBanks, arena);
        }
    }

    public void writeGPIO(String bank, int value)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("writeGPIO", SoapyNative.writeGPIO, toCString(arena, bank), value);
        }
    }

    public void writeGPIO(String bank, int value, int mask)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("writeGPIOMasked", SoapyNative.writeGPIOMasked, toCString(arena, bank), value, mask);
        }
    }

    public int readGPIO(String bank)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return (int)value("readGPIO", SoapyNative.readGPIO, toCString(arena, bank));
        }
    }

    public void writeGPIODir(String bank, int direction)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("writeGPIODir", SoapyNative.writeGPIODir, toCString(arena, bank), direction);
        }
    }

    public void writeGPIODir(String bank, int direction, int mask)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("writeGPIODirMasked", SoapyNative.writeGPIODirMasked, toCString(arena, bank), direction, mask);
        }
    }

    public int readGPIODir(String bank)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return (int)value("readGPIODir", SoapyNative.readGPIODir, toCString(arena, bank));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // I2C, SPI and UART API
    // ---------------------------------------------------------------------------------------------------------------

    public void writeI2C(int address, byte[] data)
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment buffer = arena.allocateFrom(ValueLayout.JAVA_BYTE, data);
            status("writeI2C", SoapyNative.writeI2C, address, buffer, (long)data.length);
        }
    }

    public byte[] readI2C(int address, int numBytes)
    {
        try(Arena arena = Arena.ofConfined())
        {
            MemorySegment count = arena.allocate(C_SIZE_T);
            count.set(C_SIZE_T, 0, numBytes);
            MemorySegment result = (MemorySegment)value("readI2C", SoapyNative.readI2C, address, count);
            if(result.equals(MemorySegment.NULL))
            {
                return new byte[0];
            }
            try
            {
                return result.reinterpret(count.get(C_SIZE_T, 0)).toArray(ValueLayout.JAVA_BYTE);
            }
            finally
            {
                call(SoapyNative.free, result);
            }
        }
    }

    /**
     * Performs an SPI transaction, writing numBits of data and returning the bits read back.
     */
    public int transactSPI(int address, int data, int numBits)
    {
        return (int)value("transactSPI", SoapyNative.transactSPI, address, data, (long)numBits);
    }

    public List<String> listUARTs()
    {
        try(Arena arena = Arena.ofConfined())
        {
            return strings("listUARTs", SoapyNative.listUARTs, arena);
        }
    }

    public void writeUART(String which, String data)
    {
        try(Arena arena = Arena.ofConfined())
        {
            status("writeUART", SoapyNative.writeUART, toCString(arena, which), toCString(arena, data));
        }
    }

    public String readUART(String which, long timeoutUs)
    {
        try(Arena arena = Arena.ofConfined())
        {
            return string("readUART", SoapyNative.readUART, toCString(arena, which), timeoutUs);
        }
    }

    /**
     * Driver-specific native handle of the underlying device (e.g. the rtlsdr_dev_t pointer), or NULL.
     */
    public MemorySegment getNativeDeviceHandle()
    {
        return (MemorySegment)value("getNativeDeviceHandle", SoapyNative.getNativeDeviceHandle);
    }

    @Override
    public String toString()
    {
        return "Device[" + mDriverKey + (isClosed() ? ", closed" : "") + "]";
    }
}
