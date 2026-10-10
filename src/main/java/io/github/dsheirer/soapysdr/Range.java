/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

/**
 * Numeric range with an optional step size (SoapySDRRange). A step of zero indicates a continuous range.
 *
 * @param minimum lowest value
 * @param maximum highest value
 * @param step resolution, or zero for a continuous range
 */
public record Range(double minimum, double maximum, double step)
{
    static Range of(double[] values)
    {
        return new Range(values[0], values[1], values[2]);
    }

    /**
     * Indicates if the value falls within this range, inclusive of the endpoints.
     */
    public boolean contains(double value)
    {
        return value >= minimum && value <= maximum;
    }

    @Override
    public String toString()
    {
        return step == 0 ? "[" + minimum + ", " + maximum + "]" : "[" + minimum + ", " + maximum + ", step " + step + "]";
    }
}
