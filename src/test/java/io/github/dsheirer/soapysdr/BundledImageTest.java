/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the SoapySDR image bundled for the current platform. Skipped when the library was loaded from a system
 * installation, unless {@code soapysdr.tests.bundled=true} (CI) requires the bundled image.
 */
class BundledImageTest
{
    /** Bundled driver modules and their versions, keyed by module file name prefix */
    private static final Map<String, String> MODULES = Map.of(
        "rtlsdrSupport", "0.3.3",
        "airspySupport", "0.2.0",
        "airspyhfSupport", "0.2.0",
        "HackRFSupport", "0.3.4",
        "SoapyHydraSDR", "1.0.1",
        "bladeRFSupport", "0.4.2");

    private static final String SDRPLAY_MODULE = "sdrPlaySupport";

    @BeforeEach
    void requireBundled()
    {
        NativeSupport.requireLibrary();
        boolean bundled = SoapySDR.getNativeLibraryInfo().bundled();
        if(Boolean.getBoolean("soapysdr.tests.bundled") && !bundled)
        {
            throw new AssertionError("Expected the bundled image but loaded " + SoapySDR.getNativeLibraryInfo());
        }
        Assumptions.assumeTrue(bundled, "SoapySDR was loaded from a system installation");
    }

    @Test
    void loadedFromExtractedBundle() throws Exception
    {
        NativeLibraryInfo info = SoapySDR.getNativeLibraryInfo();
        assertNotNull(info.platform());
        assertNotNull(info.root());
        assertTrue(Files.isRegularFile(Path.of(info.library())), info.library());
        assertTrue(Path.of(info.library()).startsWith(info.root()), info.toString());
        assertTrue(Files.isRegularFile(info.root().resolve(".complete")));

        // SoapySDR's root path was redirected to the bundle
        assertEquals(info.root().toRealPath(), Path.of(SoapySDR.getRootPath()).toRealPath());
        assertEquals("0.8.0", SoapySDR.getAPIVersion());
    }

    @Test
    void bundledDriversAreRegistered()
    {
        SoapySDR.loadModules(); // idempotent; normally triggered by the first enumerate/open
        Map<String, String> versions = new TreeMap<>();
        for(String module : SoapySDR.listModules())
        {
            String file = Path.of(module).getFileName().toString();
            for(String name : MODULES.keySet())
            {
                if(file.contains(name))
                {
                    versions.put(name, SoapySDR.getModuleVersion(module));
                }
            }
        }
        Map<String, String> expected = new TreeMap<>(MODULES);
        // The SDRplay module is built only when the SDRplay API headers are available (always in CI)
        if(Boolean.getBoolean("soapysdr.tests.sdrplay") || SoapySDR.listModules().stream()
            .anyMatch(m -> m.contains(SDRPLAY_MODULE)))
        {
            expected.put(SDRPLAY_MODULE, "0.5.3");
            for(String module : SoapySDR.listModules())
            {
                if(module.contains(SDRPLAY_MODULE))
                {
                    versions.put(SDRPLAY_MODULE, SoapySDR.getModuleVersion(module));
                }
            }
        }
        assertEquals(expected, versions);
    }

    @Test
    void enumerateEveryDriverWithoutHardware()
    {
        // Must not crash even where libusb cannot initialize (containers, CI); finds no devices without hardware
        for(String driver : List.of("rtlsdr", "airspy", "airspyhf", "hackrf", "hydrasdr", "bladerf", "sdrplay"))
        {
            for(Map<String, String> device : SoapySDR.enumerate("driver=" + driver))
            {
                assertEquals(driver, device.get("driver"), device.toString());
            }
        }
        SoapySDR.enumerate();
    }

    @Test
    void sdrplayModuleUsesInstalledApiLibrary()
    {
        // With the test fixture standing in for the SDRplay API library, the bundled SDRplay module must load it
        // and report its one device
        Assumptions.assumeTrue(Boolean.getBoolean("soapysdr.tests.fake.sdrplay"), "fake SDRplay API not built");
        List<Map<String, String>> devices = SoapySDR.enumerate("driver=sdrplay");
        assertEquals(1, devices.size(), devices.toString());
        assertEquals("JSOAPYTEST1", devices.get(0).get("serial"));
        assertEquals("sdrplay", devices.get(0).get("driver"));
    }

    @Test
    void openingAbsentHardwareFailsCleanly()
    {
        Assumptions.assumeTrue(SoapySDR.enumerate("driver=rtlsdr").isEmpty(), "an RTL-SDR is attached");
        SoapySDRException e = assertThrows(SoapySDRException.class, () -> SoapySDR.open("driver=rtlsdr"));
        assertFalse(e.getMessage().isEmpty());
    }
}
