/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

/**
 * Stream flag bit masks (SoapySDR/Constants.h) used with activate, read, write and status calls.
 */
public final class StreamFlags
{
    /** Indicates the end of a burst transmission or reception. */
    public static final int END_BURST = 1 << 1;
    /** Indicates that the time stamp is valid. */
    public static final int HAS_TIME = 1 << 2;
    /** Indicates that stream terminated prematurely. */
    public static final int END_ABRUPT = 1 << 3;
    /** Indicates transmit or receive only a single packet. */
    public static final int ONE_PACKET = 1 << 4;
    /** Indicates that this read call and the next results in a fragment. */
    public static final int MORE_FRAGMENTS = 1 << 5;
    /** Indicates that the stream should wait for an external trigger event. */
    public static final int WAIT_TRIGGER = 1 << 6;

    private StreamFlags()
    {
    }

    /**
     * Indicates if all bits of the mask are set in the flags.
     */
    public static boolean isSet(int flags, int mask)
    {
        return (flags & mask) == mask;
    }
}
