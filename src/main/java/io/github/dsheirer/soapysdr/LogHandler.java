/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

/**
 * Receives log messages emitted by the SoapySDR library and its driver modules.
 *
 * <p>Handlers are invoked from native threads, including device driver threads, and must be thread-safe and fast.
 * Exceptions thrown by a handler are caught and discarded.</p>
 */
@FunctionalInterface
public interface LogHandler
{
    void log(LogLevel level, String message);
}
