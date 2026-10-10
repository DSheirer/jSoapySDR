/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

/**
 * SoapySDR error codes (SoapySDR/Errors.h) returned by streaming calls.
 */
public enum ErrorCode
{
    /** Returned when read has a timeout. */
    TIMEOUT(-1),
    /** Returned for non-specific stream errors. */
    STREAM_ERROR(-2),
    /** Returned when read has data corruption, e.g. the driver saw a malformed packet. */
    CORRUPTION(-3),
    /** Returned when read has an overflow condition, e.g. the internal buffer has filled. */
    OVERFLOW(-4),
    /** Returned when a requested operation or flag setting is not supported by the underlying implementation. */
    NOT_SUPPORTED(-5),
    /** Returned when the device encountered a stream time which was expired (late) or too early to process. */
    TIME_ERROR(-6),
    /** Returned when write caused an underflow condition, e.g. a continuous stream was starved of data. */
    UNDERFLOW(-7),
    /** Any other negative code. */
    UNKNOWN(Integer.MIN_VALUE);

    private final int value;

    ErrorCode(int value)
    {
        this.value = value;
    }

    /**
     * Native error code value.
     */
    public int value()
    {
        return value;
    }

    /**
     * Converts a native error code, returning {@link #UNKNOWN} for unrecognized values.
     */
    public static ErrorCode fromValue(int value)
    {
        for(ErrorCode code : values())
        {
            if(code.value == value)
            {
                return code;
            }
        }
        return UNKNOWN;
    }
}
