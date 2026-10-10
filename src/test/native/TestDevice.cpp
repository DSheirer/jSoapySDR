// jSoapySDR test driver module.
//
// A deterministic, hardware-free SoapySDR device used by the jSoapySDR unit tests to exercise device control,
// struct marshalling and the streaming path. Discovered only when enumerating with "driver=jsoapytest".
//
// SPDX-License-Identifier: Apache-2.0

#include <SoapySDR/Device.hpp>
#include <SoapySDR/Registry.hpp>
#include <SoapySDR/Formats.hpp>
#include <SoapySDR/Errors.hpp>
#include <SoapySDR/Logger.hpp>

#include <complex>
#include <cstdint>
#include <map>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace
{
constexpr size_t MTU = 1024;

struct TestStream
{
    int direction;
    std::string format;
    std::vector<size_t> channels;
    bool active = false;
    long long counter = 0;
};

class TestDevice : public SoapySDR::Device
{
public:
    std::string getDriverKey(void) const override { return "jsoapytest"; }
    std::string getHardwareKey(void) const override { return "jsoapytest-hw"; }

    SoapySDR::Kwargs getHardwareInfo(void) const override
    {
        SoapySDR::Kwargs info;
        info["serial"] = "0001";
        info["firmware"] = "1.2.3";
        return info;
    }

    size_t getNumChannels(const int direction) const override { return direction == SOAPY_SDR_RX ? 2 : 1; }

    std::vector<std::string> getStreamFormats(const int, const size_t) const override
    {
        return {SOAPY_SDR_CF32, SOAPY_SDR_CS16};
    }

    std::string getNativeStreamFormat(const int, const size_t, double &fullScale) const override
    {
        fullScale = 32768;
        return SOAPY_SDR_CS16;
    }

    SoapySDR::Stream *setupStream(const int direction, const std::string &format,
        const std::vector<size_t> &channels, const SoapySDR::Kwargs &) override
    {
        if(format != SOAPY_SDR_CF32 && format != SOAPY_SDR_CS16)
        {
            throw std::runtime_error("unsupported format " + format);
        }
        auto *stream = new TestStream();
        stream->direction = direction;
        stream->format = format;
        stream->channels = channels.empty() ? std::vector<size_t>{0} : channels;
        for(auto ch : stream->channels)
        {
            if(ch >= getNumChannels(direction)) { delete stream; throw std::runtime_error("bad channel"); }
        }
        return reinterpret_cast<SoapySDR::Stream *>(stream);
    }

    void closeStream(SoapySDR::Stream *stream) override { delete reinterpret_cast<TestStream *>(stream); }

    size_t getStreamMTU(SoapySDR::Stream *) const override { return MTU; }

    int activateStream(SoapySDR::Stream *stream, const int, const long long, const size_t) override
    {
        reinterpret_cast<TestStream *>(stream)->active = true;
        return 0;
    }

    int deactivateStream(SoapySDR::Stream *stream, const int, const long long) override
    {
        reinterpret_cast<TestStream *>(stream)->active = false;
        return 0;
    }

    // Each element i on stream channel index c is I = n, Q = -n + 1000 * c where n = counter + i.
    int readStream(SoapySDR::Stream *handle, void *const *buffs, const size_t numElems, int &flags,
        long long &timeNs, const long) override
    {
        auto *stream = reinterpret_cast<TestStream *>(handle);
        if(!stream->active) return SOAPY_SDR_STREAM_ERROR;
        const size_t n = std::min(numElems, MTU);
        for(size_t c = 0; c < stream->channels.size(); c++)
        {
            for(size_t i = 0; i < n; i++)
            {
                const long long v = stream->counter + (long long)i;
                if(stream->format == SOAPY_SDR_CF32)
                {
                    auto *out = reinterpret_cast<float *>(buffs[c]);
                    out[2 * i] = (float)v;
                    out[2 * i + 1] = (float)(-v + 1000 * (long long)c);
                }
                else
                {
                    auto *out = reinterpret_cast<int16_t *>(buffs[c]);
                    out[2 * i] = (int16_t)v;
                    out[2 * i + 1] = (int16_t)(-v + 1000 * (long long)c);
                }
            }
        }
        flags = SOAPY_SDR_HAS_TIME;
        timeNs = stream->counter * 1000;
        stream->counter += (long long)n;
        return (int)n;
    }

    // Echoes the sum of the I components of channel 0 into the "last_write_sum" setting.
    int writeStream(SoapySDR::Stream *handle, const void *const *buffs, const size_t numElems, int &flags,
        const long long, const long) override
    {
        auto *stream = reinterpret_cast<TestStream *>(handle);
        if(!stream->active) return SOAPY_SDR_STREAM_ERROR;
        double sum = 0;
        for(size_t i = 0; i < numElems; i++)
        {
            if(stream->format == SOAPY_SDR_CF32) sum += reinterpret_cast<const float *>(buffs[0])[2 * i];
            else sum += reinterpret_cast<const int16_t *>(buffs[0])[2 * i];
        }
        std::lock_guard<std::mutex> lock(_mutex);
        _settings["last_write_sum"] = std::to_string((long long)sum);
        _settings["last_write_flags"] = std::to_string(flags);
        return (int)numElems;
    }

    int readStreamStatus(SoapySDR::Stream *, size_t &chanMask, int &flags, long long &timeNs, const long) override
    {
        chanMask = 0x1;
        flags = SOAPY_SDR_END_BURST | SOAPY_SDR_HAS_TIME;
        timeNs = 123456789;
        return SOAPY_SDR_UNDERFLOW;
    }

    std::vector<std::string> listAntennas(const int, const size_t) const override { return {"RX", "TX/RX"}; }

    void setAntenna(const int, const size_t channel, const std::string &name) override
    {
        if(name != "RX" && name != "TX/RX") throw std::runtime_error("unknown antenna " + name);
        std::lock_guard<std::mutex> lock(_mutex);
        _antenna[channel] = name;
    }

    std::string getAntenna(const int, const size_t channel) const override
    {
        std::lock_guard<std::mutex> lock(_mutex);
        auto it = _antenna.find(channel);
        return it == _antenna.end() ? "RX" : it->second;
    }

    bool hasDCOffsetMode(const int, const size_t) const override { return true; }
    void setDCOffsetMode(const int, const size_t, const bool automatic) override { _dcMode = automatic; }
    bool getDCOffsetMode(const int, const size_t) const override { return _dcMode; }
    bool hasDCOffset(const int, const size_t) const override { return true; }
    void setDCOffset(const int, const size_t, const std::complex<double> &offset) override { _dc = offset; }
    std::complex<double> getDCOffset(const int, const size_t) const override { return _dc; }

    bool hasFrequencyCorrection(const int, const size_t) const override { return true; }
    void setFrequencyCorrection(const int, const size_t, const double value) override { _ppm = value; }
    double getFrequencyCorrection(const int, const size_t) const override { return _ppm; }

    std::vector<std::string> listGains(const int, const size_t) const override { return {"LNA", "VGA"}; }
    bool hasGainMode(const int, const size_t) const override { return true; }
    void setGainMode(const int, const size_t, const bool automatic) override { _agc = automatic; }
    bool getGainMode(const int, const size_t) const override { return _agc; }

    void setGain(const int direction, const size_t channel, const std::string &name, const double value) override
    {
        auto range = getGainRange(direction, channel, name);
        if(value < range.minimum() || value > range.maximum()) throw std::runtime_error("gain out of range");
        std::lock_guard<std::mutex> lock(_mutex);
        _gains[name] = value;
    }

    double getGain(const int, const size_t, const std::string &name) const override
    {
        std::lock_guard<std::mutex> lock(_mutex);
        auto it = _gains.find(name);
        return it == _gains.end() ? 0.0 : it->second;
    }

    SoapySDR::Range getGainRange(const int, const size_t, const std::string &name) const override
    {
        if(name == "LNA") return SoapySDR::Range(0, 40, 1);
        if(name == "VGA") return SoapySDR::Range(0, 60, 2);
        throw std::runtime_error("unknown gain " + name);
    }

    void setFrequency(const int, const size_t, const std::string &name, const double frequency,
        const SoapySDR::Kwargs &args) override
    {
        if(name != "RF") throw std::runtime_error("unknown frequency component " + name);
        if(frequency < 1e6 || frequency > 6e9) throw std::runtime_error("frequency out of range");
        std::lock_guard<std::mutex> lock(_mutex);
        _frequency = frequency;
        auto it = args.find("OFFSET");
        _lastTuneArgs = it == args.end() ? "" : it->second;
    }

    double getFrequency(const int, const size_t, const std::string &) const override { return _frequency; }
    std::vector<std::string> listFrequencies(const int, const size_t) const override { return {"RF"}; }

    SoapySDR::RangeList getFrequencyRange(const int, const size_t, const std::string &) const override
    {
        return {SoapySDR::Range(1e6, 6e9)};
    }

    void setSampleRate(const int, const size_t, const double rate) override
    {
        if(rate <= 0) throw std::runtime_error("invalid sample rate");
        _sampleRate = rate;
    }

    double getSampleRate(const int, const size_t) const override { return _sampleRate; }

    SoapySDR::RangeList getSampleRateRange(const int, const size_t) const override
    {
        return {SoapySDR::Range(250e3, 250e3), SoapySDR::Range(1e6, 3.2e6, 0.1e6)};
    }

    std::vector<double> listBandwidths(const int, const size_t) const override { return {1.5e6, 5e6, 10e6}; }

    std::vector<std::string> listSensors(void) const override { return {"temperature"}; }

    SoapySDR::ArgInfo getSensorInfo(const std::string &key) const override
    {
        SoapySDR::ArgInfo info;
        info.key = key;
        info.name = "Board temperature";
        info.units = "C";
        info.type = SoapySDR::ArgInfo::FLOAT;
        info.range = SoapySDR::Range(-40, 125);
        return info;
    }

    std::string readSensor(const std::string &key) const override
    {
        if(key == "temperature") return "42.5";
        throw std::runtime_error("unknown sensor " + key);
    }

    SoapySDR::ArgInfoList getSettingInfo(void) const override
    {
        SoapySDR::ArgInfo biasTee;
        biasTee.key = "biastee";
        biasTee.value = "false";
        biasTee.name = "Bias Tee";
        biasTee.description = "Enable antenna port bias tee";
        biasTee.type = SoapySDR::ArgInfo::BOOL;

        SoapySDR::ArgInfo mode;
        mode.key = "mode";
        mode.value = "normal";
        mode.type = SoapySDR::ArgInfo::STRING;
        mode.options = {"normal", "lowpower"};
        mode.optionNames = {"Normal", "Low power"};

        return {biasTee, mode};
    }

    void writeSetting(const std::string &key, const std::string &value) override
    {
        std::lock_guard<std::mutex> lock(_mutex);
        _settings[key] = value;
    }

    std::string readSetting(const std::string &key) const override
    {
        std::lock_guard<std::mutex> lock(_mutex);
        if(key == "last_tune_offset") return _lastTuneArgs;
        auto it = _settings.find(key);
        return it == _settings.end() ? "" : it->second;
    }

    void writeRegister(const std::string &, const unsigned addr, const unsigned value) override
    {
        std::lock_guard<std::mutex> lock(_mutex);
        _registers[addr] = value;
    }

    unsigned readRegister(const std::string &, const unsigned addr) const override
    {
        std::lock_guard<std::mutex> lock(_mutex);
        auto it = _registers.find(addr);
        return it == _registers.end() ? 0 : it->second;
    }

private:
    mutable std::mutex _mutex;
    std::map<size_t, std::string> _antenna;
    std::map<std::string, double> _gains;
    std::map<std::string, std::string> _settings;
    std::map<unsigned, unsigned> _registers;
    std::string _lastTuneArgs;
    double _frequency = 100e6;
    double _sampleRate = 1e6;
    double _ppm = 0;
    bool _agc = false;
    bool _dcMode = false;
    std::complex<double> _dc{0, 0};
};

SoapySDR::KwargsList findTestDevice(const SoapySDR::Kwargs &args)
{
    SoapySDR::KwargsList results;
    auto it = args.find("driver");
    if(it == args.end() || it->second != "jsoapytest") return results;

    SoapySDR::Kwargs device = args; // keep extra args (e.g. "fail") so they reach makeTestDevice
    device["driver"] = "jsoapytest";
    device["label"] = "jSoapySDR test device";
    device["serial"] = "0001";
    results.push_back(device);
    return results;
}

SoapySDR::Device *makeTestDevice(const SoapySDR::Kwargs &args)
{
    auto it = args.find("fail");
    if(it != args.end()) throw std::runtime_error("requested failure: " + it->second);
    return new TestDevice();
}

SoapySDR::Registry registerTestDevice("jsoapytest", &findTestDevice, &makeTestDevice, SOAPY_SDR_ABI_VERSION);
}
