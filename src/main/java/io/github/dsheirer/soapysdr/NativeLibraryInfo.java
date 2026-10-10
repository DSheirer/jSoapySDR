/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr;

import java.nio.file.Path;

/**
 * Describes which SoapySDR native library jSoapySDR loaded.
 *
 * @param platform platform identifier of the running JVM, e.g. "linux-x86_64", or null for a platform without a
 *                 bundled image
 * @param bundled true when the SoapySDR image bundled in the jSoapySDR jar was loaded
 * @param library path (or loader name) of the SoapySDR core library
 * @param root directory the bundled image was extracted to (SoapySDR's root path), or null for a system library
 */
public record NativeLibraryInfo(String platform, boolean bundled, String library, Path root)
{
}
