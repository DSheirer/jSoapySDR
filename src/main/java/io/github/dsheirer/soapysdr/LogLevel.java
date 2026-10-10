/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

/**
 * SoapySDR log severity levels (SoapySDRLogLevel). Lower values are more severe.
 */
public enum LogLevel
{
    FATAL(1),
    CRITICAL(2),
    ERROR(3),
    WARNING(4),
    NOTICE(5),
    INFO(6),
    DEBUG(7),
    TRACE(8),
    /** Streaming status indicators such as "U" (underflow) and "O" (overflow). */
    SSI(9);

    private final int value;

    LogLevel(int value)
    {
        this.value = value;
    }

    /**
     * Native SoapySDR log level value.
     */
    public int value()
    {
        return value;
    }

    /**
     * Converts a native log level value, returning {@link #INFO} for unrecognized values.
     */
    public static LogLevel fromValue(int value)
    {
        for(LogLevel level : values())
        {
            if(level.value == value)
            {
                return level;
            }
        }
        return INFO;
    }
}
