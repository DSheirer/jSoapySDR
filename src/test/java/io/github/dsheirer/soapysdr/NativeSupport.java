/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

import org.junit.jupiter.api.Assumptions;

/**
 * Skips tests when the SoapySDR native library (or the jsoapytest driver module) is unavailable, unless the
 * {@code soapysdr.tests.required} system property is true, in which case the tests fail instead.
 */
final class NativeSupport
{
    private static final boolean REQUIRED = Boolean.getBoolean("soapysdr.tests.required");

    /** The first load failure; later attempts only report NoClassDefFoundError without the cause. */
    private static volatile String sFirstFailure;

    private NativeSupport()
    {
    }

    private static String describe(Throwable t)
    {
        StringBuilder sb = new StringBuilder();
        for(Throwable cause = t; cause != null; cause = cause.getCause())
        {
            if(sb.length() > 0)
            {
                sb.append(" <- caused by ");
            }
            sb.append(cause);
        }
        return sb.toString();
    }

    static void requireLibrary()
    {
        boolean available;
        String reason = "";
        try
        {
            SoapySDR.getAPIVersion();
            available = true;
        }
        catch(LinkageError e)
        {
            available = false;
            if(sFirstFailure == null)
            {
                sFirstFailure = describe(e);
            }
            reason = sFirstFailure;
        }

        if(REQUIRED && !available)
        {
            throw new AssertionError("SoapySDR library is required but unavailable: " + reason);
        }
        Assumptions.assumeTrue(available, "SoapySDR library unavailable: " + reason);
    }

    static void requireTestDriver()
    {
        requireLibrary();
        boolean available = !SoapySDR.enumerate("driver=jsoapytest").isEmpty();
        if(REQUIRED && !available)
        {
            throw new AssertionError("jsoapytest driver module is required but was not found; build it from " +
                "src/test/native and set SOAPY_SDR_PLUGIN_PATH");
        }
        Assumptions.assumeTrue(available, "jsoapytest driver module not found");
    }
}
