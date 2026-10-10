/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests against the deterministic jsoapytest driver module built from src/test/native.
 */
class TestDriverTest
{
    private Device mDevice;

    @BeforeEach
    void open()
    {
        NativeSupport.requireTestDriver();
        mDevice = SoapySDR.open("driver=jsoapytest");
    }

    @AfterEach
    void close()
    {
        if(mDevice != null)
        {
            mDevice.close();
        }
    }

    @Test
    void enumerateAndIdentify()
    {
        List<Map<String, String>> devices = SoapySDR.enumerate(Map.of("driver", "jsoapytest"));
        assertEquals(1, devices.size());
        assertEquals("jSoapySDR test device", devices.get(0).get("label"));

        try(Device byMap = SoapySDR.open(devices.get(0)))
        {
            assertEquals("jsoapytest", byMap.getDriverKey());
        }

        assertEquals("jsoapytest", mDevice.getDriverKey());
        assertEquals("jsoapytest-hw", mDevice.getHardwareKey());
        assertEquals(Map.of("serial", "0001", "firmware", "1.2.3"), mDevice.getHardwareInfo());
        assertEquals(2, mDevice.getNumChannels(Direction.RX));
        assertEquals(1, mDevice.getNumChannels(Direction.TX));
    }

    @Test
    void driverExceptionDuringOpen()
    {
        SoapySDRException e = assertThrows(SoapySDRException.class,
            () -> SoapySDR.open("driver=jsoapytest,fail=on purpose"));
        assertTrue(e.getMessage().contains("requested failure: on purpose"), e.getMessage());
    }

    @Test
    void streamFormats()
    {
        assertEquals(List.of("CF32", "CS16"), mDevice.getStreamFormats(Direction.RX, 0));
        assertEquals(new NativeStreamFormat("CS16", 32768), mDevice.getNativeStreamFormat(Direction.RX, 0));
    }

    @Test
    void antennas()
    {
        assertEquals(List.of("RX", "TX/RX"), mDevice.listAntennas(Direction.RX, 0));
        mDevice.setAntenna(Direction.RX, 1, "TX/RX");
        assertEquals("TX/RX", mDevice.getAntenna(Direction.RX, 1));
        SoapySDRException e = assertThrows(SoapySDRException.class,
            () -> mDevice.setAntenna(Direction.RX, 0, "nope"));
        assertTrue(e.getMessage().contains("unknown antenna nope"), e.getMessage());
    }

    @Test
    void gains()
    {
        assertEquals(List.of("LNA", "VGA"), mDevice.listGains(Direction.RX, 0));
        assertEquals(new Range(0, 40, 1), mDevice.getGainRange(Direction.RX, 0, "LNA"));
        assertEquals(new Range(0, 60, 2), mDevice.getGainRange(Direction.RX, 0, "VGA"));

        mDevice.setGain(Direction.RX, 0, "LNA", 24);
        assertEquals(24, mDevice.getGain(Direction.RX, 0, "LNA"));
        assertThrows(SoapySDRException.class, () -> mDevice.setGain(Direction.RX, 0, "LNA", 99));
        assertThrows(SoapySDRException.class, () -> mDevice.getGainRange(Direction.RX, 0, "BOGUS"));

        assertTrue(mDevice.hasGainMode(Direction.RX, 0));
        mDevice.setGainMode(Direction.RX, 0, true);
        assertTrue(mDevice.getGainMode(Direction.RX, 0));
    }

    @Test
    void frequency()
    {
        assertEquals(List.of("RF"), mDevice.listFrequencies(Direction.RX, 0));
        assertEquals(List.of(new Range(1e6, 6e9, 0)), mDevice.getFrequencyRange(Direction.RX, 0));

        mDevice.setFrequency(Direction.RX, 0, 162.55e6);
        assertEquals(162.55e6, mDevice.getFrequency(Direction.RX, 0));

        mDevice.setFrequency(Direction.RX, 0, 450e6, Map.of("OFFSET", "250000"));
        assertEquals("250000", mDevice.readSetting("last_tune_offset"));

        assertThrows(SoapySDRException.class, () -> mDevice.setFrequency(Direction.RX, 0, 10));
    }

    @Test
    void sampleRateAndBandwidth()
    {
        mDevice.setSampleRate(Direction.RX, 0, 2.4e6);
        assertEquals(2.4e6, mDevice.getSampleRate(Direction.RX, 0));
        SoapySDRException e = assertThrows(SoapySDRException.class,
            () -> mDevice.setSampleRate(Direction.RX, 0, -1));
        assertTrue(e.getMessage().contains("invalid sample rate"), e.getMessage());

        assertEquals(List.of(new Range(250e3, 250e3, 0), new Range(1e6, 3.2e6, 0.1e6)),
            mDevice.getSampleRateRange(Direction.RX, 0));
        assertArrayEquals(new double[]{1.5e6, 5e6, 10e6}, mDevice.listBandwidths(Direction.RX, 0));
    }

    @Test
    void corrections()
    {
        assertTrue(mDevice.hasFrequencyCorrection(Direction.RX, 0));
        mDevice.setFrequencyCorrection(Direction.RX, 0, 1.25);
        assertEquals(1.25, mDevice.getFrequencyCorrection(Direction.RX, 0));

        mDevice.setDCOffsetMode(Direction.RX, 0, true);
        assertTrue(mDevice.getDCOffsetMode(Direction.RX, 0));
        mDevice.setDCOffset(Direction.RX, 0, 0.1, -0.2);
        assertArrayEquals(new double[]{0.1, -0.2}, mDevice.getDCOffset(Direction.RX, 0));
    }

    @Test
    void sensorsSettingsRegisters()
    {
        assertEquals(List.of("temperature"), mDevice.listSensors());
        assertEquals("42.5", mDevice.readSensor("temperature"));
        ArgInfo sensor = mDevice.getSensorInfo("temperature");
        assertEquals("Board temperature", sensor.displayName());
        assertEquals("C", sensor.units());
        assertEquals(ArgType.FLOAT, sensor.type());
        assertEquals(new Range(-40, 125, 0), sensor.range());

        List<ArgInfo> settings = mDevice.getSettingInfo();
        assertEquals(2, settings.size());
        assertEquals("biastee", settings.get(0).key());
        assertEquals(ArgType.BOOL, settings.get(0).type());
        assertEquals("Enable antenna port bias tee", settings.get(0).description());
        assertEquals(List.of("normal", "lowpower"), settings.get(1).options());
        assertEquals(List.of("Normal", "Low power"), settings.get(1).optionNames());

        mDevice.writeSetting("biastee", "true");
        assertEquals("true", mDevice.readSetting("biastee"));

        mDevice.writeRegister("", 0x10, 0xDEADBEEF);
        assertEquals(0xDEADBEEF, mDevice.readRegister("", 0x10));
    }

    @Test
    void readFloatArray()
    {
        try(Stream stream = mDevice.setupStream(Direction.RX, Formats.CF32, 0))
        {
            assertEquals(1024, stream.getMTU());
            assertEquals(8, stream.getElementSize());

            float[] samples = new float[512 * 2];
            stream.activate();
            assertTrue(stream.isActive());

            StreamResult first = stream.read(samples, 100_000);
            assertTrue(first.isSuccess(), first.toString());
            assertEquals(512, first.elements());
            assertTrue(first.hasTime());
            assertEquals(0, first.timeNs());
            assertEquals(0f, samples[0]);
            assertEquals(511f, samples[1022]);
            assertEquals(-511f, samples[1023]);

            StreamResult second = stream.read(samples, 100_000);
            assertEquals(512, second.elements());
            assertEquals(512_000, second.timeNs());
            assertEquals(512f, samples[0]);
        }
    }

    @Test
    void readShortArrayCappedAtMtu()
    {
        try(Stream stream = mDevice.setupStream(Direction.RX, Formats.CS16, 0))
        {
            short[] samples = new short[4096 * 2];
            stream.activate();
            StreamResult result = stream.read(samples, 100_000);
            assertEquals(1024, result.elements());
            assertEquals((short)1023, samples[2046]);
            assertEquals(0, samples[2048]); // untouched beyond the elements read
        }
    }

    @Test
    void readMultiChannelSegments()
    {
        try(Arena arena = Arena.ofConfined();
            Stream stream = mDevice.setupStream(Direction.RX, Formats.CF32, new int[]{0, 1}, null))
        {
            int elements = 256;
            MemorySegment ch0 = arena.allocate((long)elements * stream.getElementSize());
            MemorySegment ch1 = arena.allocate((long)elements * stream.getElementSize());

            stream.activate();
            StreamResult result = stream.read(new MemorySegment[]{ch0, ch1}, elements, 100_000);
            assertEquals(elements, result.elements());

            assertEquals(10f, ch0.getAtIndex(ValueLayout.JAVA_FLOAT, 20));
            assertEquals(-10f, ch0.getAtIndex(ValueLayout.JAVA_FLOAT, 21));
            assertEquals(10f, ch1.getAtIndex(ValueLayout.JAVA_FLOAT, 20));
            assertEquals(990f, ch1.getAtIndex(ValueLayout.JAVA_FLOAT, 21));

            // Array convenience methods are single-channel only
            assertThrows(IllegalStateException.class, () -> stream.read(new float[8], 1000));
            // Buffer count must match channel count
            assertThrows(IllegalArgumentException.class, () -> stream.read(ch0, elements, 1000));
        }
    }

    @Test
    void undersizedBufferRejected()
    {
        try(Arena arena = Arena.ofConfined(); Stream stream = mDevice.setupStream(Direction.RX, Formats.CF32, 0))
        {
            stream.activate();
            MemorySegment small = arena.allocate(16);
            assertThrows(IllegalArgumentException.class, () -> stream.read(small, 100, 1000));
        }
    }

    @Test
    void inactiveReadReturnsError()
    {
        try(Stream stream = mDevice.setupStream(Direction.RX, Formats.CF32, 0))
        {
            StreamResult result = stream.read(new float[16], 1000);
            assertFalse(result.isSuccess());
            assertEquals(ErrorCode.STREAM_ERROR, result.errorCode());
        }
    }

    @Test
    void writeAndStatus()
    {
        try(Stream stream = mDevice.setupStream(Direction.TX, Formats.CS16, 0))
        {
            short[] samples = new short[8];
            for(int x = 0; x < 4; x++)
            {
                samples[2 * x] = (short)(x + 1); // I = 1,2,3,4
            }
            stream.activate();
            StreamResult result = stream.write(samples, StreamFlags.END_BURST, 0, 100_000);
            assertEquals(4, result.elements());
            assertEquals("10", mDevice.readSetting("last_write_sum"));
            assertEquals(String.valueOf(StreamFlags.END_BURST), mDevice.readSetting("last_write_flags"));

            StreamResult status = stream.readStatus(1000);
            assertEquals(ErrorCode.UNDERFLOW, status.errorCode());
            assertEquals(1, status.channelMask());
            assertTrue(status.isEndOfBurst());
            assertEquals(123456789L, status.timeNs());

            assertThrows(IllegalStateException.class, () -> stream.read(new short[8], 1000));
        }
    }

    @Test
    void streamSetupErrors()
    {
        SoapySDRException e = assertThrows(SoapySDRException.class,
            () -> mDevice.setupStream(Direction.RX, Formats.CU8, 0));
        assertTrue(e.getMessage().contains("unsupported format CU8"), e.getMessage());
        assertThrows(SoapySDRException.class, () -> mDevice.setupStream(Direction.TX, Formats.CF32, 3));
    }

    @Test
    void closingDeviceClosesStreams()
    {
        Stream stream = mDevice.setupStream(Direction.RX, Formats.CF32, 0);
        stream.activate();
        mDevice.close();
        assertTrue(stream.isClosed());
        assertThrows(IllegalStateException.class, () -> stream.read(new float[8], 1000));
        stream.close(); // idempotent
    }

    @Test
    void concurrentControlWhileStreaming() throws Exception
    {
        try(Stream stream = mDevice.setupStream(Direction.RX, Formats.CF32, 0))
        {
            stream.activate();
            java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
            Thread reader = Thread.ofPlatform().start(() -> {
                float[] buffer = new float[2048];
                for(int x = 0; x < 2000; x++)
                {
                    if(!stream.read(buffer, 100_000).isSuccess())
                    {
                        failures.incrementAndGet();
                    }
                }
            });
            for(int x = 0; x < 2000; x++)
            {
                mDevice.setFrequency(Direction.RX, 0, 100e6 + x);
            }
            reader.join();
            assertEquals(0, failures.get());
            assertEquals(100e6 + 1999, mDevice.getFrequency(Direction.RX, 0));
        }
    }
}
