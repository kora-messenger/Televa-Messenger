package org.telegram.ui.Components.voip;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.VoiceAnalyzer;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.BottomSheet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Televa in-call Voice Changer sheet.
 *
 * Two sections:
 *  - Built-in character presets (native DSP engine, zero added latency).
 *  - Sample-cloned voices: record a 30 s sample in-app or import an audio/video
 *    file. The sample's speaker pitch is measured on-device (VoiceAnalyzer) and
 *    the live engine auto-detects the caller's own pitch at call time, then
 *    shifts it to match the target speaker (preset 100 in the native engine).
 */
public class VoIPVoiceChangerSheet extends BottomSheet {

    public interface Listener {
        void onPresetSelected(int preset, float cloneTargetF0);
        void onPickSampleFile();
    }

    private static final int[] PRESET_STRINGS = {
            R.string.VoipVoiceDeep,
            R.string.VoipVoiceMonster,
            R.string.VoipVoiceSoft,
            R.string.VoipVoiceChipmunk,
            R.string.VoipVoiceRobot,
            R.string.VoipVoiceAlien,
            R.string.VoipVoiceRadio,
            R.string.VoipVoiceCave,
            R.string.VoipVoiceSqueaky,
            R.string.VoipVoiceGhost,
    };

    private final Listener listener;
    private LinearLayout listLayout;
    private ScrollView scrollView;
    private LinearLayout clonesContainer;
    private int selectedPreset;
    private float selectedTargetF0;
    private String selectedCloneName;

    // recording
    private MediaRecorder recorder;
    private boolean recording;
    private long recordStart;
    private File recordFile;
    private AlertDialog progressDialog;

    // preview playback
    private MediaPlayer previewPlayer;

    private static final int COLOR_BG = 0xFF1A1A1E;
    private static final int COLOR_ACCENT = 0xFF54DB72;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SUB = 0xFF9A9AA3;
    private static final int COLOR_ROW = 0x1AFFFFFF;

    public VoIPVoiceChangerSheet(Context context, Listener l) {
        super(context, false);
        listener = l;
        SharedConfig.loadVoiceChangerConfig();
        selectedPreset = SharedConfig.voiceChangerPreset;
        selectedTargetF0 = SharedConfig.voiceCloneTargetF0;

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.VoipVoiceChanger));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).topMargin = AndroidUtilities.dp(20);
        ((LinearLayout.LayoutParams) title.getLayoutParams()).bottomMargin = AndroidUtilities.dp(4);

        TextView subtitle = new TextView(context);
        subtitle.setText(LocaleController.getString(R.string.VoipVoiceHint));
        subtitle.setTextColor(COLOR_SUB);
        subtitle.setTextSize(13);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(subtitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) subtitle.getLayoutParams()).bottomMargin = AndroidUtilities.dp(12);

        scrollView = new ScrollView(context);
        listLayout = new LinearLayout(context);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(listLayout, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        buildRows();
        setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(560)));
    }

    private void buildRows() {
        Context context = getContext();
        listLayout.removeAllViews();

        listLayout.addView(makeRow(context, LocaleController.getString(R.string.VoipVoiceOff), null, 0, 0.0f, selectedPreset == 0));

        for (int i = 0; i < PRESET_STRINGS.length; i++) {
            int preset = i + 1;
            listLayout.addView(makeRow(context, LocaleController.getString(PRESET_STRINGS[i]), null, preset, 0.0f, selectedPreset == preset));
        }

        // ---- cloned voices ----
        TextView section = new TextView(context);
        section.setText(LocaleController.getString(R.string.VoipVoiceClonedSection));
        section.setTextColor(COLOR_ACCENT);
        section.setTextSize(13);
        section.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        listLayout.addView(section, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) section.getLayoutParams();
        slp.topMargin = AndroidUtilities.dp(16);
        slp.leftMargin = AndroidUtilities.dp(20);
        slp.bottomMargin = AndroidUtilities.dp(4);

        ArrayList<SharedConfig.VoiceClone> clones = SharedConfig.getVoiceClones();
        for (int i = 0; i < clones.size(); i++) {
            SharedConfig.VoiceClone clone = clones.get(i);
            String sub = String.format(java.util.Locale.US, "%.0f Hz", clone.f0);
            boolean selected = selectedPreset == 100 && Math.abs(selectedTargetF0 - clone.f0) < 0.5f;
            listLayout.addView(makeRow(context, clone.name, sub, 100, clone.f0, selected));
        }
        if (clones.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(LocaleController.getString(R.string.VoipVoiceCloneHint));
            empty.setTextColor(COLOR_SUB);
            empty.setTextSize(13);
            listLayout.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams elp = (LinearLayout.LayoutParams) empty.getLayoutParams();
            elp.leftMargin = AndroidUtilities.dp(20);
            elp.rightMargin = AndroidUtilities.dp(20);
            elp.bottomMargin = AndroidUtilities.dp(6);
        }

        // add-clone row
        LinearLayout addRow = new LinearLayout(context);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView plus = new TextView(context);
        plus.setText("+");
        plus.setTextColor(COLOR_ACCENT);
        plus.setTextSize(20);
        addRow.addView(plus, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView addText = new TextView(context);
        addText.setText(LocaleController.getString(R.string.VoipVoiceCloneAdd));
        addText.setTextColor(COLOR_TEXT);
        addText.setTextSize(15);
        LinearLayout.LayoutParams atlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        atlp.leftMargin = AndroidUtilities.dp(12);
        addRow.addView(addText, atlp);
        addRow.setOnClickListener(v -> showAddOptions());
        listLayout.addView(addRow, rowParams(AndroidUtilities.dp(56), AndroidUtilities.dp(20), AndroidUtilities.dp(10), AndroidUtilities.dp(20)));
        addRow.setBackground(ripple());
    }

    private LinearLayout.LayoutParams rowParams(int height, int lm, int tm, int rm) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        lp.leftMargin = AndroidUtilities.dp(Math.max(0, lm));
        lp.topMargin = AndroidUtilities.dp(Math.max(0, tm));
        lp.rightMargin = AndroidUtilities.dp(Math.max(0, rm));
        return lp;
    }

    private View makeRow(Context context, String name, String sub, int preset, float targetF0, boolean selected) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ripple());

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(name);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(15);
        texts.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (sub != null) {
            TextView subT = new TextView(context);
            subT.setText(sub);
            subT.setTextColor(COLOR_SUB);
            subT.setTextSize(12);
            texts.addView(subT, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final boolean isClone = preset == 100;
        if (isClone && sub != null) {
            TextView play = new TextView(context);
            play.setText(LocaleController.getString(R.string.VoipVoiceClonePlay));
            play.setTextColor(COLOR_ACCENT);
            play.setTextSize(13);
            play.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
            play.setOnClickListener(v -> {
                String samplePath = null;
                for (SharedConfig.VoiceClone c : SharedConfig.getVoiceClones()) {
                    if (c.name.equals(name)) {
                        samplePath = c.samplePath;
                        break;
                    }
                }
                playSample(samplePath);
            });
            row.addView(play, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView delete = new TextView(context);
            delete.setText(LocaleController.getString(R.string.VoipVoiceCloneDelete));
            delete.setTextColor(0xFFFF6B6B);
            delete.setTextSize(13);
            delete.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(4), 0);
            delete.setOnClickListener(v -> confirmDelete(name));
            row.addView(delete, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        RadioView radio = new RadioView(context);
        radio.setChecked(selected);
        row.addView(radio, new LinearLayout.LayoutParams(AndroidUtilities.dp(22), AndroidUtilities.dp(22)));
        LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) radio.getLayoutParams();
        rlp.leftMargin = AndroidUtilities.dp(8);

        row.setOnClickListener(v -> {
            selectedPreset = preset;
            selectedTargetF0 = targetF0;
            if (preset == 100) {
                selectedCloneName = name;
            }
            if (listener != null) {
                listener.onPresetSelected(preset, targetF0);
            }
            buildRows();
        });
        row.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        return row;
    }

    private class RadioView extends View {
        private boolean checked;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public RadioView(Context context) {
            super(context);
        }

        public void setChecked(boolean c) {
            checked = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(AndroidUtilities.dp(1.5f));
            paint.setColor(checked ? COLOR_ACCENT : 0xFF6A6A72);
            canvas.drawCircle(cx, cy, AndroidUtilities.dp(8), paint);
            if (checked) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(COLOR_ACCENT);
                canvas.drawCircle(cx, cy, AndroidUtilities.dp(4), paint);
            }
        }
    }

    private android.graphics.drawable.Drawable ripple() {
        return Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(6), 0x00000000, 0x22FFFFFF);
    }

    // ------------- add-clone options -------------

    private void showAddOptions() {
        Context context = getContext();
        CharSequence[] items = new CharSequence[]{
                LocaleController.getString(R.string.VoipVoiceCloneRecord),
                LocaleController.getString(R.string.VoipVoiceCloneImport)
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                startRecording();
            } else if (listener != null) {
                listener.onPickSampleFile();
            }
        });
        builder.show();
    }

    // ------------- recording -------------

    private void startRecording() {
        Context context = getContext();
        if (recording) {
            return;
        }
        try {
            File dir = new File(context.getFilesDir(), "voice_clones");
            dir.mkdirs();
            recordFile = new File(dir, "clone_" + System.currentTimeMillis() + ".m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioChannels(1);
            recorder.setAudioEncodingBitRate(24000);
            recorder.setOutputFile(recordFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            recordStart = System.currentTimeMillis();
            showRecordingView();
        } catch (Exception e) {
            FileLog.e(e);
            recorder = null;
            Toast.makeText(context, LocaleController.getString(R.string.VoipVoiceCloneMicBusy), Toast.LENGTH_SHORT).show();
        }
    }

    private void showRecordingView() {
        Context context = getContext();
        listLayout.removeAllViews();

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        listLayout.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView timer = new TextView(context);
        timer.setTextColor(COLOR_TEXT);
        timer.setTextSize(44);
        timer.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        timer.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(timer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tlp = (LinearLayout.LayoutParams) timer.getLayoutParams();
        tlp.topMargin = AndroidUtilities.dp(24);

        TextView hint = new TextView(context);
        hint.setText(LocaleController.getString(R.string.VoipVoiceCloneRecordHint));
        hint.setTextColor(COLOR_SUB);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams hlp = (LinearLayout.LayoutParams) hint.getLayoutParams();
        hlp.topMargin = AndroidUtilities.dp(8);
        hlp.leftMargin = AndroidUtilities.dp(24);
        hlp.rightMargin = AndroidUtilities.dp(24);

        TextView stopBtn = new TextView(context);
        stopBtn.setText(LocaleController.getString(R.string.VoipVoiceCloneStop));
        stopBtn.setTextColor(0xFFFF6B6B);
        stopBtn.setTextSize(15);
        stopBtn.setGravity(Gravity.CENTER);
        stopBtn.setBackground(ripple());
        box.addView(stopBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(48)));
        LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) stopBtn.getLayoutParams();
        slp.topMargin = AndroidUtilities.dp(28);
        slp.leftMargin = AndroidUtilities.dp(20);
        slp.rightMargin = AndroidUtilities.dp(20);
        stopBtn.setOnClickListener(v -> stopRecording());

        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!recording) {
                    return;
                }
                long elapsed = System.currentTimeMillis() - recordStart;
                long s = elapsed / 1000;
                timer.setText(String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60));
                if (s >= 35) {
                    stopRecording();
                    return;
                }
                AndroidUtilities.runOnUIThread(this, 200);
            }
        };
        AndroidUtilities.runOnUIThread(tick, 200);
    }

    private void stopRecording() {
        if (!recording || recorder == null) {
            return;
        }
        recording = false;
        try {
            recorder.stop();
        } catch (Exception ignored) {
        }
        try {
            recorder.release();
        } catch (Exception ignored) {
        }
        recorder = null;
        long elapsed = System.currentTimeMillis() - recordStart;
        if (elapsed < 10_000) {
            if (recordFile != null) {
                recordFile.delete();
            }
            buildRows();
            Toast.makeText(getContext(), LocaleController.getString(R.string.VoipVoiceCloneTooShort), Toast.LENGTH_SHORT).show();
            return;
        }
        analyzeSample(recordFile.getAbsolutePath());
    }

    // ------------- import -------------

    public void onSampleFilePicked(android.net.Uri uri) {
        try {
            Context context = getContext();
            File dir = new File(context.getFilesDir(), "voice_clones");
            dir.mkdirs();
            File imported = new File(dir, "clone_" + System.currentTimeMillis() + ".m4a");
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(imported)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            analyzeSample(imported.getAbsolutePath());
        } catch (Exception e) {
            FileLog.e(e);
            Toast.makeText(getContext(), LocaleController.getString(R.string.VoipVoiceCloneFailed), Toast.LENGTH_SHORT).show();
        }
    }

    // ------------- analysis -------------

    private void analyzeSample(String path) {
        Context context = getContext();
        progressDialog = new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.VoipVoiceChanger))
                .setMessage(LocaleController.getString(R.string.VoipVoiceCloneAnalyzing))
                .create();
        progressDialog.setCancelable(false);
        if (isShowing()) {
            progressDialog.show();
        }
        new Thread(() -> {
            VoiceAnalyzer.Result result = VoiceAnalyzer.analyze(path);
            AndroidUtilities.runOnUIThread(() -> {
                if (progressDialog != null) {
                    try {
                        progressDialog.dismiss();
                    } catch (Exception ignored) {
                    }
                    progressDialog = null;
                }
                if (result.ok) {
                    askNameAndSave(path, result.f0);
                } else {
                    new File(path).delete();
                    String msg = LocaleController.getString(R.string.VoipVoiceCloneFailed);
                    if (result.error != null && result.error.equals("sample too short")) {
                        msg = LocaleController.getString(R.string.VoipVoiceCloneTooShort);
                    }
                    AlertDialog.Builder b = new AlertDialog.Builder(getContext());
                    b.setMessage(msg);
                    b.setPositiveButton(LocaleController.getString(R.string.OK), null);
                    b.show();
                    buildRows();
                }
            });
        }).start();
    }

    private void askNameAndSave(String path, float f0) {
        Context context = getContext();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.VoipVoiceCloneName));
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);
        builder.setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                name = LocaleController.getString(R.string.VoipVoiceCloneDefault);
            }
            SharedConfig.addVoiceClone(new SharedConfig.VoiceClone(name, f0, path));
            selectedPreset = 100;
            selectedTargetF0 = f0;
            if (listener != null) {
                listener.onPresetSelected(100, f0);
            }
            buildRows();
            Toast.makeText(getContext(), LocaleController.getString(R.string.VoipVoiceCloneSaved), Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (dialog, which) -> {
            new File(path).delete();
            buildRows();
        });
        builder.show();
    }

    // ------------- preview / delete -------------

    private void playSample(String path) {
        if (path == null) {
            return;
        }
        try {
            stopPreview();
            previewPlayer = new MediaPlayer();
            previewPlayer.setDataSource(path);
            previewPlayer.prepare();
            previewPlayer.start();
            previewPlayer.setOnCompletionListener(mp -> stopPreview());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void stopPreview() {
        if (previewPlayer != null) {
            try {
                previewPlayer.stop();
            } catch (Exception ignored) {
            }
            try {
                previewPlayer.release();
            } catch (Exception ignored) {
            }
            previewPlayer = null;
        }
    }

    private void confirmDelete(String name) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setMessage(LocaleController.getString(R.string.VoipVoiceCloneDeleteConfirm));
        builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
            ArrayList<SharedConfig.VoiceClone> clones = SharedConfig.getVoiceClones();
            for (int i = 0; i < clones.size(); i++) {
                if (clones.get(i).name.equals(name)) {
                    SharedConfig.removeVoiceClone(i);
                    break;
                }
            }
            if (selectedPreset == 100) {
                selectedPreset = 0;
                selectedTargetF0 = 0;
                if (listener != null) {
                    listener.onPresetSelected(0, 0);
                }
            }
            buildRows();
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.show();
    }

    @Override
    public void dismiss() {
        recording = false;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
            if (recordFile != null) {
                recordFile.delete();
            }
        }
        stopPreview();
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignored) {
            }
            progressDialog = null;
        }
        super.dismiss();
    }

}
