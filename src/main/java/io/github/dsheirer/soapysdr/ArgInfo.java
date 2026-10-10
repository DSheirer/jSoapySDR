/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

import io.github.dsheirer.soapysdr.internal.SoapyNative;

import java.util.List;

/**
 * Describes a device argument, setting or sensor (SoapySDRArgInfo).
 *
 * @param key identifier used with read/write calls
 * @param value default value
 * @param name displayable name (may be empty)
 * @param description help text (may be empty)
 * @param units units of the value (may be empty)
 * @param type data type
 * @param range valid range for numeric types
 * @param options list of discrete allowed values (may be empty)
 * @param optionNames displayable names for the options (may be empty)
 */
public record ArgInfo(String key, String value, String name, String description, String units, ArgType type,
                      Range range, List<String> options, List<String> optionNames)
{
    public ArgInfo
    {
        options = List.copyOf(options);
        optionNames = List.copyOf(optionNames);
    }

    static ArgInfo of(SoapyNative.RawArgInfo raw)
    {
        return new ArgInfo(raw.key(), raw.value(), raw.name(), raw.description(), raw.units(),
            ArgType.fromValue(raw.type()), Range.of(raw.range()), raw.options(), raw.optionNames());
    }

    /**
     * Displayable name, falling back to the key when no name is provided.
     */
    public String displayName()
    {
        return name == null || name.isEmpty() ? key : name;
    }
}
