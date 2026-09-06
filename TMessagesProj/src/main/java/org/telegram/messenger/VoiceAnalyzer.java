package org.telegram.messenger;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Televa voice clone sample analyzer.
 * Decodes an audio or video file (up to 30 s of its audio track), then measures
 * the speaker's fundamental frequency (f0) with normalized autocorrelation.
 * The result drives the live-call clone preset: the engine shifts the caller's
 * pitch by targetF0 / callerF0 to approximate the sample speaker's voice.
 */
public class VoiceAnalyzer {

    public static class Result {
        public boolean ok;
        public float f0;          // median voiced f0 in Hz
        public int durationMs;    // analyzed duration
        public String error;
    }

    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int MAX_ANALYZE_MS = 30_000;
    private static final int MIN_ANALYZE_MS = 10_000;
    private static final int FRAME_MS = 40;

    public static Result analyze(String path) {
        Result result = new Result();
        File file = new File(path);
        if (!file.exists() || file.length() == 0) {
            result.error = "file missing";
            return result;
        }
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(path);
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = f;
                    break;
                }
            }
            if (trackIndex < 0 || format == null) {
                result.error = "no audio track";
                return result;
            }
            extractor.selectTrack(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            List<Integer> pcm = new ArrayList<>(); // resampled mono @16k
            int srcRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int srcChannels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            if (srcRate <= 0) srcRate = 44100;
            if (srcChannels <= 0) srcChannels = 1;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;
            int maxSamples = TARGET_SAMPLE_RATE * MAX_ANALYZE_MS / 1000;

            while (!sawOutputEOS && pcm.size() < maxSamples) {
                if (!sawInputEOS) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        java.nio.ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        int sampleSize = extractor.readSampleData(inBuf, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sawInputEOS = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                boolean more = true;
                while (more) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = codec.getOutputFormat();
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) srcChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    } else if (outIndex >= 0) {
                        java.nio.ByteBuffer outBuf = codec.getOutputBuffer(outIndex);
                        if (outBuf != null && info.size > 0) {
                            outBuf.order(ByteOrder.LITTLE_ENDIAN);
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            ShortBuffer shorts = outBuf.asShortBuffer();
                            int frames = shorts.remaining() / srcChannels;
                            double step = (double) srcRate / TARGET_SAMPLE_RATE;
                            double pos = 0.0;
                            for (int i = 0; i < frames && pcm.size() < maxSamples; i++) {
                                int idx = (int) pos;
                                if (idx >= frames) break;
                                int sum = 0;
                                for (int c = 0; c < srcChannels; c++) {
                                    sum += shorts.get(idx * srcChannels + c);
                                }
                                pcm.add(sum / srcChannels);
                                pos += step;
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEOS = true;
                        }
                    }
                    if (sawOutputEOS || pcm.size() >= maxSamples) {
                        more = false;
                    } else {
                        outIndex = codec.dequeueOutputBuffer(info, 10_000);
                        if (outIndex < 0 && outIndex != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            more = false;
                        }
                    }
                }
            }

            int durationMs = pcm.size() * 1000 / TARGET_SAMPLE_RATE;
            result.durationMs = durationMs;
            if (durationMs < MIN_ANALYZE_MS) {
                result.error = "sample too short";
                return result;
            }

            // autocorrelation f0 detection on 40 ms frames
            int frameLen = TARGET_SAMPLE_RATE * FRAME_MS / 1000; // 640
            int minLag = TARGET_SAMPLE_RATE / 400;
            int maxLag = TARGET_SAMPLE_RATE / 60;
            List<Float> candidates = new ArrayList<>();
            for (int start = 0; start + frameLen <= pcm.size(); start += frameLen) {
                double energy = 0;
                for (int i = 0; i < frameLen; i++) {
                    double v = pcm.get(start + i);
                    energy += v * v;
                }
                double rms = Math.sqrt(energy / frameLen);
                if (rms < 250) continue; // silence
                double norm = energy > 0 ? energy : 1;
                double bestPeak = 0;
                int bestLag = 0;
                for (int lag = minLag; lag <= maxLag && lag < frameLen; lag++) {
                    double sum = 0;
                    for (int i = 0; i + lag < frameLen; i++) {
                        sum += (double) pcm.get(start + i) * (double) pcm.get(start + i + lag);
                    }
                    double nac = sum / norm;
                    if (nac > bestPeak) {
                        bestPeak = nac;
                        bestLag = lag;
                    }
                }
                if (bestLag > 0 && bestPeak >= 0.30) {
                    candidates.add((float) TARGET_SAMPLE_RATE / bestLag);
                }
            }
            if (candidates.size() < 5) {
                result.error = "no clear voice found";
                return result;
            }
            Collections.sort(candidates);
            float f0 = candidates.get(candidates.size() / 2);
            if (f0 < 60 || f0 > 400) {
                result.error = "unrecognizable voice";
                return result;
            }
            result.f0 = f0;
            result.ok = true;
            return result;
        } catch (Exception e) {
            FileLog.e(e);
            result.error = "failed to read file";
            return result;
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignored) {
            }
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
        }
    }
}
