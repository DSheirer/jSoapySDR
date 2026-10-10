/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

/**
 * Stream sample format identifiers (SoapySDR/Formats.h). The "C" prefix denotes complex (interleaved I/Q) samples.
 */
public final class Formats
{
    /** Complex 64-bit floats (complex double) */
    public static final String CF64 = "CF64";
    /** Complex 32-bit floats (complex float) */
    public static final String CF32 = "CF32";
    /** Complex signed 32-bit integers */
    public static final String CS32 = "CS32";
    /** Complex unsigned 32-bit integers */
    public static final String CU32 = "CU32";
    /** Complex signed 16-bit integers */
    public static final String CS16 = "CS16";
    /** Complex unsigned 16-bit integers */
    public static final String CU16 = "CU16";
    /** Complex signed 12-bit integers (3 bytes) */
    public static final String CS12 = "CS12";
    /** Complex unsigned 12-bit integers (3 bytes) */
    public static final String CU12 = "CU12";
    /** Complex signed 8-bit integers */
    public static final String CS8 = "CS8";
    /** Complex unsigned 8-bit integers */
    public static final String CU8 = "CU8";
    /** Complex signed 4-bit integers (1 byte) */
    public static final String CS4 = "CS4";
    /** Complex unsigned 4-bit integers (1 byte) */
    public static final String CU4 = "CU4";
    /** Real 64-bit floats (double) */
    public static final String F64 = "F64";
    /** Real 32-bit floats (float) */
    public static final String F32 = "F32";
    /** Real signed 32-bit integers */
    public static final String S32 = "S32";
    /** Real unsigned 32-bit integers */
    public static final String U32 = "U32";
    /** Real signed 16-bit integers */
    public static final String S16 = "S16";
    /** Real unsigned 16-bit integers */
    public static final String U16 = "U16";
    /** Real signed 8-bit integers */
    public static final String S8 = "S8";
    /** Real unsigned 8-bit integers */
    public static final String U8 = "U8";

    private Formats()
    {
    }

    /**
     * Size in bytes of a single element (one complex sample, or one real sample) in the specified format, as
     * reported by SoapySDR_formatToSize().
     */
    public static int sizeOf(String format)
    {
        return SoapySDR.formatToSize(format);
    }
}
