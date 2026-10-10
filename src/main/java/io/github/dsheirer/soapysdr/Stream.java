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
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static io.github.dsheirer.soapysdr.internal.SoapyNative.C_INT;
import static io.github.dsheirer.soapysdr.internal.SoapyNative.C_LONG_LONG;
import static io.github.dsheirer.soapysdr.internal.SoapyNative.C_POINTER;
import static io.github.dsheirer.soapysdr.internal.SoapyNative.C_SIZE_T;
import static io.github.dsheirer.soapysdr.internal.SoapyNative.call;

/**
 * A receive or transmit sample stream (SoapySDRStream) created by
 * {@link Device#setupStream(Direction, String, int[], java.util.Map)}.
 *
 * <p>Typical receive loop:</p>
 * <pre>{@code
 * try(Stream stream = device.setupStream(Direction.RX, Formats.CF32, 0))
 * {
 *     float[] samples = new float[stream.getMTU() * 2]; // interleaved I/Q
 *     stream.activate();
 *     while(running)
 *     {
 *         StreamResult result = stream.read(samples, 100_000);
 *         if(result.isSuccess())
 *         {
 *             process(samples, result.elements());
 *         }
 *         else if(result.errorCode() != ErrorCode.TIMEOUT && result.errorCode() != ErrorCode.OVERFLOW)
 *         {
 *             throw new SoapySDRException("Stream error: " + result, result.status());
 *         }
 *     }
 *     stream.deactivate();
 * }
 * }</pre>
 *
 * <p>Two transfer styles are offered:</p>
 * <ul>
 *     <li>Java array methods ({@code read(float[])}, {@code write(short[])}, ...) for single-channel streams. The
 *     stream keeps an internal native buffer and copies to or from the array, so no per-call allocation occurs.</li>
 *     <li>{@link MemorySegment} methods for zero-copy and multi-channel use. Supply one native segment per channel,
 *     for example allocated from an {@link Arena} owned by the caller.</li>
 * </ul>
 *
 * <p>Read and write calls return a {@link StreamResult} rather than throwing, because timeouts and overflows are
 * routine during streaming. A stream should be read or written by one thread at a time; control calls such as
 * {@link #close()} wait for an in-progress transfer to complete.</p>
 */
public final class Stream implements AutoCloseable
{
    private final Device mDevice;
    private final MemorySegment mDeviceHandle;
    private final MemorySegment mHandle;
    private final Direction mDirection;
    private final String mFormat;
    private final int[] mChannels;
    private final int mElementSize;
    private final Arena mArena = Arena.ofShared();
    private final MemorySegment mBufferPointers;
    private final MemorySegment mFlags;
    private final MemorySegment mTimeNs;
    private final MemorySegment mChannelMask;
    private final MemorySegment mStatusFlags;
    private final MemorySegment mStatusTimeNs;
    private final ReentrantReadWriteLock mLock = new ReentrantReadWriteLock();
    private MemorySegment mScratch = MemorySegment.NULL;
    private boolean mActive;
    private boolean mClosed;

    Stream(Device device, MemorySegment handle, Direction direction, String format, int[] channels, int elementSize)
    {
        mDevice = device;
        mDeviceHandle = device.nativeHandle();
        mHandle = handle;
        mDirection = direction;
        mFormat = format;
        mChannels = channels;
        mElementSize = elementSize;
        mBufferPointers = mArena.allocate(C_POINTER, channels.length);
        mFlags = mArena.allocate(C_INT);
        mTimeNs = mArena.allocate(C_LONG_LONG);
        mChannelMask = mArena.allocate(C_SIZE_T);
        mStatusFlags = mArena.allocate(C_INT);
        mStatusTimeNs = mArena.allocate(C_LONG_LONG);
    }

    public Device getDevice()
    {
        return mDevice;
    }

    public Direction getDirection()
    {
        return mDirection;
    }

    public String getFormat()
    {
        return mFormat;
    }

    /**
     * Channel indexes carried by this stream.
     */
    public int[] getChannels()
    {
        return mChannels.clone();
    }

    /**
     * Number of channels carried by this stream.
     */
    public int getNumChannels()
    {
        return mChannels.length;
    }

    /**
     * Size of one element (one sample, complex or real) in bytes.
     */
    public int getElementSize()
    {
        return mElementSize;
    }

    /**
     * Raw native SoapySDRStream pointer. Valid only until {@link #close()}.
     */
    public MemorySegment nativeHandle()
    {
        return mHandle;
    }

    public boolean isActive()
    {
        mLock.readLock().lock();
        try
        {
            return mActive;
        }
        finally
        {
            mLock.readLock().unlock();
        }
    }

    public boolean isClosed()
    {
        mLock.readLock().lock();
        try
        {
            return mClosed;
        }
        finally
        {
            mLock.readLock().unlock();
        }
    }

    private void checkOpen()
    {
        if(mClosed)
        {
            throw new IllegalStateException("Stream has been closed");
        }
    }

    /**
     * Maximum transmission unit: the number of elements the driver transfers per packet. Read and write calls
     * that are a multiple of the MTU are typically most efficient.
     */
    public int getMTU()
    {
        mLock.readLock().lock();
        try
        {
            checkOpen();
            long mtu = (long)call(SoapyNative.getStreamMTU, mDeviceHandle, mHandle);
            if(SoapyNative.lastStatus() != 0)
            {
                throw new SoapySDRException("getStreamMTU failed: " + SoapyNative.lastError(), SoapyNative.lastStatus());
            }
            return (int)Math.min(mtu, Integer.MAX_VALUE);
        }
        finally
        {
            mLock.readLock().unlock();
        }
    }

    /**
     * Activates the stream immediately and continuously.
     */
    public void activate()
    {
        activate(0, 0, 0);
    }

    /**
     * Activates the stream.
     *
     * @param flags optional {@link StreamFlags}, e.g. HAS_TIME for a timed start
     * @param timeNs activation time in nanoseconds when HAS_TIME is set
     * @param numElems number of elements for a burst, or zero for continuous streaming
     */
    public void activate(int flags, long timeNs, int numElems)
    {
        mLock.writeLock().lock();
        try
        {
            checkOpen();
            int status = (int)call(SoapyNative.activateStream, mDeviceHandle, mHandle, flags, timeNs, (long)numElems);
            if(status != 0)
            {
                throw streamFailure("activateStream", status);
            }
            mActive = true;
        }
        finally
        {
            mLock.writeLock().unlock();
        }
    }

    /**
     * Deactivates the stream immediately.
     */
    public void deactivate()
    {
        deactivate(0, 0);
    }

    /**
     * Deactivates the stream.
     *
     * @param flags optional {@link StreamFlags}, e.g. HAS_TIME for a timed stop
     * @param timeNs deactivation time in nanoseconds when HAS_TIME is set
     */
    public void deactivate(int flags, long timeNs)
    {
        mLock.writeLock().lock();
        try
        {
            checkOpen();
            int status = (int)call(SoapyNative.deactivateStream, mDeviceHandle, mHandle, flags, timeNs);
            if(status != 0)
            {
                throw streamFailure("deactivateStream", status);
            }
            mActive = false;
        }
        finally
        {
            mLock.writeLock().unlock();
        }
    }

    private SoapySDRException streamFailure(String operation, int status)
    {
        String error = SoapyNative.lastError();
        if(error.isEmpty())
        {
            error = SoapySDR.errorToString(status);
        }
        return new SoapySDRException(mDevice.getDriverKey() + " " + operation + " failed: " + error, status);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Memory segment transfers
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Reads up to numElems elements into one native buffer per channel.
     *
     * @param buffers one segment per channel, each at least numElems * element size bytes
     * @param numElems maximum number of elements to read per channel
     * @param timeoutUs timeout in microseconds
     */
    public StreamResult read(MemorySegment[] buffers, int numElems, long timeoutUs)
    {
        mLock.readLock().lock();
        try
        {
            checkOpen();
            checkDirection(Direction.RX);
            setBufferPointers(buffers, numElems);
            return readInternal(numElems, timeoutUs);
        }
        finally
        {
            mLock.readLock().unlock();
        }
    }

    /**
     * Reads up to numElems elements from a single-channel stream into a native buffer.
     */
    public StreamResult read(MemorySegment buffer, int numElems, long timeoutUs)
    {
        return read(new MemorySegment[]{buffer}, numElems, timeoutUs);
    }

    /**
     * Writes up to numElems elements from one native buffer per channel.
     *
     * @param buffers one segment per channel, each at least numElems * element size bytes
     * @param numElems number of elements to write per channel
     * @param flags input {@link StreamFlags}, e.g. END_BURST or HAS_TIME
     * @param timeNs transmit time in nanoseconds when HAS_TIME is set
     * @param timeoutUs timeout in microseconds
     */
    public StreamResult write(MemorySegment[] buffers, int numElems, int flags, long timeNs, long timeoutUs)
    {
        mLock.readLock().lock();
        try
        {
            checkOpen();
            checkDirection(Direction.TX);
            setBufferPointers(buffers, numElems);
            return writeInternal(numElems, flags, timeNs, timeoutUs);
        }
        finally
        {
            mLock.readLock().unlock();
        }
    }

    /**
     * Writes up to numElems elements to a single-channel stream from a native buffer.
     */
    public StreamResult write(MemorySegment buffer, int numElems, long timeoutUs)
    {
        return write(new MemorySegment[]{buffer}, numElems, 0, 0, timeoutUs);
    }

    /**
     * Reads stream status events such as underflows, late packets and end of burst acknowledgements. Most useful for
     * transmit streams.
     */
    public StreamResult readStatus(long timeoutUs)
    {
        mLock.readLock().lock();
        try
        {
            checkOpen();
            mStatusFlags.set(C_INT, 0, 0);
            mStatusTimeNs.set(C_LONG_LONG, 0, 0);
            mChannelMask.set(C_SIZE_T, 0, 0);
            int status = (int)SoapyNative.readStreamStatus.invokeExact(mDeviceHandle, mHandle, mChannelMask,
                mStatusFlags, mStatusTimeNs, timeoutUs);
            return new StreamResult(status, mStatusFlags.get(C_INT, 0), mStatusTimeNs.get(C_LONG_LONG, 0),
                mChannelMask.get(C_SIZE_T, 0));
        }
        catch(RuntimeException | Error e)
        {
            throw e;
        }
        catch(Throwable t)
        {
            throw new IllegalStateException(t);
        }
        finally
        {
            mLock.readLock().unlock();
        }
    }

    private void checkDirection(Direction expected)
    {
        if(mDirection != expected)
        {
            throw new IllegalStateException("Operation requires a " + expected + " stream but this is a " +
                mDirection + " stream");
        }
    }

    private void setBufferPointers(MemorySegment[] buffers, int numElems)
    {
        Objects.requireNonNull(buffers, "buffers");
        if(buffers.length != mChannels.length)
        {
            throw new IllegalArgumentException("Expected " + mChannels.length + " buffers (one per channel) but got " +
                buffers.length);
        }
        if(numElems < 0)
        {
            throw new IllegalArgumentException("numElems must be zero or positive");
        }
        long required = (long)numElems * mElementSize;
        for(int x = 0; x < buffers.length; x++)
        {
            MemorySegment buffer = buffers[x];
            if(!buffer.isNative())
            {
                throw new IllegalArgumentException("Buffer " + x + " must be a native memory segment");
            }
            if(buffer.byteSize() < required)
            {
                throw new IllegalArgumentException("Buffer " + x + " holds " + buffer.byteSize() + " bytes but " +
                    required + " bytes are required for " + numElems + " " + mFormat + " elements");
            }
            mBufferPointers.setAtIndex(C_POINTER, x, buffer);
        }
    }

    private StreamResult readInternal(int numElems, long timeoutUs)
    {
        try
        {
            mFlags.set(C_INT, 0, 0);
            mTimeNs.set(C_LONG_LONG, 0, 0);
            int status = (int)SoapyNative.readStream.invokeExact(mDeviceHandle, mHandle, mBufferPointers,
                (long)numElems, mFlags, mTimeNs, timeoutUs);
            return new StreamResult(status, mFlags.get(C_INT, 0), mTimeNs.get(C_LONG_LONG, 0), 0);
        }
        catch(RuntimeException | Error e)
        {
            throw e;
        }
        catch(Throwable t)
        {
            throw new IllegalStateException(t);
        }
    }

    private StreamResult writeInternal(int numElems, int flags, long timeNs, long timeoutUs)
    {
        try
        {
            mFlags.set(C_INT, 0, flags);
            int status = (int)SoapyNative.writeStream.invokeExact(mDeviceHandle, mHandle, mBufferPointers,
                (long)numElems, mFlags, timeNs, timeoutUs);
            return new StreamResult(status, mFlags.get(C_INT, 0), timeNs, 0);
        }
        catch(RuntimeException | Error e)
        {
            throw e;
        }
        catch(Throwable t)
        {
            throw new IllegalStateException(t);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Java array transfers (single channel)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Reads samples into a float array, e.g. interleaved I/Q for {@link Formats#CF32} or real samples for
     * {@link Formats#F32}. The number of elements requested is derived from the array length.
     *
     * @return result whose {@link StreamResult#elements()} is the number of elements (not array entries) read
     */
    public StreamResult read(float[] samples, long timeoutUs)
    {
        return readArray(samples, samples.length, ValueLayout.JAVA_FLOAT, timeoutUs);
    }

    /**
     * Reads samples into a short array, e.g. interleaved I/Q for {@link Formats#CS16}.
     */
    public StreamResult read(short[] samples, long timeoutUs)
    {
        return readArray(samples, samples.length, ValueLayout.JAVA_SHORT, timeoutUs);
    }

    /**
     * Reads samples into a byte array, e.g. interleaved I/Q for {@link Formats#CS8} or {@link Formats#CU8}.
     */
    public StreamResult read(byte[] samples, long timeoutUs)
    {
        return readArray(samples, samples.length, ValueLayout.JAVA_BYTE, timeoutUs);
    }

    /**
     * Reads samples into a double array, e.g. interleaved I/Q for {@link Formats#CF64}.
     */
    public StreamResult read(double[] samples, long timeoutUs)
    {
        return readArray(samples, samples.length, ValueLayout.JAVA_DOUBLE, timeoutUs);
    }

    /**
     * Reads samples into an int array, e.g. interleaved I/Q for {@link Formats#CS32}.
     */
    public StreamResult read(int[] samples, long timeoutUs)
    {
        return readArray(samples, samples.length, ValueLayout.JAVA_INT, timeoutUs);
    }

    /**
     * Writes samples from a float array. The number of elements written is derived from the array length.
     */
    public StreamResult write(float[] samples, int flags, long timeNs, long timeoutUs)
    {
        return writeArray(samples, samples.length, ValueLayout.JAVA_FLOAT, flags, timeNs, timeoutUs);
    }

    public StreamResult write(short[] samples, int flags, long timeNs, long timeoutUs)
    {
        return writeArray(samples, samples.length, ValueLayout.JAVA_SHORT, flags, timeNs, timeoutUs);
    }

    public StreamResult write(byte[] samples, int flags, long timeNs, long timeoutUs)
    {
        return writeArray(samples, samples.length, ValueLayout.JAVA_BYTE, flags, timeNs, timeoutUs);
    }

    public StreamResult write(double[] samples, int flags, long timeNs, long timeoutUs)
    {
        return writeArray(samples, samples.length, ValueLayout.JAVA_DOUBLE, flags, timeNs, timeoutUs);
    }

    public StreamResult write(int[] samples, int flags, long timeNs, long timeoutUs)
    {
        return writeArray(samples, samples.length, ValueLayout.JAVA_INT, flags, timeNs, timeoutUs);
    }

    private int elementsFor(int arrayLength, ValueLayout layout)
    {
        if(mChannels.length != 1)
        {
            throw new IllegalStateException("Array transfers support single-channel streams only; use the " +
                "MemorySegment[] methods for " + mChannels.length + " channels");
        }
        long bytes = (long)arrayLength * layout.byteSize();
        return (int)(bytes / mElementSize);
    }

    private MemorySegment scratch(long bytes)
    {
        if(mScratch.byteSize() < bytes)
        {
            // Old scratch buffers remain owned by the stream arena and are released on close
            mScratch = mArena.allocate(bytes, 64);
        }
        return mScratch;
    }

    private StreamResult readArray(Object array, int length, ValueLayout layout, long timeoutUs)
    {
        mLock.readLock().lock();
        try
        {
            checkOpen();
            checkDirection(Direction.RX);
            int numElems = elementsFor(length, layout);
            MemorySegment buffer;
            synchronized(this)
            {
                buffer = scratch((long)numElems * mElementSize);
            }
            mBufferPointers.setAtIndex(C_POINTER, 0, buffer);
            StreamResult result = readInternal(numElems, timeoutUs);
            if(result.status() > 0)
            {
                long bytes = (long)result.status() * mElementSize;
                MemorySegment.copy(buffer, layout, 0, array, 0, (int)(bytes / layout.byteSize()));
            }
            return result;
        }
        finally
        {
            mLock.readLock().unlock();
        }
    }

    private StreamResult writeArray(Object array, int length, ValueLayout layout, int flags, long timeNs,
                                    long timeoutUs)
    {
        mLock.readLock().lock();
        try
        {
            checkOpen();
            checkDirection(Direction.TX);
            int numElems = elementsFor(length, layout);
            MemorySegment buffer;
            synchronized(this)
            {
                buffer = scratch((long)numElems * mElementSize);
            }
            MemorySegment.copy(array, 0, buffer, layout, 0, (int)((long)numElems * mElementSize / layout.byteSize()));
            mBufferPointers.setAtIndex(C_POINTER, 0, buffer);
            return writeInternal(numElems, flags, timeNs, timeoutUs);
        }
        finally
        {
            mLock.readLock().unlock();
        }
    }

    /**
     * Deactivates (if active) and closes the stream, releasing native resources. Calling close more than once has no
     * effect.
     */
    @Override
    public void close()
    {
        mLock.writeLock().lock();
        try
        {
            if(mClosed)
            {
                return;
            }
            mClosed = true;

            int status = 0;
            if(!mDevice.isClosed())
            {
                if(mActive)
                {
                    call(SoapyNative.deactivateStream, mDeviceHandle, mHandle, 0, 0L);
                    mActive = false;
                }
                status = mDevice.closeNativeStream(mHandle);
            }

            mArena.close();
            mDevice.streamClosed(this);

            if(status != 0)
            {
                throw streamFailure("closeStream", status);
            }
        }
        finally
        {
            mLock.writeLock().unlock();
        }
    }

    @Override
    public String toString()
    {
        return "Stream[" + mDirection + ", " + mFormat + ", channels=" + java.util.Arrays.toString(mChannels) +
            (mClosed ? ", closed" : mActive ? ", active" : "") + "]";
    }
}
