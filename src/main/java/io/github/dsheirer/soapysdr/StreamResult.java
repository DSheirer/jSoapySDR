/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

/**
 * Result of a stream read, write or status call.
 *
 * @param status number of elements transferred (zero or positive), or a negative {@link ErrorCode} value
 * @param flags output flags reported by the driver, see {@link StreamFlags}
 * @param timeNs buffer time stamp in nanoseconds, valid when {@link StreamFlags#HAS_TIME} is set
 * @param channelMask channel mask reported by readStreamStatus (zero for read and write)
 */
public record StreamResult(int status, int flags, long timeNs, long channelMask)
{
    /**
     * Indicates the call succeeded.
     */
    public boolean isSuccess()
    {
        return status >= 0;
    }

    /**
     * Number of elements transferred, or zero on error.
     */
    public int elements()
    {
        return Math.max(status, 0);
    }

    /**
     * Error code, or null when the call succeeded.
     */
    public ErrorCode errorCode()
    {
        return status < 0 ? ErrorCode.fromValue(status) : null;
    }

    /**
     * Indicates the time stamp is valid.
     */
    public boolean hasTime()
    {
        return StreamFlags.isSet(flags, StreamFlags.HAS_TIME);
    }

    /**
     * Indicates the end of a burst.
     */
    public boolean isEndOfBurst()
    {
        return StreamFlags.isSet(flags, StreamFlags.END_BURST);
    }

    /**
     * Indicates there are more fragments of the current packet to read.
     */
    public boolean hasMoreFragments()
    {
        return StreamFlags.isSet(flags, StreamFlags.MORE_FRAGMENTS);
    }

    @Override
    public String toString()
    {
        return isSuccess() ? "StreamResult[elements=" + status + ", flags=0x" + Integer.toHexString(flags) +
            (hasTime() ? ", timeNs=" + timeNs : "") + "]" : "StreamResult[error=" + errorCode() + " (" + status + ")]";
    }
}
