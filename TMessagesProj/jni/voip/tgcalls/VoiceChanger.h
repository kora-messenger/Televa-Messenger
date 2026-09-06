/*
 * Televa Voice Changer — real-time voice conversion for live audio/video calls.
 * Part of the Televa Messenger custom feature set.
 *
 * Plugs into the WebRTC Audio Processing Module capture path (after AEC/NS,
 * before Opus encoding), so it applies to both 1:1 audio and video calls.
 *
 * Engine: preset-based real-time DSP (v1), architected so a neural
 * sample-based conversion engine can be dropped into the same slot later.
 */

#ifndef TELEVA_VOICE_CHANGER_H
#define TELEVA_VOICE_CHANGER_H

#include <atomic>
#include <cstdint>
#include <vector>

#ifdef __cplusplus
extern "C" {
#endif

#include "../sonic/sonic.h"

#ifdef __cplusplus
}
#endif

namespace tgcalls {

struct VoicePreset {
    int id;                 // 0 = off
    const char *name;       // stable identifier key for UI (English fallback)
    float pitch;            // 1.0 = unchanged, <1.0 deeper, >1.0 higher
    float ringModHz;        // 0 = off; ring modulation carrier frequency
    float ringModDepth;     // 0..1
    float lowpassHz;        // 0 = off
    float highpassHz;       // 0 = off
    float reverbDelayMs;    // 0 = off; simple comb reverb delay
    float reverbGain;       // 0..1
    float drive;            // 0 = off; soft-clipping amount for radio-style grit
    float gain;             // output trim (1.0 = unchanged)
};

class VoiceChanger {
public:
    // Preset id for the sample-cloned voice. The target speaker's fundamental
    // frequency is measured from an uploaded 30 s sample and delivered through
    // `cloneTargetF0` (Hz). On clone mode the engine first measures the
    // caller's own pitch on the live capture stream, then shifts pitch by
    // targetF0 / callerF0 to approximate the target speaker's voice character.
    static constexpr int kPresetClone = 100;

    // Called on the capture audio thread for every 10 ms frame.
    // `samples` holds numFrames mono floats in the int16 numeric range
    // (matching webrtc::AudioBuffer conventions).
    // Both parameters are read atomically; changes take effect on the next frame.
    VoiceChanger(std::atomic<int> *preset, std::atomic<float> *cloneTargetF0);
    ~VoiceChanger();

    void Process(float *samples, int numFrames, int sampleRate);

    // Preset table shared with the UI. Index by preset id (0 = off).
    static const VoicePreset *PresetById(int id);

private:
    void Reconfigure(int preset, int sampleRate);
    void ProcessEffects(int16_t *samples, int numFrames);
    void CloneDetectFrame(const int16_t *samples, int numFrames);
    void CloneEngage();

    std::atomic<int> *_preset = nullptr;
    std::atomic<float> *_cloneTargetF0 = nullptr;
    int _currentPreset = 0;
    int _sampleRate = 48000;

    // clone mode state
    bool _cloneDetecting = false;        // measuring the caller's own pitch
    double _cloneUserF0 = 0.0;           // measured caller f0 (Hz); 0 = unknown
    double _clonePitchRatio = 1.0;
    int _cloneVoicedFrames = 0;
    int _cloneElapsedMs = 0;
    std::vector<int16_t> _cloneDetectBuffer;  // recent capture for autocorrelation
    std::vector<double> _cloneF0Candidates;

    sonicStream _sonic = nullptr;
    std::vector<int16_t> _fifo;   // pitch-shifted output queue
    size_t _fifoReadPos = 0;

    // ring mod oscillator phase
    double _ringPhase = 0.0;

    // one-pole filter states
    double _lowpassState = 0.0;
    double _highpassState = 0.0;
    double _lowpassCoef = 0.0;
    double _highpassCoef = 0.0;

    // comb reverb delay line
    std::vector<int16_t> _reverbBuffer;
    size_t _reverbPos = 0;
    float _reverbGain = 0.0f;

    // pre-allocated per-frame scratch buffers (no heap allocs on the audio thread)
    std::vector<int16_t> _inputBuffer;
    std::vector<int16_t> _outBuffer;
    std::vector<int16_t> _chunkBuffer;
};

} // namespace tgcalls

#endif // TELEVA_VOICE_CHANGER_H
