package org.telegram.ui.Components;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.WorkerThread;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.Vosk;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * TelevaTranscribe — free on-device voice note transcription for every Televa user.
 *
 * Telegram's own transcription runs on Telegram's servers and is only available
 * to Premium accounts (with a tiny trial quota for everyone else). Televa users
 * are real Telegram accounts, so the server almost always refuses.
 *
 * This engine is the fallback: when the server request fails, it decodes the
 * voice note locally (Ogg Opus -> PCM 16 kHz mono), downloads a small offline
 * Vosk speech model for the relevant language (one time, ~40 MB, then cached
 * forever), and runs recognition entirely on the device. The result is fed
 * back through the same TranscribeButton.finishTranscription pipeline the
 * server flow uses, so the text appears at the bottom of the voice note with
 * the stock Telegram UI.
 *
 * Primary decode path: MediaExtractor + MediaCodec (all modern Androids).
 * Fallback decode path for older devices that cannot extract Ogg Opus:
 * the in-tree ExoPlayer OggExtractor + MediaCodec fed manually.
 */
public class TelevaTranscribe {

    public interface Callback {
        void onResult(String text);
        void onError(String reason);
    }

    private static final String TAG = "TelevaTranscribe";
    private static final String MODEL_BASE_URL = "https://alphacephei.com/vosk/models/";
    private static final long MAX_DURATION_SECONDS = 600; // 10 minutes, same spirit as Telegram's own cap

    // Small (16 kHz) offline Vosk models, keyed by ISO language code.
    // Verified against https://alphacephei.com/vosk/models
    private static final Map<String, String> MODEL_ZIPS = new HashMap<>();
    static {
        MODEL_ZIPS.put("en", "vosk-model-small-en-us-0.15");
        MODEL_ZIPS.put("en-in", "vosk-model-small-en-in-0.4");
        MODEL_ZIPS.put("fr", "vosk-model-small-fr-0.22");
        MODEL_ZIPS.put("de", "vosk-model-small-de-0.15");
        MODEL_ZIPS.put("es", "vosk-model-small-es-0.42");
        MODEL_ZIPS.put("pt", "vosk-model-small-pt-0.3");
        MODEL_ZIPS.put("it", "vosk-model-small-it-0.22");
        MODEL_ZIPS.put("ru", "vosk-model-small-ru-0.22");
        MODEL_ZIPS.put("uk", "vosk-model-small-uk-v3-small");
        MODEL_ZIPS.put("tr", "vosk-model-small-tr-0.3");
        MODEL_ZIPS.put("fa", "vosk-model-small-fa-0.42");
        MODEL_ZIPS.put("ar", "vosk-model-small-ar-tn-0.1-linto");
        MODEL_ZIPS.put("hi", "vosk-model-small-hi-0.22");
        MODEL_ZIPS.put("ja", "vosk-model-small-ja-0.22");
        MODEL_ZIPS.put("ko", "vosk-model-small-ko-0.22");
        MODEL_ZIPS.put("zh", "vosk-model-small-cn-0.22");
        MODEL_ZIPS.put("vi", "vosk-model-small-vn-0.4");
        MODEL_ZIPS.put("nl", "vosk-model-small-nl-0.22");
        MODEL_ZIPS.put("pl", "vosk-model-small-pl-0.22");
        MODEL_ZIPS.put("sv", "vosk-model-small-sv-rhasspy-0.15");
        MODEL_ZIPS.put("cs", "vosk-model-small-cs-0.4-rhasspy");
        MODEL_ZIPS.put("ca", "vosk-model-small-ca-0.4");
        MODEL_ZIPS.put("gu", "vosk-model-small-gu-0.42");
        MODEL_ZIPS.put("ka", "vosk-model-small-ka-0.42");
        MODEL_ZIPS.put("ky", "vosk-model-small-ky-0.42");
        MODEL_ZIPS.put("kz", "vosk-model-small-kz-0.42");
        MODEL_ZIPS.put("te", "vosk-model-small-te-0.42");
        MODEL_ZIPS.put("tg", "vosk-model-small-tg-0.22");
        MODEL_ZIPS.put("uz", "vosk-model-small-uz-0.22");
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TelevaTranscribe");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setDaemon(true);
        return t;
    });

    private static final HashSet<String> inFlight = new HashSet<>();

    // Vosk models are heavyweight; keep at most one alive and reuse it.
    private static final Object modelLock = new Object();
    private static volatile Model cachedModel;
    private static String cachedModelKey;

    private static final AtomicBoolean voskInitialized = new AtomicBoolean(false);

    /**
     * Transcribes the voice note of the given message entirely on this device,
     * on a background thread. Exactly one transcription per message runs at a
     * time; repeated taps while one is running are ignored.
     */
    public static void transcribe(MessageObject messageObject, Callback callback) {
        if (messageObject == null || messageObject.messageOwner == null) {
            if (callback != null) callback.onError("Nothing to transcribe");
            return;
        }
        final String key = messageObject.currentAccount + ":" + messageObject.getDialogId() + ":" + messageObject.getId();
        synchronized (inFlight) {
            if (!inFlight.add(key)) {
                FileLog.d(TAG + ": already transcribing " + key);
                return;
            }
        }
        executor.execute(() -> {
            String text = null;
            String error = null;
            try {
                text = transcribeInternal(messageObject);
            } catch (Throwable e) {
                FileLog.e(TAG + ": on-device transcription failed", e);
                error = e instanceof TranscribeException ? e.getMessage() : "Transcription failed on this device";
            } finally {
                synchronized (inFlight) {
                    inFlight.remove(key);
                }
            }
            final String finalText = text;
            final String finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (finalText != null) {
                    if (callback != null) callback.onResult(finalText);
                } else {
                    if (callback != null) callback.onError(finalError != null ? finalError : "Transcription failed");
                }
            });
        });
    }

    private static class TranscribeException extends Exception {
        public TranscribeException(String message) {
            super(message);
        }
    }

    @WorkerThread
    private static String transcribeInternal(MessageObject messageObject) throws Exception {
        int duration = (int) messageObject.getDuration();
        if (duration > MAX_DURATION_SECONDS) {
            throw new TranscribeException("Voice message is too long to transcribe on this device");
        }

        File voiceFile;
        try {
            voiceFile = FileLoader.getInstance(messageObject.currentAccount).getPathToMessage(messageObject.messageOwner);
        } catch (Throwable e) {
            voiceFile = null;
        }
        if (voiceFile == null || !voiceFile.exists() || voiceFile.length() <= 0) {
            // Fall back to the local attach path for freshly recorded outgoing notes.
            if (messageObject.messageOwner.attachPath != null) {
                File f = new File(messageObject.messageOwner.attachPath);
                if (f.exists() && f.length() > 0) {
                    voiceFile = f;
                }
            }
        }
        if (voiceFile == null || !voiceFile.exists() || voiceFile.length() <= 0) {
            throw new TranscribeException("Voice message not downloaded yet. Play it once, then try again.");
        }

        String language = detectLanguage(messageObject);
        File modelDir = ensureModel(language);

        long started = SystemClock.elapsedRealtime();
        byte[] pcm = decodeToPcm16kMono(voiceFile);
        if (pcm == null || pcm.length < 320) {
            throw new TranscribeException("Couldn't decode this voice message on this device");
        }

        String text = recognize(modelDir, language, pcm);
        FileLog.d(TAG + ": recognized in " + (SystemClock.elapsedRealtime() - started) + "ms, length=" + text.length());
        if (text.trim().isEmpty()) {
            throw new TranscribeException("No speech recognized in this voice message");
        }
        return text;
    }

    /**
     * Chooses the offline model language. Order of preference:
     * 1. the sender's Telegram language_code (people usually talk in their language),
     * 2. the Telegram app UI language,
     * 3. the device locale,
     * 4. English.
     * Unknown languages without a model also fall back to English.
     */
    private static String detectLanguage(MessageObject messageObject) {
        try {
            long senderId = messageObject.getSenderId();
            if (senderId > 0) {
                TLRPC.User sender = MessagesController.getInstance(messageObject.currentAccount).getUser(senderId);
                if (sender != null && !TextUtils.isEmpty(sender.lang_code)) {
                    String code = normalizeLanguage(sender.language_code);
                    if (code != null) {
                        return code;
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        try {
            Locale locale = LocaleController.getInstance().getCurrentLocale();
            String code = normalizeLanguage(locale.getLanguage());
            if (code != null) {
                return code;
            }
        } catch (Throwable ignore) {
        }
        try {
            String code = normalizeLanguage(Locale.getDefault().getLanguage());
            if (code != null) {
                return code;
            }
        } catch (Throwable ignore) {
        }
        return "en";
    }

    /**
     * Maps a language tag to a model key that has an offline model,
     * or null when this language has no small model at all.
     */
    private static String normalizeLanguage(String code) {
        if (code == null) {
            return null;
        }
        code = code.toLowerCase(Locale.US).replace('_', '-').trim();
        if (code.isEmpty()) {
            return null;
        }
        if (MODEL_ZIPS.containsKey(code)) {
            return code;
        }
        // e.g. "en-US" -> "en", "pt-BR" -> "pt", "en-IN" -> "en-in"
        int dash = code.indexOf('-');
        if (dash > 0) {
            String broad = code.substring(0, dash);
            if (MODEL_ZIPS.containsKey(broad)) {
                return broad;
            }
        }
        if (code.startsWith("zh")) {
            return "zh";
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Model management
    // ---------------------------------------------------------------------

    private static File modelsRoot() {
        Context context = ApplicationLoader.applicationContext;
        return new File(context.getFilesDir(), "televa_vosk");
    }

    private static File modelDir(String language) {
        String zip = MODEL_ZIPS.get(language);
        String folder = zip != null ? zip : MODEL_ZIPS.get("en");
        return new File(modelsRoot(), folder);
    }

    /**
     * Returns a ready-to-load model directory, downloading and unzipping the
     * offline model once if needed. Thread-safe; a concurrent second caller
     * waits for the first download to finish instead of duplicating it.
     */
    private static File ensureModel(String language) throws Exception {
        final String zipName = MODEL_ZIPS.containsKey(language) ? MODEL_ZIPS.get(language) : MODEL_ZIPS.get("en");
        final File dir = modelDir(MODEL_ZIPS.containsKey(language) ? language : "en");
        if (isModelReady(dir)) {
            return dir;
        }
        synchronized (modelLock) {
            if (!isModelReady(dir)) {
                downloadModel(zipName);
                unzipModel(zipName, dir);
            }
        }
        if (!isModelReady(dir)) {
            throw new TranscribeException("Speech model failed to install. Try again.");
        }
        return dir;
    }

    private static boolean isModelReady(File dir) {
        return new File(dir, ".ready").exists()
                && new File(dir, "conf/model.conf").exists();
    }

    private static long downloadModel(String zipName) throws Exception {
        Context context = ApplicationLoader.applicationContext;
        File cache = new File(context.getCacheDir(), zipName + ".zip");
        FileLog.d(TAG + ": downloading model " + zipName);
        toast("Televa: downloading speech model (one-time, up to 40 MB)…");

        HttpURLConnection connection = null;
        try {
            URL url = new URL(MODEL_BASE_URL + zipName + ".zip");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new TranscribeException("Couldn't download the speech model (HTTP " + code + ")");
            }
            long total = connection.getContentLength();
            DataInputStream in = new DataInputStream(new BufferedInputStream(connection.getInputStream(), 64 * 1024));
            FileOutputStream out = new FileOutputStream(cache);
            byte[] buf = new byte[32 * 1024];
            long read = 0;
            int n;
            boolean toastedHalf = false;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                read += n;
                if (total > 0 && !toastedHalf && read > total / 2) {
                    toastedHalf = true;
                    toast("Televa: model download 50%…");
                }
            }
            out.flush();
            out.close();
            in.close();
            if (read <= 0) {
                throw new TranscribeException("Speech model download failed. Check your connection and try again.");
            }
            FileLog.d(TAG + ": model downloaded, " + read + " bytes");
            return read;
        } finally {
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static void unzipModel(String zipName, File dir) throws Exception {
        Context context = ApplicationLoader.applicationContext;
        File zip = new File(context.getCacheDir(), zipName + ".zip");
        dir.mkdirs();
        ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)));
        try {
            ZipEntry entry;
            byte[] buf = new byte[16 * 1024];
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                // Strip the top-level folder inside the zip ("vosk-model-.../").
                int slash = name.indexOf('/');
                if (slash < 0) {
                    continue;
                }
                String rel = name.substring(slash + 1);
                if (rel.isEmpty()) {
                    continue;
                }
                File out = new File(dir, rel);
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                FileOutputStream fos = new FileOutputStream(out);
                int n;
                while ((n = zin.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                fos.flush();
                fos.close();
            }
        } finally {
            try {
                zin.close();
            } catch (Throwable ignore) {
            }
        }
        new File(dir, ".ready").createNewFile();
        zip.delete();
    }

    // ---------------------------------------------------------------------
    // Recognition
    // ---------------------------------------------------------------------

    @WorkerThread
    private static String recognize(File modelDir, String language, byte[] pcm16k) throws Exception {
        if (voskInitialized.compareAndSet(false, true)) {
            try {
                Vosk.setLogLevel(LogLevel.WARN);
            } catch (Throwable ignore) {
            }
        }
        Model model = obtainModel(modelDir, language);
        Recognizer recognizer = new Recognizer(model, 16000.0f);
        try {
            StringBuilder text = new StringBuilder();
            byte[] chunk = new byte[4096];
            int pos = 0;
            while (pos < pcm16k.length) {
                int len = Math.min(chunk.length, pcm16k.length - pos);
                System.arraycopy(pcm16k, pos, chunk, 0, len);
                if (recognizer.acceptWaveForm(chunk, len)) {
                    appendResult(recognizer.getResult(), text);
                }
                pos += len;
            }
            appendResult(recognizer.getFinalResult(), text);
            return text.toString().trim();
        } finally {
            try {
                recognizer.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static void appendResult(String json, StringBuilder out) {
        try {
            String piece = new JSONObject(json).optString("text", "").trim();
            if (!piece.isEmpty()) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(piece);
            }
        } catch (Throwable ignore) {
        }
    }

    private static Model obtainModel(File modelDir, String language) throws IOException {
        synchronized (modelLock) {
            String key = modelDir.getAbsolutePath();
            if (cachedModel != null && key.equals(cachedModelKey)) {
                return cachedModel;
            }
            if (cachedModel != null) {
                try {
                    cachedModel.close();
                } catch (Throwable ignore) {
                }
                cachedModel = null;
                cachedModelKey = null;
            }
            FileLog.d(TAG + ": loading vosk model from " + key);
            cachedModel = new Model(key);
            cachedModelKey = key;
            return cachedModel;
        }
    }

    // ---------------------------------------------------------------------
    // Audio decoding: any container -> 16 kHz mono 16-bit PCM
    // ---------------------------------------------------------------------

    @WorkerThread
    private static byte[] decodeToPcm16kMono(File file) throws Exception {
        try {
            return decodeWithMediaExtractor(file);
        } catch (Throwable primary) {
            FileLog.d(TAG + ": primary decode path failed (" + primary + "), trying Ogg fallback");
            try {
                return decodeOggWithExtractor(file);
            } catch (Throwable secondary) {
                FileLog.e(TAG + ": fallback decode also failed", secondary);
                return null;
            }
        }
    }

    private static byte[] decodeWithMediaExtractor(File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int trackIndex = -1;
            MediaFormat trackFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.containsKey(MediaFormat.KEY_MIME) ? format.getString(MediaFormat.KEY_MIME) : null;
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    trackFormat = format;
                    break;
                }
            }
            if (trackIndex < 0 || trackFormat == null) {
                throw new IOException("no audio track found via MediaExtractor");
            }
            String mime = trackFormat.getString(MediaFormat.KEY_MIME);
            extractor.selectTrack(trackIndex);

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(trackFormat, null, null, 0);
            codec.start();

            ByteArrayOutputStream pcm = new ByteArrayOutputStream(64 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEos = false;
            boolean outputEos = false;
            MediaFormat outFormat = null;
            int guard = 0;
            while (!outputEos && guard++ < 5_000_000) {
                if (!inputEos) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuffer = codec.getInputBuffer(inIndex);
                        int sampleSize = inBuffer != null ? extractor.readSampleData(inBuffer, 0) : -1;
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer outBuffer = codec.getOutputBuffer(outIndex);
                        if (outBuffer != null) {
                            byte[] chunk = new byte[info.size];
                            outBuffer.position(info.offset);
                            outBuffer.get(chunk);
                            pcm.write(chunk);
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEos = true;
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFormat = codec.getOutputFormat();
                }
            }
            int outRate = 48000;
            int outChannels = 1;
            if (outFormat != null) {
                if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    outRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                }
                if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    outChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            } else if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                outRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    outChannels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            }
            if (pcm.size() <= 0) {
                throw new IOException("decoder produced no pcm");
            }
            return resampleTo16kMono(pcm.toByteArray(), outRate, outChannels);
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                }
            } catch (Throwable ignore) {
            }
            try {
                if (codec != null) {
                    codec.release();
                }
            } catch (Throwable ignore) {
            }
            try {
                extractor.release();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * Fallback for devices whose MediaExtractor cannot parse Ogg Opus
     * (typically API < 29): extract the Opus packets with the in-tree
     * ExoPlayer OggExtractor and feed them to MediaCodec by hand.
     */
    private static byte[] decodeOggWithExtractor(File file) throws Exception {
        final CapturedTrack captured = new CapturedTrack();
        com.google.android.exoplayer2.extractor.ogg.OggExtractor oggExtractor =
                new com.google.android.exoplayer2.extractor.ogg.OggExtractor();
        oggExtractor.init(new com.google.android.exoplayer2.extractor.ExtractorOutput() {
            @Override
            public com.google.android.exoplayer2.extractor.TrackOutput track(int id, int type) {
                return captured;
            }

            @Override
            public void endTracks() {
            }

            @Override
            public void seekMap(com.google.android.exoplayer2.extractor.SeekMap seekMap) {
            }
        });

        long length = file.length();
        com.google.android.exoplayer2.upstream.FileDataSource dataSource = new com.google.android.exoplayer2.upstream.FileDataSource();
        dataSource.open(new com.google.android.exoplayer2.upstream.DataSpec(Uri.fromFile(file), 0, length));
        com.google.android.exoplayer2.extractor.DefaultExtractorInput input =
                new com.google.android.exoplayer2.extractor.DefaultExtractorInput(dataSource, 0, length);
        com.google.android.exoplayer2.extractor.PositionHolder positionHolder =
                new com.google.android.exoplayer2.extractor.PositionHolder();

        int result = com.google.android.exoplayer2.extractor.Extractor.RESULT_CONTINUE;
        int guard = 0;
        while (result != com.google.android.exoplayer2.extractor.Extractor.RESULT_END_OF_INPUT && guard++ < 100) {
            result = oggExtractor.read(input, positionHolder);
            if (result == com.google.android.exoplayer2.extractor.Extractor.RESULT_SEEK) {
                long pos = positionHolder.position;
                dataSource.close();
                dataSource.open(new com.google.android.exoplayer2.upstream.DataSpec(Uri.fromFile(file), pos, length - pos));
                input = new com.google.android.exoplayer2.extractor.DefaultExtractorInput(dataSource, pos, length - pos);
            }
        }
        dataSource.close();

        if (captured.packets.isEmpty() || captured.format == null) {
            throw new IOException("ogg extractor produced no packets");
        }

        String mime = captured.format.sampleMimeType;
        if (mime == null) {
            mime = "audio/opus";
        }
        MediaFormat mediaFormat = MediaFormat.createAudioFormat(mime, 48000, 1);
        List<byte[]> csd = captured.format.initializationData;
        if (csd != null && !csd.isEmpty()) {
            mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd.get(0)));
        }

        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(mediaFormat, null, null, 0);
        codec.start();
        ByteArrayOutputStream pcm = new ByteArrayOutputStream(64 * 1024);
        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int sampleIndex = 0;
            int inIndex;
            int outIndex;
            int guard2 = 0;
            boolean done = false;
            while (!done && guard2++ < 5_000_000) {
                inIndex = codec.dequeueInputBuffer(10_000);
                if (inIndex >= 0 && sampleIndex <= captured.packets.size()) {
                    ByteBuffer inBuffer = codec.getInputBuffer(inIndex);
                    if (sampleIndex == captured.packets.size()) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        byte[] packet = captured.packets.get(sampleIndex);
                        inBuffer.put(packet);
                        codec.queueInputBuffer(inIndex, 0, packet.length, sampleIndex * 20_000L, 0);
                    }
                    sampleIndex++;
                }
                outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer outBuffer = codec.getOutputBuffer(outIndex);
                        if (outBuffer != null) {
                            byte[] chunk = new byte[info.size];
                            outBuffer.position(info.offset);
                            outBuffer.get(chunk);
                            pcm.write(chunk);
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        done = true;
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // output sample rate for Opus is always 48000
                }
            }
        } finally {
            try {
                codec.stop();
            } catch (Throwable ignore) {
            }
            try {
                codec.release();
            } catch (Throwable ignore) {
            }
        }
        if (pcm.size() <= 0) {
            throw new IOException("decoder produced no pcm");
        }
        return resampleTo16kMono(pcm.toByteArray(), 48000, 1);
    }

    private static class CapturedTrack implements com.google.android.exoplayer2.extractor.TrackOutput {
        public com.google.android.exoplayer2.Format format;
        public final List<byte[]> packets = new ArrayList<>();
        private final ByteArrayOutputStream current = new ByteArrayOutputStream();

        @Override
        public void format(com.google.android.exoplayer2.Format format) {
            if (this.format == null) {
                this.format = format;
            }
        }

        @Override
        public void sampleData(com.google.android.exoplayer2.util.ParsableByteArray data, int length, int sampleDataPart) {
            byte[] buf = new byte[length];
            data.readBytes(buf, 0, length);
            current.write(buf, 0, length);
        }

        @Override
        public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData cryptoData) {
            byte[] packet = current.toByteArray();
            current.reset();
            if (packet.length > 0) {
                packets.add(packet);
            }
        }
    }

    /**
     * Downmixes to mono and linearly resamples to 16 kHz.
     * Input: interleaved little-endian 16-bit PCM.
     */
    private static byte[] resampleTo16kMono(byte[] pcm, int fromRate, int channels) {
        if (channels <= 0) channels = 1;
        if (fromRate <= 0) fromRate = 48000;
        if (fromRate == 16000 && channels == 1) {
            return pcm;
        }
        int frames = pcm.length / (2 * channels);
        if (frames == 0) {
            return pcm;
        }
        double ratio = fromRate / 16000.0;
        int outFrames = Math.max(1, (int) (frames / ratio));
        ByteBuffer in = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer out = ByteBuffer.allocate(outFrames * 2);
        out.order(ByteOrder.LITTLE_ENDIAN);
        double srcPos = 0;
        for (int i = 0; i < outFrames; i++) {
            int i0 = (int) srcPos;
            int i1 = Math.min(i0 + 1, frames - 1);
            double frac = srcPos - i0;
            double s0 = frameSample(in, i0, channels);
            double s1 = frameSample(in, i1, channels);
            double sample = s0 * (1 - frac) + s1 * frac;
            if (sample > 32767) sample = 32767;
            if (sample < -32768) sample = -32768;
            out.putShort((short) sample);
            srcPos += ratio;
        }
        return out.array();
    }

    private static double frameSample(ByteBuffer pcm, int frame, int channels) {
        int base = frame * channels;
        int sum = 0;
        for (int c = 0; c < channels; c++) {
            sum += pcm.getShort((base + c) * 2);
        }
        return (double) sum / channels;
    }

    private static void toast(String message) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Toast.makeText(ApplicationLoader.applicationContext, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignore) {
            }
        });
    }

    /**
     * True when the offline engine has a language model installed and there is
     * no ongoing download, so the fallback is known to be instantly available.
     * Used only for diagnostics.
     */
    public static boolean isModelReadyForCurrentLanguage(String language) {
        try {
            return isModelReady(modelDir(language));
        } catch (Throwable e) {
            return false;
        }
    }

    private TelevaTranscribe() {
    }
}
