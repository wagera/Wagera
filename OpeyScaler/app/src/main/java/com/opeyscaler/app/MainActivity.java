package com.opeyscaler.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.opeyscaler.engine.Preset;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Uygulamanin tek ekrani: fotograf secimi, model/cozunurluk ayarlari, ilerleme ve sonuc. */
public final class MainActivity extends Activity implements UpscaleJob.Listener {

    private static final int REQ_PICK = 1;
    private static final int REQ_NOTIFICATIONS = 2;

    private ImageView sourcePreview, resultPreview, resultMark;
    private TextView sourceInfo, targetInfo, sharpenValue, qualityValue, progressText,
            progressPercent, resultTitle, resultInfo, modelInfo, stageInfo;
    private Button pickButton, startButton, cancelButton, openButton, shareButton,
            formatJpeg, formatPng, stage1, stage2;
    private GridLayout presetGrid;
    private LinearLayout modelList, progressCard, resultCard, emptyState, qualityRow;
    private SeekBar sharpenSeek, qualitySeek;
    private ProgressBar progressBar;

    private Uri sourceUri;
    private int srcWidth, srcHeight;
    private Preset preset = Preset.R4K;
    private SrModel model = SrModel.ESRGAN_FAST;
    private int stages = 1;
    private boolean jpeg = true;
    private final List<LinearLayout> presetChips = new ArrayList<>();
    private final List<View> modelRows = new ArrayList<>();
    private final List<SrModel> availableModels = new ArrayList<>();
    private UpscaleJob lastResult;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        buildModelList();
        buildPresetGrid();
        setupListeners();
        if (!NativeSr.available() || !availableModels.contains(model)) {
            model = availableModels.isEmpty() ? SrModel.LANCZOS : availableModels.get(0);
            if (!NativeSr.available()) model = SrModel.LANCZOS;
            updateModelRows();
        }
        requestNotificationPermission();
        handleShareIntent(getIntent());
        refreshTexts();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    @Override protected void onStart() {
        super.onStart();
        UpscaleJob job = UpscaleJob.current();
        if (job != null) job.setListener(this);
    }

    @Override protected void onStop() {
        super.onStop();
        UpscaleJob job = UpscaleJob.current();
        if (job != null) job.setListener(null);
    }

    // ------------------------------------------------------------------ kurulum

    private void bindViews() {
        sourcePreview = findViewById(R.id.sourcePreview);
        resultPreview = findViewById(R.id.resultPreview);
        resultMark = findViewById(R.id.resultMark);
        sourceInfo = findViewById(R.id.sourceInfo);
        targetInfo = findViewById(R.id.targetInfo);
        sharpenValue = findViewById(R.id.sharpenValue);
        qualityValue = findViewById(R.id.qualityValue);
        progressText = findViewById(R.id.progressText);
        progressPercent = findViewById(R.id.progressPercent);
        resultTitle = findViewById(R.id.resultTitle);
        resultInfo = findViewById(R.id.resultInfo);
        modelInfo = findViewById(R.id.modelInfo);
        stageInfo = findViewById(R.id.stageInfo);
        stage1 = findViewById(R.id.stage1);
        stage2 = findViewById(R.id.stage2);
        pickButton = findViewById(R.id.pickButton);
        startButton = findViewById(R.id.startButton);
        cancelButton = findViewById(R.id.cancelButton);
        openButton = findViewById(R.id.openButton);
        shareButton = findViewById(R.id.shareButton);
        formatJpeg = findViewById(R.id.formatJpeg);
        formatPng = findViewById(R.id.formatPng);
        presetGrid = findViewById(R.id.presetGrid);
        modelList = findViewById(R.id.modelList);
        progressCard = findViewById(R.id.progressCard);
        resultCard = findViewById(R.id.resultCard);
        emptyState = findViewById(R.id.emptyState);
        qualityRow = findViewById(R.id.qualityRow);
        sharpenSeek = findViewById(R.id.sharpenSeek);
        qualitySeek = findViewById(R.id.qualitySeek);
        progressBar = findViewById(R.id.progressBar);
    }

    private void buildModelList() {
        modelList.removeAllViews();
        modelRows.clear();
        availableModels.clear();
        for (final SrModel m : SrModel.values()) {
            if (!m.isBundled(getAssets())) continue;
            availableModels.add(m);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundResource(R.drawable.row_model);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(12), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(2);
            row.setLayoutParams(lp);

            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView title = new TextView(this);
            title.setText(m.label);
            title.setTextSize(13.5f);
            title.setTextColor(getColor(R.color.paper));
            text.addView(title);

            TextView desc = new TextView(this);
            desc.setText(m.description);
            desc.setTextSize(11f);
            desc.setLineSpacing(dp(2), 1f);
            desc.setTextColor(getColor(R.color.paper_faint));
            LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dp2.topMargin = dp(3);
            desc.setLayoutParams(dp2);
            text.addView(desc);
            row.addView(text);

            // Sagda olcek rozeti: modelin sabit buyutme orani
            TextView badge = new TextView(this);
            badge.setText(m.isNeural() ? m.scale + "x" : "—");
            badge.setTextSize(11f);
            badge.setTypeface(android.graphics.Typeface.MONOSPACE);
            badge.setTextColor(getColor(R.color.paper_ghost));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.leftMargin = dp(10);
            badge.setLayoutParams(bp);
            row.addView(badge);

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (m.isNeural() && !NativeSr.available()) {
                        toast("Bu cihazda yapay zeka modelleri calistirilamiyor");
                        return;
                    }
                    model = m;
                    if (!m.isNeural()) stages = 1;
                    // Model ciktisi zaten keskin oldugundan ek keskinlestirme dusurulur.
                    sharpenSeek.setProgress(m.isNeural() ? 12 : 45);
                    updateModelRows();
                    refreshTexts();
                }
            });
            modelList.addView(row);
            modelRows.add(row);
        }
        updateModelRows();
    }

    private void updateModelRows() {
        for (int i = 0; i < modelRows.size(); i++) {
            View row = modelRows.get(i);
            boolean on = availableModels.get(i) == model;
            row.setSelected(on);
            LinearLayout text = (LinearLayout) ((LinearLayout) row).getChildAt(0);
            ((TextView) text.getChildAt(0)).setTextColor(
                    getColor(on ? R.color.paper : R.color.paper_soft));
            ((TextView) ((LinearLayout) row).getChildAt(1)).setTextColor(
                    getColor(on ? R.color.paper_dim : R.color.paper_ghost));
        }
        String gpu;
        if (!NativeSr.available()) {
            gpu = "Yerel model kitapligi bu cihazda yuklenemedi; klasik yontem kullanilabilir.";
        } else if (NativeSr.gpuAvailable()) {
            gpu = "Vulkan GPU bulundu — modeller GPU uzerinde calisacak.";
        } else {
            gpu = "GPU bulunamadi — modeller " + NativeSr.cpuCount()
                    + " CPU cekirdeginde calisacak, daha yavas olur.";
        }
        modelInfo.setText(gpu);
    }

    /** Cozunurluk izgarasinin sutun sayisi (duzen dosyasiyla ayni olmali). */
    private static final int COLUMNS = 3;

    private void buildPresetGrid() {
        presetGrid.removeAllViews();
        presetChips.clear();
        for (final Preset p : Preset.values()) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundResource(R.drawable.chip);
            chip.setPadding(dp(4), dp(10), dp(4), dp(10));

            TextView label = new TextView(this);
            label.setText(p.label);
            label.setTextSize(17f);
            label.setGravity(Gravity.CENTER);
            label.setLetterSpacing(0.02f);
            label.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
            chip.addView(label);

            // Ikinci satir: secilen fotografa gore hesaplanan hedef boyut
            TextView sub = new TextView(this);
            sub.setTextSize(9.5f);
            sub.setGravity(Gravity.CENTER);
            sub.setTypeface(android.graphics.Typeface.MONOSPACE);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = dp(3);
            sub.setLayoutParams(sp);
            chip.addView(sub);

            /*
             * Son satirda tek basina kalan cip (10 on ayar, 3 sutun) satirin
             * tamamini kaplar; boylece izgara dengeli kalir ve en buyuk
             * cozunurluk one cikar.
             */
            final int index = presetGrid.getChildCount();
            final int total = Preset.values().length;
            final int span = (index == total - 1 && total % COLUMNS == 1) ? COLUMNS : 1;
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, span, span);
            lp.setMargins(dp(3), dp(3), dp(3), dp(3));
            chip.setLayoutParams(lp);

            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    preset = p;
                    updatePresetChips();
                    refreshTexts();
                }
            });
            presetGrid.addView(chip);
            presetChips.add(chip);
        }
        updatePresetChips();
    }

    /** Secili cip beyaza doner; alt satirda hedef boyut gosterilir. */
    private void updatePresetChips() {
        Preset[] values = Preset.values();
        boolean hasSource = sourceUri != null && srcWidth > 0;
        for (int i = 0; i < presetChips.size(); i++) {
            LinearLayout chip = presetChips.get(i);
            boolean on = values[i] == preset;
            chip.setSelected(on);
            TextView label = (TextView) chip.getChildAt(0);
            TextView sub = (TextView) chip.getChildAt(1);
            label.setTextColor(getColor(on ? R.color.ink : R.color.paper_soft));
            sub.setTextColor(getColor(on ? R.color.ink : R.color.paper_ghost));
            if (hasSource) {
                int[] t = values[i].targetSize(srcWidth, srcHeight);
                sub.setText(t[0] + "x" + t[1]);
            } else {
                sub.setText(values[i].longEdge + "px");
            }
        }
    }

    private void setupListeners() {
        pickButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickPhoto(); }
        });
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startJob(); }
        });
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                UpscaleJob job = UpscaleJob.current();
                if (job != null) job.cancel();
                cancelButton.setEnabled(false);
            }
        });
        stage1.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stages = 1; refreshTexts(); }
        });
        stage2.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!model.isNeural()) {
                    toast("Iki gecis yalnizca yapay zeka modellerinde kullanilir");
                    return;
                }
                stages = 2;
                refreshTexts();
            }
        });
        formatJpeg.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { jpeg = true; refreshTexts(); }
        });
        formatPng.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { jpeg = false; refreshTexts(); }
        });
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { refreshTexts(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        };
        sharpenSeek.setOnSeekBarChangeListener(l);
        qualitySeek.setOnSeekBarChangeListener(l);
        openButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openResult(false); }
        });
        shareButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openResult(true); }
        });
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    // ------------------------------------------------------------------ fotograf secimi

    private void pickPhoto() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
            startActivityForResult(fallback, REQ_PICK);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            setSource(data.getData(), data.getFlags());
        }
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction()) && intent.getType() != null
                && intent.getType().startsWith("image/")) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) setSource(uri, intent.getFlags());
        }
    }

    private void setSource(Uri uri, int flags) {
        try {
            if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        } catch (Exception ignored) {
            // Bazi kaynaklar kalici izin vermez; oturum boyunca gecerli izin yeterli.
        }
        sourceUri = uri;
        loadThumbnail(uri);
        resultCard.setVisibility(View.GONE);
        refreshTexts();
    }

    private void loadThumbnail(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, bounds);
            if (in != null) in.close();

            int orientation = ExifInterface.ORIENTATION_NORMAL;
            InputStream ex = getContentResolver().openInputStream(uri);
            if (ex != null) {
                try {
                    orientation = new ExifInterface(ex).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                } catch (Throwable ignored) {
                } finally {
                    ex.close();
                }
            }
            boolean swap = orientation >= 5 && orientation <= 8;
            srcWidth = swap ? bounds.outHeight : bounds.outWidth;
            srcHeight = swap ? bounds.outWidth : bounds.outHeight;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            int sample = 1;
            while (bounds.outWidth / sample > 1400 || bounds.outHeight / sample > 1400) sample *= 2;
            opts.inSampleSize = sample;
            InputStream in2 = getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(in2, null, opts);
            if (in2 != null) in2.close();
            if (bmp != null) {
                bmp = applyOrientation(bmp, orientation);
                sourcePreview.setImageBitmap(bmp);
            }
        } catch (Throwable t) {
            toast("Fotograf okunamadi");
            sourceUri = null;
        }
    }

    private static Bitmap applyOrientation(Bitmap bmp, int orientation) {
        if (orientation <= 1) return bmp;
        Matrix m = new Matrix();
        switch (orientation) {
            case 2: m.setScale(-1, 1); break;
            case 3: m.setRotate(180); break;
            case 4: m.setScale(1, -1); break;
            case 5: m.setRotate(90); m.postScale(-1, 1); break;
            case 6: m.setRotate(90); break;
            case 7: m.setRotate(270); m.postScale(-1, 1); break;
            case 8: m.setRotate(270); break;
            default: return bmp;
        }
        try {
            Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (out != bmp) bmp.recycle();
            return out;
        } catch (OutOfMemoryError e) {
            return bmp;
        }
    }

    // ------------------------------------------------------------------ metinler

    private void refreshTexts() {
        boolean hasSource = sourceUri != null && srcWidth > 0;

        emptyState.setVisibility(hasSource ? View.GONE : View.VISIBLE);
        sourceInfo.setVisibility(hasSource ? View.VISIBLE : View.GONE);
        if (hasSource) {
            sourceInfo.setText(String.format(Locale.US, "%d x %d   %.1f MP",
                    srcWidth, srcHeight, srcWidth * (long) srcHeight / 1e6));
        }
        pickButton.setText(hasSource ? R.string.change_photo : R.string.pick_photo);

        updatePresetChips();
        if (hasSource) {
            int[] t = preset.targetSize(srcWidth, srcHeight);
            double factor = t[0] / (double) srcWidth;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "%d x %d   %.1f MP   %.1f kat",
                    t[0], t[1], t[0] * (long) t[1] / 1e6, factor));
            if (model.isNeural()) {
                long cost = (long) (srcWidth * (long) srcHeight * model.costPerPixel * stages);
                if (cost > 400L * 1000 * 1000) {
                    sb.append("\nBu fotograf bu model icin buyuk, islem cok uzun surebilir.");
                }
            }
            targetInfo.setText(sb.toString());
        } else {
            targetInfo.setText("Once bir fotograf sec.");
        }

        stage1.setSelected(stages == 1);
        stage2.setSelected(stages == 2);
        stage2.setEnabled(model.isNeural());
        if (!model.isNeural()) {
            stageInfo.setText("Klasik yontemde gecis secimi yoktur.");
        } else if (stages == 1) {
            stageInfo.setText(String.format(Locale.US,
                    "Model bir kez calisir (%dx); kalan fark Lanczos ile tamamlanir.", model.scale));
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US,
                    "Model iki kez calisir (%dx -> %dx). Daha fazla detay, cok daha uzun sure.",
                    model.scale, model.scale * model.scale));
            if (hasSource) {
                long midBytes = (long) srcWidth * srcHeight * model.scale * model.scale * 3L;
                sb.append(String.format(Locale.US, " Ara goruntu icin ~%.1f GB gecici alan gerekir.",
                        midBytes / 1073741824.0));
            }
            stageInfo.setText(sb.toString());
        }

        sharpenValue.setText(String.format(Locale.US, "%%%d", sharpenSeek.getProgress()));
        qualityValue.setText(String.valueOf(jpegQuality()));
        qualityRow.setAlpha(jpeg ? 1f : 0.35f);
        qualitySeek.setAlpha(jpeg ? 1f : 0.35f);
        qualitySeek.setEnabled(jpeg);
        formatJpeg.setSelected(jpeg);
        formatPng.setSelected(!jpeg);
        formatJpeg.setTextColor(getColor(jpeg ? R.color.ink : R.color.paper_soft));
        formatPng.setTextColor(getColor(jpeg ? R.color.paper_soft : R.color.ink));
        stage1.setTextColor(getColor(stages == 1 ? R.color.ink : R.color.paper_soft));
        stage2.setTextColor(getColor(!model.isNeural() ? R.color.paper_ghost
                : (stages == 2 ? R.color.ink : R.color.paper_soft)));

        startButton.setEnabled(hasSource && UpscaleJob.current() == null);
    }

    private int jpegQuality() {
        return 70 + qualitySeek.getProgress();  // 70..100
    }

    // ------------------------------------------------------------------ is baslatma

    private void startJob() {
        if (sourceUri == null || UpscaleJob.current() != null) return;
        int[] t = preset.targetSize(srcWidth, srcHeight);
        UpscaleJob job = new UpscaleJob(sourceUri, preset, t[0], t[1], model, stages, jpeg,
                jpegQuality(), sharpenSeek.getProgress() / 100f);
        UpscaleJob.setCurrent(job);
        job.setListener(this);
        UpscaleService.start(this);

        resultCard.setVisibility(View.GONE);
        progressCard.setVisibility(View.VISIBLE);
        cancelButton.setEnabled(true);
        startButton.setEnabled(false);
        progressBar.setProgress(0);
        progressText.setText("Hazirlaniyor");
    }

    @Override public void onJobChanged(UpscaleJob job) {
        if (!job.finished) {
            progressCard.setVisibility(View.VISIBLE);
            progressBar.setProgress((int) (job.progress * 1000));
            progressText.setText(job.stage);
            progressPercent.setText(String.format(Locale.US, "%%%d", (int) (job.progress * 100)));
            startButton.setEnabled(false);
            return;
        }

        UpscaleJob.setCurrent(null);
        job.setListener(null);
        progressCard.setVisibility(View.GONE);
        startButton.setEnabled(sourceUri != null);

        if (job.cancelled) {
            toast("Islem iptal edildi");
            return;
        }
        if (job.error != null) {
            resultCard.setVisibility(View.VISIBLE);
            resultTitle.setText("BASARISIZ");
            resultMark.setAlpha(0.35f);
            resultInfo.setText(job.error);
            resultPreview.setImageDrawable(null);
            openButton.setEnabled(false);
            shareButton.setEnabled(false);
            return;
        }

        lastResult = job;
        resultCard.setVisibility(View.VISIBLE);
        resultTitle.setText("HAZIR");
        resultMark.setAlpha(1f);
        openButton.setEnabled(true);
        shareButton.setEnabled(true);
        if (job.preview != null && job.previewWidth > 0) {
            resultPreview.setImageBitmap(Bitmap.createBitmap(job.preview, job.previewWidth,
                    job.previewHeight, Bitmap.Config.ARGB_8888));
        }
        String engine = job.usedModel != null && job.usedModel.isNeural()
                ? job.usedModel.label + (job.usedStages > 1 ? " x" + job.usedStages + " gecis" : "")
                        + (job.usedGpu ? " (GPU)" : " (CPU)")
                : SrModel.LANCZOS.label;
        resultInfo.setText(String.format(Locale.US,
                "%d x %d   %.1f MP\n%s\n%.1f MB   %.1f sn\n%s\nPictures / OpeyScaler",
                job.outWidth, job.outHeight, job.outWidth * (long) job.outHeight / 1e6,
                engine, job.outputBytes / 1048576.0, job.elapsedMillis / 1000.0, job.outputName));
    }

    private void openResult(boolean share) {
        UpscaleJob last = lastResult;
        if (last == null || last.outputUri == null) return;
        String mime = last.jpeg ? "image/jpeg" : "image/png";
        Intent i;
        if (share) {
            i = new Intent(Intent.ACTION_SEND).setType(mime)
                    .putExtra(Intent.EXTRA_STREAM, last.outputUri);
        } else {
            i = new Intent(Intent.ACTION_VIEW).setDataAndType(last.outputUri, mime);
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(share ? Intent.createChooser(i, getString(R.string.share)) : i);
        } catch (Exception e) {
            toast("Uygun uygulama bulunamadi");
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
