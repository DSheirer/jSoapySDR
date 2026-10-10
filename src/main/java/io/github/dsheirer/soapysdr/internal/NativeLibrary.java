/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Locates and loads the native SoapySDR shared library.
 *
 * <p>Resolution order:</p>
 * <ol>
 *     <li>The {@code soapysdr.library} system property, which may be a bare library name
 *     (resolved by the OS loader) or an absolute path to the library file.</li>
 *     <li>The {@code SOAPYSDR_LIBRARY} environment variable, with the same semantics.</li>
 *     <li>Platform default names and common install locations.</li>
 * </ol>
 */
final class NativeLibrary
{
    static final String PROPERTY = "soapysdr.library";
    static final String ENVIRONMENT = "SOAPYSDR_LIBRARY";

    private NativeLibrary()
    {
    }

    static SymbolLookup load()
    {
        List<String> candidates = new ArrayList<>();
        String property = System.getProperty(PROPERTY);
        String environment = System.getenv(ENVIRONMENT);

        if(property != null && !property.isBlank())
        {
            candidates.add(property.trim());
        }
        else if(environment != null && !environment.isBlank())
        {
            candidates.add(environment.trim());
        }
        else
        {
            candidates.addAll(defaultCandidates());
        }

        List<String> failures = new ArrayList<>();

        for(String candidate : candidates)
        {
            try
            {
                if(isPath(candidate))
                {
                    Path path = Path.of(candidate);
                    if(Files.exists(path))
                    {
                        return SymbolLookup.libraryLookup(path, Arena.global());
                    }
                    failures.add(candidate + " (not found)");
                }
                else
                {
                    return SymbolLookup.libraryLookup(candidate, Arena.global());
                }
            }
            catch(IllegalArgumentException e)
            {
                failures.add(candidate + " (" + e.getMessage() + ")");
            }
        }

        throw new UnsatisfiedLinkError("Unable to load the SoapySDR native library. Install SoapySDR 0.8.x or set " +
            "the '" + PROPERTY + "' system property (or " + ENVIRONMENT + " environment variable) to the library " +
            "name or path. Tried: " + failures);
    }

    private static boolean isPath(String candidate)
    {
        return candidate.contains("/") || candidate.contains("\\");
    }

    private static List<String> defaultCandidates()
    {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();

        if(os.contains("win"))
        {
            candidates.add("SoapySDR.dll");
            candidates.add("libSoapySDR.dll");
            String programFiles = System.getenv("ProgramFiles");
            if(programFiles != null)
            {
                candidates.add(programFiles + "\\PothosSDR\\bin\\SoapySDR.dll");
            }
            String userProfile = System.getenv("USERPROFILE");
            if(userProfile != null)
            {
                candidates.add(userProfile + "\\radioconda\\Library\\bin\\SoapySDR.dll");
            }
        }
        else if(os.contains("mac") || os.contains("darwin"))
        {
            candidates.add("libSoapySDR.0.8.dylib");
            candidates.add("libSoapySDR.dylib");
            candidates.add("/opt/homebrew/lib/libSoapySDR.0.8.dylib");
            candidates.add("/opt/homebrew/lib/libSoapySDR.dylib");
            candidates.add("/usr/local/lib/libSoapySDR.0.8.dylib");
            candidates.add("/usr/local/lib/libSoapySDR.dylib");
            candidates.add("/opt/local/lib/libSoapySDR.dylib");
        }
        else
        {
            candidates.add("libSoapySDR.so.0.8");
            candidates.add("libSoapySDR.so");
            candidates.add("/usr/local/lib/libSoapySDR.so.0.8");
            candidates.add("/usr/local/lib64/libSoapySDR.so.0.8");
            candidates.add("/usr/lib/x86_64-linux-gnu/libSoapySDR.so.0.8");
            candidates.add("/usr/lib/aarch64-linux-gnu/libSoapySDR.so.0.8");
            candidates.add("/usr/lib64/libSoapySDR.so.0.8");
        }

        return candidates;
    }
}
