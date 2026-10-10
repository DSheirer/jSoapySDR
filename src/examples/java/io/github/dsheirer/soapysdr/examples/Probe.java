/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr.examples;

import io.github.dsheirer.soapysdr.ArgInfo;
import io.github.dsheirer.soapysdr.Device;
import io.github.dsheirer.soapysdr.Direction;
import io.github.dsheirer.soapysdr.SoapySDR;

import java.util.List;
import java.util.Map;

/**
 * Lists SoapySDR devices and prints the capabilities of each, similar to "SoapySDRUtil --probe".
 *
 * <p>Usage: {@code Probe [device args]}, e.g. {@code Probe driver=rtlsdr}</p>
 */
public final class Probe
{
    private Probe()
    {
    }

    public static void main(String[] args)
    {
        System.out.println("SoapySDR library " + SoapySDR.getLibVersion() + " (API " + SoapySDR.getAPIVersion() +
            ", ABI " + SoapySDR.getABIVersion() + ")");
        System.out.println("Modules: " + SoapySDR.listModules());

        String filter = args.length > 0 ? args[0] : "";
        List<Map<String, String>> devices = SoapySDR.enumerate(filter);
        System.out.println("Found " + devices.size() + " device(s)");

        for(Map<String, String> deviceArgs : devices)
        {
            System.out.println();
            System.out.println("== " + deviceArgs);
            try(Device device = SoapySDR.open(deviceArgs))
            {
                System.out.println("  driver:   " + device.getDriverKey());
                System.out.println("  hardware: " + device.getHardwareKey());
                device.getHardwareInfo().forEach((k, v) -> System.out.println("    " + k + "=" + v));

                for(ArgInfo setting : device.getSettingInfo())
                {
                    System.out.println("  setting:  " + setting.key() + " (" + setting.type() + ", default " +
                        setting.value() + ") " + setting.description());
                }

                for(Direction direction : Direction.values())
                {
                    int channels = device.getNumChannels(direction);
                    for(int channel = 0; channel < channels; channel++)
                    {
                        System.out.println("  " + direction + " channel " + channel);
                        System.out.println("    formats:      " + device.getStreamFormats(direction, channel) +
                            " native " + device.getNativeStreamFormat(direction, channel));
                        System.out.println("    antennas:     " + device.listAntennas(direction, channel));
                        System.out.println("    gains:        " + device.listGains(direction, channel) +
                            " overall " + device.getGainRange(direction, channel) +
                            (device.hasGainMode(direction, channel) ? " (AGC)" : ""));
                        for(String gain : device.listGains(direction, channel))
                        {
                            System.out.println("      " + gain + " " + device.getGainRange(direction, channel, gain));
                        }
                        System.out.println("    frequency:    " + device.getFrequencyRange(direction, channel));
                        System.out.println("    sample rates: " + device.getSampleRateRange(direction, channel));
                        System.out.println("    bandwidths:   " + device.getBandwidthRange(direction, channel));
                        System.out.println("    sensors:      " + device.listSensors(direction, channel));
                    }
                }
            }
            catch(RuntimeException e)
            {
                System.out.println("  unable to probe: " + e.getMessage());
            }
        }
    }
}
