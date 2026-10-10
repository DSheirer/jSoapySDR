/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Library-level tests that need only the SoapySDR library (uses its built-in "null" device).
 */
class SoapySDRTest
{
    @BeforeEach
    void requireLibrary()
    {
        NativeSupport.requireLibrary();
    }

    @Test
    void versions()
    {
        assertTrue(SoapySDR.getAPIVersion().startsWith("0.8"), SoapySDR.getAPIVersion());
        assertFalse(SoapySDR.getABIVersion().isEmpty());
        assertFalse(SoapySDR.getLibVersion().isEmpty());
    }

    @Test
    void formatSizes()
    {
        assertEquals(16, Formats.sizeOf(Formats.CF64));
        assertEquals(8, Formats.sizeOf(Formats.CF32));
        assertEquals(4, Formats.sizeOf(Formats.CS16));
        assertEquals(2, Formats.sizeOf(Formats.CU8));
        assertEquals(4, Formats.sizeOf(Formats.F32));
        assertThrows(IllegalArgumentException.class, () -> Formats.sizeOf("BOGUS"));
    }

    @Test
    void errorStrings()
    {
        assertEquals("TIMEOUT", SoapySDR.errorToString(ErrorCode.TIMEOUT.value()));
        assertEquals("OVERFLOW", SoapySDR.errorToString(ErrorCode.OVERFLOW.value()));
        assertEquals(ErrorCode.UNDERFLOW, ErrorCode.fromValue(-7));
    }

    @Test
    void timeConversions()
    {
        assertEquals(1_000_000_000L, SoapySDR.ticksToTimeNs(1000, 1000.0));
        assertEquals(1000L, SoapySDR.timeNsToTicks(1_000_000_000L, 1000.0));
    }

    @Test
    void kwargsRoundTrip()
    {
        Map<String, String> parsed = SoapySDR.parseKwargs("driver=rtlsdr, serial=00000001");
        assertEquals(Map.of("driver", "rtlsdr", "serial", "00000001"), parsed);

        Map<String, String> args = new LinkedHashMap<>();
        args.put("driver", "hackrf");
        args.put("serial", "abc");
        assertEquals(args, SoapySDR.parseKwargs(SoapySDR.formatKwargs(args)));
        assertTrue(SoapySDR.parseKwargs("").isEmpty());
    }

    @Test
    void enumerateNullDevice()
    {
        // Some drivers (e.g. SDRplay) ignore filter keys they do not know, so count only the null device
        List<Map<String, String>> results = SoapySDR.enumerate("type=null").stream()
            .filter(device -> "null".equals(device.get("type"))).toList();
        assertEquals(1, results.size());

        // Map based filter
        assertEquals(1, SoapySDR.enumerate(Map.of("type", "null")).stream()
            .filter(device -> "null".equals(device.get("type"))).count());
    }

    @Test
    void openNullDevice()
    {
        Device device = SoapySDR.open("driver=null,type=null");
        try
        {
            assertEquals("null", device.getDriverKey());
            assertEquals("null", device.getHardwareKey());
            assertEquals(0, device.getNumChannels(Direction.RX));
            assertTrue(device.getHardwareInfo().isEmpty());
            assertTrue(device.listAntennas(Direction.RX, 0).isEmpty());
            assertTrue(device.getFrequencyRange(Direction.RX, 0).isEmpty());
            assertTrue(device.getSettingInfo().isEmpty());
            assertEquals(new Range(0, 0, 0), device.getGainRange(Direction.RX, 0));

            // The null device does not implement streaming; the driver exception becomes a SoapySDRException
            SoapySDRException e = assertThrows(SoapySDRException.class,
                () -> device.setupStream(Direction.RX, Formats.CF32, 0));
            assertFalse(e.getMessage().isEmpty());
        }
        finally
        {
            device.close();
        }

        assertTrue(device.isClosed());
        device.close(); // idempotent
        assertThrows(IllegalStateException.class, () -> device.getHardwareKey());
    }

    @Test
    void openUnknownDriverFails()
    {
        assertThrows(SoapySDRException.class, () -> SoapySDR.open("driver=does_not_exist_" + System.nanoTime()));
    }

    @Test
    void logHandlerReceivesMessages()
    {
        List<String> messages = new ArrayList<>();
        SoapySDR.setLogLevel(LogLevel.DEBUG);
        SoapySDR.setLogHandler((level, message) -> {
            if(level == LogLevel.WARNING)
            {
                messages.add(message);
            }
        });
        try
        {
            SoapySDR.log(LogLevel.WARNING, "hello from jSoapySDR");
            SoapySDR.log(LogLevel.INFO, "filtered by the handler");
        }
        finally
        {
            SoapySDR.setLogHandler(null);
            SoapySDR.setLogLevel(LogLevel.INFO);
        }
        assertEquals(List.of("hello from jSoapySDR"), messages);
    }

    @Test
    void logHandlerExceptionsAreContained()
    {
        SoapySDR.setLogHandler((level, message) -> {
            throw new RuntimeException("boom");
        });
        try
        {
            SoapySDR.log(LogLevel.ERROR, "must not crash the JVM");
        }
        finally
        {
            SoapySDR.setLogHandler(null);
        }
    }

    @Test
    void modules()
    {
        assertFalse(SoapySDR.getRootPath().isEmpty());
        assertFalse(SoapySDR.listSearchPaths().isEmpty());
        SoapySDR.listModules(); // may be empty
    }
}
