/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

/**
 * Thrown when a SoapySDR call reports a failure.
 */
public class SoapySDRException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final int code;

    public SoapySDRException(String message, int code)
    {
        super(message);
        this.code = code;
    }

    public SoapySDRException(String message)
    {
        this(message, -1);
    }

    /**
     * Native status or error code associated with the failure (usually -1 for exceptions thrown by a driver).
     */
    public int code()
    {
        return code;
    }

    /**
     * Error code as an enumeration value.
     */
    public ErrorCode errorCode()
    {
        return ErrorCode.fromValue(code);
    }
}
