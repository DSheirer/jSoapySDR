/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

/**
 * The device's native stream format and its full-scale value (getNativeStreamFormat).
 *
 * @param format native format identifier, e.g. "CS16"
 * @param fullScale maximum possible value of a sample in the native format, e.g. 2048 for 12-bit samples
 */
public record NativeStreamFormat(String format, double fullScale)
{
}
