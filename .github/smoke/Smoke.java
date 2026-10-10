/*
 * jSoapySDR - smoke test for the packaged jar
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import io.github.dsheirer.soapysdr.NativeLibraryInfo;
import io.github.dsheirer.soapysdr.SoapySDR;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Verifies that the jar selects, extracts and loads the bundled image for this platform and that all bundled drivers
 * register and enumerate. Usage: java -cp jsoapysdr.jar Smoke.java <expected platform>
 */
public class Smoke
{
    public static void main(String[] args)
    {
        String expected = args[0];
        NativeLibraryInfo info = SoapySDR.getNativeLibraryInfo();
        System.out.println("Native library: " + info);
        System.out.println("SoapySDR " + SoapySDR.getLibVersion() + " API " + SoapySDR.getAPIVersion() + " ABI " +
            SoapySDR.getABIVersion() + " root " + SoapySDR.getRootPath());

        check(info.bundled(), "bundled image was not loaded");
        check(expected.equals(info.platform()), "expected platform " + expected + " but got " + info.platform());

        SoapySDR.loadModules();
        Map<String, String> modules = new TreeMap<>();
        for(String module : SoapySDR.listModules())
        {
            modules.put(Path.of(module).getFileName().toString(), SoapySDR.getModuleVersion(module));
        }
        System.out.println("Modules: " + modules);
        check(modules.size() == 4, "expected 4 bundled driver modules");

        for(String driver : List.of("rtlsdr", "airspy", "airspyhf", "hackrf"))
        {
            System.out.println("enumerate driver=" + driver + ": " + SoapySDR.enumerate("driver=" + driver));
        }
        System.out.println("enumerate all: " + SoapySDR.enumerate());
        System.out.println("OK");
    }

    private static void check(boolean condition, String message)
    {
        if(!condition)
        {
            System.err.println("FAILED: " + message);
            System.exit(1);
        }
    }
}
