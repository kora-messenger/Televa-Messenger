/*
 * Televa Voice Changer — real-time DSP voice conversion engine.
 *
 * Signal chain (capture side, per 10 ms frame):
 *   mic PCM -> [Sonic WSOLA pitch shift] -> FIFO -> [ring mod]
 *           -> [one-pole lowpass] -> [one-pole highpass] -> [comb reverb]
 *           -> [soft-clip drive] -> [output gain] -> to encoder
 *
 * Pitch shifting uses the Sonic library (Apache 2.0, vendored in jni/voip/sonic)
 * in streaming mode: duration-preserving, low-latency, energy-preserving.
 *
 * When the preset is 0 the engine is fully bypassed (bit-exact pass-through),
 * so calls with the voice changer off behave exactly like stock Televa.
 */

#include "VoiceChanger.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace tgcalls {

// v1 preset library. Tuned for speech intelligibility on a 48 kHz mono capture
// stream. These are "voice character" presets — a neural sample-based cloning
// engine (v2) will reuse this exact slot.
static const VoicePreset kPresets[] = {
    // id  name          pitch  ringHz depth  lpHz  hpHz  revMs revGain drive gain
    {  0, "off",        1.00f, 0.0f,  0.0f,    0,    0,    0,     0.00f, 0.0f, 1.00f },
    {  1, "deep_male",  0.80f, 0.0f,  0.0f,    0,    0,    0,     0.00f, 0.0f, 1.00f },
    {  2, "monster",    0.60f, 0.0f,  0.0f, 2400,  100,    0,     0.00f, 0.3f, 1.00f },
    {  3, "soft_female",1.32f, 0.0f,  0.0f,    0,  150,    0,     0.00f, 0.0f, 0.92f },
    {  4, "chipmunk",   1.55f, 0.0f,  0.0f,    0,  250,    0,     0.00f, 0.0f, 0.90f },
    {  5, "robot",      1.00f, 55.0f, 0.95f, 3800,  200,    0,     0.00f, 0.2f, 1.00f },
    {  6, "alien",      1.42f, 30.0f, 0.65f,    0,  200,    0,     0.00f, 0.0f, 0.95f },
    {  7, "radio",      1.00f, 0.0f,  0.0f, 3400,  300,    0,     0.00f, 0.9f, 1.10f },
    {  8, "cave",       0.87f, 0.0f,  0.0f,    0,  120,   90,     0.35f, 0.0f, 1.00f },
    {  9, "squeaky",    1.85f, 0.0f,  0.0f,    0,  300,    0,     0.00f, 0.0f, 0.85f },
    { 10, "ghost",      0.93f, 8.0f,  0.40f, 3200,  150,  120,     0.45f, 0.0f, 1.00f },
};

const VoicePreset *VoiceChanger::PresetById(int id) {
    if (id < 0 || id >= (int)(sizeof(kPresets) / sizeof(kPresets[0]))) {
        return nullptr;
    }
    return &kPresets[id];
}

static float ClampFloat(double v, float lo, float hi) {
    return (float)std::max((double)lo, std::min((double)hi, v));
}

static double OnePoleCoefficient(double cutoffHz, int sampleRate) {
    if (cutoffHz <= 0.0 || sampleRate <= 0) {
        return 0.0;
    }
    double rc = 1.0 / (2.0 * M_PI * cutoffHz);
    double dt = 1.0 / sampleRate;
    return dt / (rc + dt);
}

VoiceChanger::VoiceChanger(std::atomic<int> *preset, std::atomic<float> *cloneTargetF0)
: _preset(preset), _cloneTargetF0(cloneTargetF0) {
    _fifo.reserve(4096);
    _reverbBuffer.reserve(16384);
    _cloneDetectBuffer.reserve(8192);
}

VoiceChanger::~VoiceChanger() {
    if (_sonic != nullptr) {
        sonicDestroyStream(_sonic);
        _sonic = nullptr;
    }
}

void VoiceChanger::Reconfigure(int preset, int sampleRate) {
    if (_sonic != nullptr) {
        sonicDestroyStream(_sonic);
        _sonic = nullptr;
    }
    if (preset == kPresetClone) {
        // Clone mode: sonic is created but stays inactive until the caller's own
        // pitch is measured on the live stream (see CloneEngage).
        _sonic = sonicCreateStream(sampleRate, 1);
        if (_sonic != nullptr) {
            sonicSetSpeed(_sonic, 1.0f);
            sonicSetRate(_sonic, 1.0f);
            sonicSetQuality(_sonic, 1);
            sonicSetChordPitch(_sonic, 0);
        }
        _cloneDetecting = true;
        _cloneUserF0 = 0.0;
        _clonePitchRatio = 1.0;
        _cloneVoicedFrames = 0;
        _cloneElapsedMs = 0;
        _cloneDetectBuffer.clear();
        _cloneF0Candidates.clear();
        _fifo.clear();
        _fifoReadPos = 0;
        _ringPhase = 0.0;
        _lowpassState = 0.0;
        _highpassState = 0.0;
        _lowpassCoef = 0.0;
        _highpassCoef = 0.0;
        _reverbBuffer.clear();
        _reverbPos = 0;
        _reverbGain = 0.0f;
        _currentPreset = preset;
        _sampleRate = sampleRate;
        return;
    }
    _cloneDetecting = false;

    const VoicePreset *p = PresetById(preset);
    if (p != nullptr && p->pitch != 1.0f) {
        _sonic = sonicCreateStream(sampleRate, 1);
        if (_sonic != nullptr) {
            sonicSetPitch(_sonic, ClampFloat(p->pitch, 0.25f, 4.0f));
            sonicSetSpeed(_sonic, 1.0f);
            sonicSetRate(_sonic, 1.0f);
            sonicSetQuality(_sonic, 1);    // higher quality WSOLA
            sonicSetChordPitch(_sonic, 0); // pitch-shift the whole voice, not per-chord
        }
    }
    _fifo.clear();
    _fifoReadPos = 0;
    _ringPhase = 0.0;
    _lowpassState = 0.0;
    _highpassState = 0.0;
    _lowpassCoef = p != nullptr ? OnePoleCoefficient(p->lowpassHz, sampleRate) : 0.0;
    _highpassCoef = p != nullptr ? OnePoleCoefficient(p->highpassHz, sampleRate) : 0.0;

    _reverbBuffer.clear();
    _reverbPos = 0;
    _reverbGain = 0.0f;
    if (p != nullptr && p->reverbDelayMs > 0.0f && p->reverbGain > 0.0f) {
        size_t delaySamples = (size_t)(p->reverbDelayMs * sampleRate / 1000.0);
        if (delaySamples < 16) {
            delaySamples = 16;
        }
        _reverbBuffer.assign(delaySamples, 0);
        _reverbGain = ClampFloat(p->reverbGain, 0.0f, 0.9f);
    }

    _currentPreset = preset;
    _sampleRate = sampleRate;
}

void VoiceChanger::ProcessEffects(int16_t *samples, int numFrames) {
    if (_currentPreset == kPresetClone) {
        return; // clone mode is a pure pitch match — no character effects
    }
    const VoicePreset *p = PresetById(_currentPreset);
    if (p == nullptr) {
        return;
    }
    const double ringInc = 2.0 * M_PI * p->ringModHz / _sampleRate;

    for (int i = 0; i < numFrames; i++) {
        double s = samples[i];

        // ring modulation
        if (p->ringModHz > 0.0f && p->ringModDepth > 0.0f) {
            double osc = std::sin(_ringPhase);
            _ringPhase += ringInc;
            if (_ringPhase > 2.0 * M_PI) {
                _ringPhase -= 2.0 * M_PI;
            }
            double depth = ClampFloat(p->ringModDepth, 0.0f, 1.0f);
            s = s * (1.0 - depth) + s * osc * depth;
        }

        // one-pole lowpass
        if (_lowpassCoef > 0.0) {
            _lowpassState += _lowpassCoef * (s - _lowpassState);
            s = _lowpassState;
        }

        // one-pole highpass (subtracted lowpassed tail)
        if (_highpassCoef > 0.0) {
            _highpassState += _highpassCoef * (s - _highpassState);
            s = s - _highpassState;
        }

        // comb reverb
        if (!_reverbBuffer.empty() && _reverbGain > 0.0f) {
            size_t delayLen = _reverbBuffer.size();
            size_t delayedPos = _reverbPos;
            double delayed = _reverbBuffer[delayedPos];
            _reverbBuffer[_reverbPos] = (int16_t)std::lround(std::max(-32768.0, std::min(32767.0, s)));
            _reverbPos = (_reverbPos + 1) % delayLen;
            s = s + delayed * _reverbGain;
        }

        // drive (soft clip)
        if (p->drive > 0.0f) {
            double norm = s / 32768.0;
            double driven = std::tanh(norm * (1.0 + p->drive * 7.0));
            s = driven * 32768.0;
        }

        // output gain trim
        s *= p->gain;

        samples[i] = (int16_t)std::lround(std::max(-32768.0, std::min(32767.0, s)));
    }
}

void VoiceChanger::Process(float *samples, int numFrames, int sampleRate) {
    if (_preset == nullptr || numFrames <= 0) {
        return;
    }
    int preset = _preset->load(std::memory_order_relaxed);
    if (preset <= 0) {
        if (_currentPreset != 0) {
            Reconfigure(0, sampleRate);
        }
        return; // bit-exact bypass
    }
    if (preset != _currentPreset || sampleRate != _sampleRate) {
        Reconfigure(preset, sampleRate);
    }
    if (preset == kPresetClone) {
        if (_cloneDetecting) {
            // still measuring the caller's own pitch — pass through untouched
            return;
        }
    }

    const int maxFifoSamples = sampleRate / 5;   // 200 ms hard cap on internal latency
    const int targetFifoSamples = sampleRate / 100; // ~10 ms steady-state target

    // 1) float(int16 range) -> int16 (pre-allocated scratch, no heap alloc here)
    _inputBuffer.resize(numFrames);
    std::vector<int16_t> &input = _inputBuffer;
    for (int i = 0; i < numFrames; i++) {
        float f = samples[i];
        if (f > 32767.0f) f = 32767.0f;
        if (f < -32768.0f) f = -32768.0f;
        input[i] = (int16_t)std::lrintf(f);
    }

    // clone mode: measure the caller's own pitch on the raw capture
    if (_currentPreset == kPresetClone) {
        CloneDetectFrame(input.data(), numFrames);
        if (_cloneDetecting) {
            return; // measuring — untouched pass-through
        }
    }

    // 2) pitch shift through Sonic (if enabled), queue into FIFO
    if (_sonic != nullptr) {
        sonicWriteShortToStream(_sonic, input.data(), numFrames);
        int available = 0;
        // read everything sonic currently has ready
        _chunkBuffer.resize(2048);
        std::vector<int16_t> &chunk = _chunkBuffer;
        while ((available = sonicReadShortFromStream(_sonic, chunk.data(), 2048)) > 0) {
            _fifo.insert(_fifo.end(), chunk.begin(), chunk.begin() + available);
        }
    } else {
        _fifo.insert(_fifo.end(), input.begin(), input.end());
    }

    // 3) latency management: if the FIFO grows past the cap, drop oldest;
    //    if it is far below target, pad by repeating the last frame so the
    //    WSOLA time-stretcher's input backlog doesn't stall the stream.
    if ((int)_fifo.size() - (int)_fifoReadPos > maxFifoSamples) {
        _fifo.erase(_fifo.begin(), _fifo.begin() + _fifoReadPos);
        _fifoReadPos = 0;
        int excess = (int)_fifo.size() - maxFifoSamples;
        if (excess > 0) {
            _fifo.erase(_fifo.begin(), _fifo.begin() + excess);
        }
    } else if ((int)_fifo.size() - (int)_fifoReadPos < targetFifoSamples / 2) {
        int16_t last = _fifo.empty() ? 0 : _fifo.back();
        int pad = numFrames;
        _fifo.insert(_fifo.end(), pad, last);
    }

    // 4) consume exactly numFrames for this callback; never block the capture thread
    _outBuffer.resize(numFrames);
    std::vector<int16_t> &out = _outBuffer;
    for (int i = 0; i < numFrames; i++) {
        if (_fifoReadPos < _fifo.size()) {
            out[i] = _fifo[_fifoReadPos++];
        } else {
            out[i] = 0; // momentary underrun during reconfiguration
        }
    }
    // periodic FIFO compaction
    if (_fifoReadPos > 16384) {
        _fifo.erase(_fifo.begin(), _fifo.begin() + _fifoReadPos);
        _fifoReadPos = 0;
    }
    // hard cap on memory
    if (_fifo.size() > (size_t)(sampleRate / 2)) {
        _fifo.erase(_fifo.begin(), _fifo.begin() + (_fifo.size() - maxFifoSamples));
        _fifoReadPos = 0;
    }

    // 5) character effects
    ProcessEffects(out.data(), numFrames);

    // 6) int16 -> float(int16 range)
    for (int i = 0; i < numFrames; i++) {
        samples[i] = (float)out[i];
    }
}

// --- Clone mode: measure the caller's own pitch, then match the target ---

// Normalized autocorrelation pitch detection on a 40 ms window.
// Downsamples the 48 kHz stream 4x for speed, searches 60..400 Hz.
static double DetectF0(const int16_t *samples, int numSamples, int sampleRate) {
    const int dsFactor = 4;
    const int dsRate = sampleRate / dsFactor;
    int dsCount = numSamples / dsFactor;
    static int16_t ds[1024];
    if (dsCount > 1024) dsCount = 1024;
    double energy = 0.0;
    for (int i = 0; i < dsCount; i++) {
        int16_t v = samples[i * dsFactor];
        ds[i] = v;
        energy += (double)v * v;
    }
    if (dsCount < 240 || energy / dsCount < 250.0 * 250.0) {
        return 0.0; // too short or too quiet
    }
    const int minLag = dsRate / 400; // 400 Hz
    const int maxLag = dsRate / 60;  // 60 Hz
    double bestPeak = 0.0;
    int bestLag = 0;
    double norm = 0.0;
    for (int i = 0; i < dsCount; i++) {
        double v = ds[i];
        norm += v * v;
    }
    norm = norm > 0.0 ? norm : 1.0;
    for (int lag = minLag; lag <= maxLag && lag < dsCount; lag++) {
        double sum = 0.0;
        for (int i = 0; i + lag < dsCount; i++) {
            sum += (double)ds[i] * (double)ds[i + lag];
        }
        double nac = sum / norm;
        // simple center clipping threshold
        if (nac > bestPeak) {
            bestPeak = nac;
            bestLag = lag;
        }
    }
    if (bestLag == 0 || bestPeak < 0.30) {
        return 0.0; // unvoiced
    }
    return (double)dsRate / bestLag;
}

void VoiceChanger::CloneDetectFrame(const int16_t *samples, int numFrames) {
    if (!_cloneDetecting) {
        return;
    }
    _cloneElapsedMs += numFrames * 1000 / _sampleRate;

    // keep the most recent 40 ms for autocorrelation
    _cloneDetectBuffer.insert(_cloneDetectBuffer.end(), samples, samples + numFrames);
    size_t keep = (size_t)(_sampleRate * 40 / 1000);
    if (_cloneDetectBuffer.size() > keep) {
        _cloneDetectBuffer.erase(_cloneDetectBuffer.begin(), _cloneDetectBuffer.end() - keep);
    }
    if (_cloneDetectBuffer.size() < keep) {
        if (_cloneElapsedMs > 6000) {
            CloneEngage(); // give up measuring, use the default
        }
        return;
    }

    double f0 = DetectF0(_cloneDetectBuffer.data(), (int)_cloneDetectBuffer.size(), _sampleRate);
    if (f0 > 0.0) {
        _cloneF0Candidates.push_back(f0);
        _cloneVoicedFrames++;
    }

    const bool enoughVoiced = _cloneVoicedFrames >= 20;   // ~20 voiced windows
    const bool timeout = _cloneElapsedMs > 6000;          // max 6 s of measuring
    if (enoughVoiced || timeout) {
        CloneEngage();
    }
}

void VoiceChanger::CloneEngage() {
    double userF0 = 135.0; // typical male voice fallback
    if (!_cloneF0Candidates.empty()) {
        std::sort(_cloneF0Candidates.begin(), _cloneF0Candidates.end());
        userF0 = _cloneF0Candidates[_cloneF0Candidates.size() / 2];
    }
    double targetF0 = 135.0;
    if (_cloneTargetF0 != nullptr) {
        float t = _cloneTargetF0->load(std::memory_order_relaxed);
        if (t > 50.0f && t < 500.0f) {
            targetF0 = t;
        }
    }
    double ratio = targetF0 / userF0;
    if (ratio < 0.50) ratio = 0.50;
    if (ratio > 2.00) ratio = 2.00;
    _cloneUserF0 = userF0;
    _clonePitchRatio = ratio;
    _cloneDetecting = false;
    if (_sonic != nullptr) {
        sonicSetPitch(_sonic, (float)ratio);
    }
    // reset the FIFO so no partially-processed audio from detection leaks out
    _fifo.clear();
    _fifoReadPos = 0;
}

} // namespace tgcalls
