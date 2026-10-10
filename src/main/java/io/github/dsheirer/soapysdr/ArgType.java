/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

/**
 * Data type of an argument described by {@link ArgInfo} (SoapySDRArgInfoType).
 */
public enum ArgType
{
    BOOL,
    INT,
    FLOAT,
    STRING,
    UNKNOWN;

    static ArgType fromValue(int value)
    {
        return switch(value)
        {
            case 0 -> BOOL;
            case 1 -> INT;
            case 2 -> FLOAT;
            case 3 -> STRING;
            default -> UNKNOWN;
        };
    }
}
