/*
 * jSoapySDR - Java bindings for SoapySDR using the Foreign Function & Memory API
 * Copyright (C) 2026 Dennis Sheirer
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.dsheirer.soapysdr.examples;

import io.github.dsheirer.soapysdr.Device;
import io.github.dsheirer.soapysdr.Direction;
import io.github.dsheirer.soapysdr.ErrorCode;
import io.github.dsheirer.soapysdr.Formats;
import io.github.dsheirer.soapysdr.LogLevel;
import io.github.dsheirer.soapysdr.SoapySDR;
import io.github.dsheirer.soapysdr.Stream;
import io.github.dsheirer.soapysdr.StreamResult;

/**
 * Tunes a receiver, streams CF32 samples for a few seconds and prints the average power of each block.
 *
 * <p>Usage: {@code RxPower <device args> [frequency Hz] [sample rate]}, e.g.
 * {@code RxPower driver=rtlsdr 162.55e6 2.4e6}</p>
 */
public final class RxPower
{
    private RxPower()
    {
    }

    public static void main(String[] args)
    {
        String deviceArgs = args.length > 0 ? args[0] : "";
        double frequency = args.length > 1 ? Double.parseDouble(args[1]) : 100e6;
        double sampleRate = args.length > 2 ? Double.parseDouble(args[2]) : 2.4e6;

        SoapySDR.setLogHandler((level, message) -> {
            if(level.value() <= LogLevel.WARNING.value())
            {
                System.err.println("[" + level + "] " + message);
            }
        });

        try(Device device = SoapySDR.open(deviceArgs))
        {
            System.out.println("Opened " + device.getDriverKey() + " / " + device.getHardwareKey());
            device.setSampleRate(Direction.RX, 0, sampleRate);
            device.setFrequency(Direction.RX, 0, frequency);
            if(device.hasGainMode(Direction.RX, 0))
            {
                device.setGainMode(Direction.RX, 0, true);
            }

            try(Stream stream = device.setupStream(Direction.RX, Formats.CF32, 0))
            {
                float[] samples = new float[stream.getMTU() * 2 * 8];
                long end = System.nanoTime() + 3_000_000_000L;
                long total = 0;
                int overflows = 0;

                stream.activate();
                while(System.nanoTime() < end)
                {
                    StreamResult result = stream.read(samples, 500_000);
                    if(result.isSuccess())
                    {
                        double power = 0;
                        for(int x = 0; x < result.elements() * 2; x += 2)
                        {
                            power += samples[x] * samples[x] + samples[x + 1] * samples[x + 1];
                        }
                        power /= Math.max(result.elements(), 1);
                        total += result.elements();
                        System.out.printf("%6d samples  %7.2f dBFS%n", result.elements(),
                            10 * Math.log10(power + 1e-20));
                    }
                    else if(result.errorCode() == ErrorCode.OVERFLOW)
                    {
                        overflows++;
                    }
                    else if(result.errorCode() != ErrorCode.TIMEOUT)
                    {
                        System.err.println("Stream error: " + result);
                        break;
                    }
                }
                stream.deactivate();

                System.out.printf("Received %d samples in 3 s (%.3f MS/s), %d overflows%n", total, total / 3e6,
                    overflows);
            }
        }
        finally
        {
            SoapySDR.setLogHandler(null);
        }
    }
}
