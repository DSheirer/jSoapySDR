/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

/**
 * Stream and channel direction.
 */
public enum Direction
{
    /** Transmit (SOAPY_SDR_TX) */
    TX(0),
    /** Receive (SOAPY_SDR_RX) */
    RX(1);

    private final int value;

    Direction(int value)
    {
        this.value = value;
    }

    /**
     * Native SoapySDR direction constant.
     */
    public int value()
    {
        return value;
    }
}
