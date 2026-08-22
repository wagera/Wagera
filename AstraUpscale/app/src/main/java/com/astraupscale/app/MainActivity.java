package com.astraupscale.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.astraupscale.engine.Preset;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Uygulamanin tek ekrani; iki sayfadan olusur:
 * buyutme ayarlari ve kullanici istekleri.
 */
public final class MainActivity extends Activity implements UpscaleJob.Listener {

    private static final int REQ_PICK = 1;
    private static final int REQ_NOTIFICATIONS = 2;

    /** Cozunurluk on ayarlarinin kademeleri. */
    private static final int TIER_STANDARD_END = 5;    // 2K..6K
    private static final int TIER_HIGH_END = 9;        // 8K..16K

    private ImageView sourcePreview, resultPreview, resultMark;
    private TextView sourceInfo, targetInfo, sharpenValue, qualityValue, progressText,
            progressPercent, resultTitle, resultInfo, modelInfo, stageInfo,
            deviceInfo, loadInfo, denoiseInfo, offlineNote, updateText,
            languageButton, themeButton, queueStatus, updateRecheck,
            historyCount, historyEmpty, historyRefresh,
            navUpscaleLabel, navHistoryLabel, navRequestsLabel;
    private Button pickButton, startButton, cancelButton, openButton, shareButton,
            formatJpeg, formatPng, stage1, stage2, denoiseToggle, updateButton, sendButton;
    private LinearLayout modelList, progressCard, resultCard, emptyState, qualityRow,
            loadLevels, updateGate, pageUpscale, pageRequests, pageHistory, kindRow,
            historyList, onboarding, navUpscale, navHistory, navRequests;
    private ImageView navUpscaleIcon, navHistoryIcon, navRequestsIcon;
    private Button onboardingStart;
    /** 0 = buyut, 1 = gecmis, 2 = istekler */
    private int page;
    private LinearLayout[] tiers;
    private TextView[] tierLabels;
    private SeekBar sharpenSeek, qualitySeek;
    private ProgressBar progressBar;
    private EditText messageField, contactField;

    private Uri sourceUri;
    private int srcWidth, srcHeight;
    private Preset preset = Preset.R4K;
    private SrModel model = SrModel.ESRGAN_FAST;
    private boolean jpeg = true;
    private int stages = 1;
    private DeviceProfile device;
    private LoadLevel loadLevel = LoadLevel.BALANCED;
    /** 0 = otomatik (64K ve uzeri), 1 = her zaman acik, 2 = kapali */
    private int denoiseMode = 0;
    private int feedbackKind;


    private final List<LinearLayout> presetChips = new ArrayList<>();
    private final List<View> modelRows = new ArrayList<>();
    private final List<SrModel> availableModels = new ArrayList<>();
    private final List<Button> loadButtons = new ArrayList<>();
    private final List<Button> kindButtons = new ArrayList<>();
    private UpscaleJob lastResult;

    @Override protected void attachBaseContext(Context base) {
        // Once tema, sonra dil: ikisi de yapilandirma uzerinden uygulanir.
        super.attachBaseContext(LocaleHelper.wrap(ThemeHelper.wrap(base)));
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();

        device = DeviceProfile.scan(this);
        buildModelList();
        buildPresetTiers();
        buildLoadLevels();
        buildKindChips();
        setupListeners();

        if (!NativeSr.available() || !availableModels.contains(model)) {
            model = availableModels.isEmpty() || !NativeSr.available()
                    ? SrModel.LANCZOS : availableModels.get(0);
            updateModelRows();
        }
        requestNotificationPermission();
        handleShareIntent(getIntent());
        showPage(0);
        if (!getSharedPreferences("astraupscale", MODE_PRIVATE).getBoolean("onboarded", false)) {
            onboarding.setVisibility(View.VISIBLE);
        }
        refreshTexts();

        applyUpdateState(UpdateChecker.pending(this));
        checkForUpdate();
        Reporter.event(this, "app_open", null);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    @Override protected void onStart() {
        super.onStart();
        UpscaleJob job = UpscaleJob.current();
        if (job != null) job.setListener(this);
        applyUpdateState(UpdateChecker.pending(this));
        if (device != null) updateLoadLevels();
        refreshQueueStatus();
    }

    @Override protected void onStop() {
        super.onStop();
        UpscaleJob job = UpscaleJob.current();
        if (job != null) job.setListener(null);
    }

    @Override public void onBackPressed() {
        if (updateGate.getVisibility() == View.VISIBLE) {
            // Yeni surum yuklenmeden uygulama kullanilamaz.
            finishAffinity();
            return;
        }
        if (onboarding.getVisibility() == View.VISIBLE) {
            finishAffinity();
            return;
        }
        if (page != 0) {
            showPage(0);
            return;
        }
        super.onBackPressed();
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
        deviceInfo = findViewById(R.id.deviceInfo);
        loadInfo = findViewById(R.id.loadInfo);
        denoiseInfo = findViewById(R.id.denoiseInfo);
        offlineNote = findViewById(R.id.offlineNote);
        updateText = findViewById(R.id.updateText);
        languageButton = findViewById(R.id.languageButton);
        themeButton = findViewById(R.id.themeButton);
        historyCount = findViewById(R.id.historyCount);
        historyEmpty = findViewById(R.id.historyEmpty);
        historyRefresh = findViewById(R.id.historyRefresh);
        navUpscaleLabel = findViewById(R.id.navUpscaleLabel);
        navHistoryLabel = findViewById(R.id.navHistoryLabel);
        navRequestsLabel = findViewById(R.id.navRequestsLabel);
        navUpscaleIcon = findViewById(R.id.navUpscaleIcon);
        navHistoryIcon = findViewById(R.id.navHistoryIcon);
        navRequestsIcon = findViewById(R.id.navRequestsIcon);
        navUpscale = findViewById(R.id.navUpscale);
        navHistory = findViewById(R.id.navHistory);
        navRequests = findViewById(R.id.navRequests);
        historyList = findViewById(R.id.historyList);
        onboarding = findViewById(R.id.onboarding);
        onboardingStart = findViewById(R.id.onboardingStart);
        pageHistory = findViewById(R.id.pageHistory);
        queueStatus = findViewById(R.id.queueStatus);
        updateRecheck = findViewById(R.id.updateRecheck);

        pickButton = findViewById(R.id.pickButton);
        startButton = findViewById(R.id.startButton);
        cancelButton = findViewById(R.id.cancelButton);
        openButton = findViewById(R.id.openButton);
        shareButton = findViewById(R.id.shareButton);
        formatJpeg = findViewById(R.id.formatJpeg);
        formatPng = findViewById(R.id.formatPng);
        stage1 = findViewById(R.id.stage1);
        stage2 = findViewById(R.id.stage2);
        denoiseToggle = findViewById(R.id.denoiseToggle);
        updateButton = findViewById(R.id.updateButton);
        sendButton = findViewById(R.id.sendButton);

        modelList = findViewById(R.id.modelList);
        progressCard = findViewById(R.id.progressCard);
        resultCard = findViewById(R.id.resultCard);
        emptyState = findViewById(R.id.emptyState);
        qualityRow = findViewById(R.id.qualityRow);
        loadLevels = findViewById(R.id.loadLevels);
        updateGate = findViewById(R.id.updateGate);
        pageUpscale = findViewById(R.id.pageUpscale);
        pageRequests = findViewById(R.id.pageRequests);
        kindRow = findViewById(R.id.kindRow);

        tiers = new LinearLayout[]{findViewById(R.id.tier1), findViewById(R.id.tier2),
                findViewById(R.id.tier3)};
        tierLabels = new TextView[]{findViewById(R.id.tierLabel1), findViewById(R.id.tierLabel2),
                findViewById(R.id.tierLabel3)};

        sharpenSeek = findViewById(R.id.sharpenSeek);
        qualitySeek = findViewById(R.id.qualitySeek);
        progressBar = findViewById(R.id.progressBar);
        messageField = findViewById(R.id.messageField);
        contactField = findViewById(R.id.contactField);
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
            title.setTextColor(getColor(R.color.content));
            text.addView(title);

            TextView desc = new TextView(this);
            desc.setText(getString(m.descriptionRes));
            desc.setTextSize(11f);
            desc.setLineSpacing(dp(2), 1f);
            desc.setTextColor(getColor(R.color.content_faint));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = dp(3);
            desc.setLayoutParams(dlp);
            text.addView(desc);
            row.addView(text);

            TextView badge = new TextView(this);
            badge.setText(m.isNeural() ? m.scale + "×" : "—");
            badge.setTextSize(11f);
            badge.setTypeface(Typeface.MONOSPACE);
            badge.setTextColor(getColor(R.color.content_ghost));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.leftMargin = dp(10);
            badge.setLayoutParams(bp);
            row.addView(badge);

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (m.isNeural() && !NativeSr.available()) {
                        toast(getString(R.string.engine_no_ai));
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
            LinearLayout row = (LinearLayout) modelRows.get(i);
            boolean on = availableModels.get(i) == model;
            row.setSelected(on);
            LinearLayout text = (LinearLayout) row.getChildAt(0);
            ((TextView) text.getChildAt(0)).setTextColor(
                    getColor(on ? R.color.content : R.color.content_soft));
            ((TextView) text.getChildAt(1)).setTextColor(
                    getColor(on ? R.color.content_dim : R.color.content_ghost));
            ((TextView) row.getChildAt(1)).setTextColor(
                    getColor(on ? R.color.content_dim : R.color.content_ghost));
        }
        if (!NativeSr.available()) {
            modelInfo.setText(R.string.engine_unavailable);
        } else if (NativeSr.gpuAvailable()) {
            modelInfo.setText(R.string.engine_gpu);
        } else {
            modelInfo.setText(getString(R.string.engine_cpu, NativeSr.cpuCount()));
        }
    }

    /**
     * Cozunurluk on ayarlari uc kademeye bolunur: her kademe tek satirda,
     * esit genislikte ciplerden olusur. Hedef boyut cip icinde degil, altta
     * tek bir ozet satirinda gosterilir.
     */
    private void buildPresetTiers() {
        for (LinearLayout t : tiers) t.removeAllViews();
        presetChips.clear();
        Preset[] values = Preset.values();
        for (int i = 0; i < values.length; i++) {
            final Preset p = values[i];
            int tier = i < TIER_STANDARD_END ? 0 : (i < TIER_HIGH_END ? 1 : 2);

            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundResource(R.drawable.chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
            if (tiers[tier].getChildCount() > 0) lp.leftMargin = dp(6);
            chip.setLayoutParams(lp);

            TextView label = new TextView(this);
            label.setText(p.label);
            label.setTextSize(13.5f);
            label.setGravity(Gravity.CENTER);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            chip.addView(label);

            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    preset = p;
                    refreshTexts();
                }
            });
            tiers[tier].addView(chip);
            presetChips.add(chip);
        }
    }

    private void updatePresetChips() {
        Preset[] values = Preset.values();
        for (int i = 0; i < presetChips.size(); i++) {
            LinearLayout chip = presetChips.get(i);
            boolean on = values[i] == preset;
            chip.setSelected(on);
            ((TextView) chip.getChildAt(0)).setTextColor(
                    getColor(on ? R.color.bg : R.color.content_soft));
        }
    }

    private void buildLoadLevels() {
        loadLevels.removeAllViews();
        loadButtons.clear();
        for (final LoadLevel level : LoadLevel.values()) {
            Button b = new Button(this);
            b.setText(getString(level.labelRes));
            b.setTextSize(12.5f);
            b.setAllCaps(false);
            b.setBackgroundResource(R.drawable.chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
            if (loadButtons.size() > 0) lp.leftMargin = dp(8);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    loadLevel = level;
                    updateLoadLevels();
                }
            });
            loadLevels.addView(b);
            loadButtons.add(b);
        }
        deviceInfo.setText(device.summary(this));
        updateLoadLevels();
    }

    private void updateLoadLevels() {
        LoadLevel[] values = LoadLevel.values();
        for (int i = 0; i < loadButtons.size(); i++) {
            boolean on = values[i] == loadLevel;
            loadButtons.get(i).setSelected(on);
            loadButtons.get(i).setTextColor(getColor(on ? R.color.bg : R.color.content_soft));
        }
        StringBuilder sb = new StringBuilder(getString(loadLevel.descriptionRes));
        sb.append('\n').append(getString(R.string.load_detail,
                loadLevel.threads(device), loadLevel.tileSize(device)));
        int thermal = DeviceProfile.thermalStatus(this);
        if (thermal >= 0) {
            sb.append("  ·  ").append(getString(R.string.load_thermal,
                    getString(DeviceProfile.thermalTextRes(thermal))));
        }
        loadInfo.setText(sb.toString());
    }

    private void buildKindChips() {
        kindRow.removeAllViews();
        kindButtons.clear();
        int[] labels = {R.string.kind_bug, R.string.kind_idea, R.string.kind_other};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setTextSize(12.5f);
            b.setAllCaps(false);
            b.setBackgroundResource(R.drawable.chip);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            if (i > 0) lp.leftMargin = dp(8);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    feedbackKind = index;
                    updateKindChips();
                }
            });
            kindRow.addView(b);
            kindButtons.add(b);
        }
        updateKindChips();
    }

    private void updateKindChips() {
        for (int i = 0; i < kindButtons.size(); i++) {
            boolean on = i == feedbackKind;
            kindButtons.get(i).setSelected(on);
            kindButtons.get(i).setTextColor(getColor(on ? R.color.bg : R.color.content_soft));
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
                    toast(getString(R.string.pass_neural_only));
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
        denoiseToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                denoiseMode = (denoiseMode + 1) % 3;
                refreshTexts();
            }
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
        navUpscale.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPage(0); }
        });
        navHistory.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPage(1); }
        });
        navRequests.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPage(2); }
        });
        themeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ThemeHelper.set(MainActivity.this, ThemeHelper.next(ThemeHelper.current(MainActivity.this)));
                recreate();
            }
        });
        historyRefresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadHistory(); }
        });
        onboardingStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                getSharedPreferences("astraupscale", MODE_PRIVATE)
                        .edit().putBoolean("onboarded", true).apply();
                onboarding.setVisibility(View.GONE);
            }
        });
        languageButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                LocaleHelper.set(MainActivity.this, LocaleHelper.next(LocaleHelper.current(MainActivity.this)));
                recreate();
            }
        });
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendFeedback(); }
        });
        updateButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                UpdateChecker.Result r = UpdateChecker.pending(MainActivity.this);
                String url = r.downloadUrl != null && r.downloadUrl.length() > 0
                        ? r.downloadUrl
                        : "https://github.com/wagera/Wagera/tree/main/AstraUpscale/release";
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    toast(getString(R.string.no_app_found));
                }
            }
        });
        updateRecheck.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                applyUpdateState(UpdateChecker.pending(MainActivity.this));
                checkForUpdate();
            }
        });
    }

    /** 0 = buyut, 1 = gecmis, 2 = istekler */
    private void showPage(int which) {
        page = which;
        pageUpscale.setVisibility(which == 0 ? View.VISIBLE : View.GONE);
        pageHistory.setVisibility(which == 1 ? View.VISIBLE : View.GONE);
        pageRequests.setVisibility(which == 2 ? View.VISIBLE : View.GONE);

        LinearLayout[] items = {navUpscale, navHistory, navRequests};
        TextView[] labels = {navUpscaleLabel, navHistoryLabel, navRequestsLabel};
        ImageView[] icons = {navUpscaleIcon, navHistoryIcon, navRequestsIcon};
        for (int i = 0; i < items.length; i++) {
            boolean on = i == which;
            items[i].setSelected(on);
            labels[i].setTextColor(getColor(on ? R.color.content : R.color.content_faint));
            icons[i].setAlpha(on ? 1f : 0.45f);
        }
        languageButton.setText(LocaleHelper.label(this, LocaleHelper.current(this)));
        themeButton.setText(ThemeHelper.labelRes(ThemeHelper.current(this)));
        scrollToTop();
        if (which == 1) loadHistory();
        if (which == 2) refreshQueueStatus();
    }

    private void scrollToTop() {
        final View scroll = findViewById(R.id.scroll);
        scroll.post(new Runnable() {
            @Override public void run() { scroll.scrollTo(0, 0); }
        });
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    // ------------------------------------------------------------------ istekler sayfasi

    private void sendFeedback() {
        String message = messageField.getText().toString().trim();
        if (message.isEmpty()) {
            toast(getString(R.string.requests_empty));
            return;
        }
        String[] kinds = {"bug", "idea", "other"};
        Reporter.feedback(this, kinds[feedbackKind], message,
                contactField.getText().toString());
        messageField.setText("");
        contactField.setText("");
        hideKeyboard();
        toast(getString(UpdateChecker.online(this)
                ? R.string.requests_sent : R.string.requests_queued));
        refreshQueueStatus();
    }

    // ------------------------------------------------------------------ gecmis sayfasi

    /**
     * Daha once uretilmis fotograflari galeriden okur. Yalnizca uygulamanin
     * kendi klasoru taranir ve kucuk onizlemeler istenir; boylece cok buyuk
     * dosyalar acilmadan liste hizli kalir.
     */
    private void loadHistory() {
        historyList.removeAllViews();
        final java.util.List<android.net.Uri> uris = new ArrayList<>();
        int shown = 0, total = 0;

        String[] cols = {android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                android.provider.MediaStore.Images.Media.WIDTH,
                android.provider.MediaStore.Images.Media.HEIGHT,
                android.provider.MediaStore.Images.Media.SIZE,
                android.provider.MediaStore.Images.Media.MIME_TYPE};
        android.database.Cursor c = null;
        try {
            String selection = Build.VERSION.SDK_INT >= 29
                    ? android.provider.MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?"
                    : android.provider.MediaStore.Images.Media.DATA + " LIKE ?";
            String arg = Build.VERSION.SDK_INT >= 29 ? "%AstraUpscale%" : "%AstraUpscale%";
            c = getContentResolver().query(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
                    selection, new String[]{arg},
                    android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC");
            if (c != null) {
                while (c.moveToNext()) {
                    total++;
                    if (shown >= 30) continue;   // liste uzamasin
                    long id = c.getLong(0);
                    android.net.Uri uri = android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    uris.add(uri);
                    historyList.addView(historyRow(uri, c.getString(1), c.getInt(2), c.getInt(3),
                            c.getLong(4), c.getString(5)));
                    shown++;
                }
            }
        } catch (Throwable ignored) {
            // Izin yoksa ya da sorgu basarisizsa liste bos kalir.
        } finally {
            if (c != null) c.close();
        }

        historyEmpty.setVisibility(total == 0 ? View.VISIBLE : View.GONE);
        historyCount.setText(getString(R.string.history_count, total));
    }

    private View historyRow(final android.net.Uri uri, String name, int w, int h,
                            long size, final String mime) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.row_model);
        row.setPadding(dp(8), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        row.setLayoutParams(lp);

        ImageView thumb = new ImageView(this);
        thumb.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackgroundResource(R.drawable.preview_bg);
        thumb.setImageBitmap(thumbnail(uri));
        row.addView(thumb);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(12);
        text.setLayoutParams(tp);

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(12.5f);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setTextColor(getColor(R.color.content));
        text.addView(title);

        TextView meta = new TextView(this);
        meta.setText(getString(R.string.history_item, w, h, formatSize(size)));
        meta.setTextSize(11f);
        meta.setTypeface(Typeface.MONOSPACE);
        meta.setTextColor(getColor(R.color.content_faint));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = dp(3);
        meta.setLayoutParams(mp);
        text.addView(meta);
        row.addView(text);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, mime == null ? "image/*" : mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                } catch (Exception e) {
                    toast(getString(R.string.no_app_found));
                }
            }
        });
        return row;
    }

    /** Kucuk onizleme; devasa dosyalarda bile bellek kullanmaz. */
    private Bitmap thumbnail(android.net.Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return getContentResolver().loadThumbnail(uri, new android.util.Size(128, 128), null);
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();
            int sample = 1;
            while (o.outWidth / sample > 256) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            InputStream in2 = getContentResolver().openInputStream(uri);
            Bitmap b = BitmapFactory.decodeStream(in2, null, d);
            if (in2 != null) in2.close();
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private void refreshQueueStatus() {
        queueStatus.setText(ReportSender.queueStatus(this));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(messageField.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ surum denetimi

    private void checkForUpdate() {
        new Thread(new Runnable() {
            @Override public void run() {
                final UpdateChecker.Result r = UpdateChecker.check(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        applyUpdateState(r);
                        refreshQueueStatus();
                    }
                });
            }
        }, "astra-update").start();
    }

    /**
     * Yeni surum goruldugu anda uygulama kilitlenir ve bu bilgi kalici olarak
     * saklanir; kullanici guncellemeyi yukleyene kadar internet olmasa bile
     * kilit acilmaz.
     */
    private void applyUpdateState(UpdateChecker.Result r) {
        if (r.blocking()) {
            updateGate.setVisibility(View.VISIBLE);
            updateText.setText(getString(R.string.update_body,
                    BuildInfo.versionName(this), r.versionName)
                    + (r.notes != null && r.notes.length() > 0 ? "\n\n" + r.notes : "")
                    + "\n\n" + getString(R.string.update_must));
            return;
        }
        updateGate.setVisibility(View.GONE);

        if (UpdateChecker.online(this)) {
            offlineNote.setVisibility(View.GONE);
            return;
        }
        String when;
        if (r.lastCheckAgeMillis < 0) {
            when = getString(R.string.offline_never);
        } else {
            long hours = r.lastCheckAgeMillis / 3600000L;
            when = hours < 1 ? getString(R.string.offline_recent)
                    : (hours < 48 ? getString(R.string.offline_hours, hours)
                                  : getString(R.string.offline_days, hours / 24));
        }
        offlineNote.setText(getString(R.string.offline_note, when));
        offlineNote.setVisibility(View.VISIBLE);
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
            startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_PICK);
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
                sourcePreview.setImageBitmap(applyOrientation(bmp, orientation));
            }
        } catch (Throwable t) {
            toast(getString(R.string.photo_read_failed));
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
            sourceInfo.setText(getString(R.string.source_format,
                    srcWidth, srcHeight, srcWidth * (long) srcHeight / 1e6));
        }
        pickButton.setText(hasSource ? R.string.change_photo : R.string.pick_photo);

        updatePresetChips();
        if (hasSource) {
            int[] t = preset.targetSize(srcWidth, srcHeight);
            StringBuilder sb = new StringBuilder();
            sb.append(getString(R.string.target_format, t[0], t[1],
                    t[0] * (long) t[1] / 1e6, t[0] / (double) srcWidth));
            if (model.isNeural()) {
                long cost = (long) (srcWidth * (long) srcHeight * model.costPerPixel * stages);
                if (cost > 400L * 1000 * 1000) {
                    sb.append('\n').append(getString(R.string.target_slow_warning));
                }
            }
            if (!preset.fitsJpeg(srcWidth, srcHeight)) {
                sb.append('\n').append(getString(R.string.target_jpeg_limit));
            }
            long estimate = estimatedBytes(t[0], t[1]);
            long free = DeviceProfile.freeSpaceBytes(getFilesDir());
            sb.append('\n').append(getString(R.string.target_estimate,
                    formatSize(estimate), formatSize(free)));
            if (estimate > free) sb.append("  — ").append(getString(R.string.target_not_enough));
            targetInfo.setText(sb.toString());
        } else {
            targetInfo.setText(R.string.pick_photo_first);
        }

        stage1.setSelected(stages == 1);
        stage2.setSelected(stages == 2);
        stage2.setEnabled(model.isNeural());
        if (!model.isNeural()) {
            stageInfo.setText(R.string.pass_classic_note);
        } else if (stages == 1) {
            stageInfo.setText(getString(R.string.pass_single_note, model.scale));
        } else {
            StringBuilder sb = new StringBuilder(getString(R.string.pass_double_note,
                    model.scale, model.scale * model.scale));
            if (hasSource) {
                long midBytes = (long) srcWidth * srcHeight * model.scale * model.scale * 3L;
                sb.append(' ').append(getString(R.string.pass_double_space,
                        midBytes / 1073741824.0));
            }
            stageInfo.setText(sb.toString());
        }

        boolean jpegPossible = !hasSource || preset.fitsJpeg(srcWidth, srcHeight);
        if (!jpegPossible) jpeg = false;
        formatJpeg.setEnabled(jpegPossible);
        formatJpeg.setAlpha(jpegPossible ? 1f : 0.4f);

        denoiseToggle.setText(denoiseMode == 0 ? R.string.denoise_auto
                : (denoiseMode == 1 ? R.string.denoise_on : R.string.denoise_off));
        boolean denoiseOn = denoiseActive();
        denoiseToggle.setSelected(denoiseOn);
        denoiseToggle.setTextColor(getColor(denoiseOn ? R.color.bg : R.color.content_soft));
        if (denoiseMode == 0) {
            denoiseInfo.setText(denoiseOn ? R.string.denoise_auto_active : R.string.denoise_auto_inactive);
        } else {
            denoiseInfo.setText(denoiseOn ? R.string.denoise_active : R.string.denoise_inactive);
        }

        sharpenValue.setText(String.format(Locale.getDefault(), "%%%d", sharpenSeek.getProgress()));
        qualityValue.setText(String.valueOf(jpegQuality()));
        qualityRow.setAlpha(jpeg ? 1f : 0.35f);
        qualitySeek.setAlpha(jpeg ? 1f : 0.35f);
        qualitySeek.setEnabled(jpeg);
        formatJpeg.setSelected(jpeg);
        formatPng.setSelected(!jpeg);
        formatJpeg.setTextColor(getColor(jpeg ? R.color.bg : R.color.content_soft));
        formatPng.setTextColor(getColor(jpeg ? R.color.content_soft : R.color.bg));
        stage1.setTextColor(getColor(stages == 1 ? R.color.bg : R.color.content_soft));
        stage2.setTextColor(getColor(!model.isNeural() ? R.color.content_ghost
                : (stages == 2 ? R.color.bg : R.color.content_soft)));

        startButton.setEnabled(hasSource && UpscaleJob.current() == null);
    }

    private boolean denoiseActive() {
        if (denoiseMode == 1) return true;
        if (denoiseMode == 2) return false;
        return preset.needsDenoise();
    }

    /** Kaba cikis dosyasi tahmini: PNG'de piksel basina ~1.2 bayt, JPEG'de ~0.06. */
    private long estimatedBytes(int w, int h) {
        double perPixel = jpeg && preset.fitsJpeg(srcWidth, srcHeight) ? 0.06 : 1.2;
        return (long) (w * (double) h * perPixel);
    }

    private String formatSize(long bytes) {
        if (bytes >= (1L << 30)) return String.format(Locale.getDefault(), "%.1f GB", bytes / 1073741824.0);
        return String.format(Locale.getDefault(), "%.0f MB", bytes / 1048576.0);
    }

    private int jpegQuality() {
        return 70 + qualitySeek.getProgress();   // 70..100
    }

    // ------------------------------------------------------------------ is baslatma

    private void startJob() {
        if (sourceUri == null || UpscaleJob.current() != null) return;
        int[] t = preset.targetSize(srcWidth, srcHeight);

        long estimate = estimatedBytes(t[0], t[1]);
        long free = DeviceProfile.freeSpaceBytes(getFilesDir());
        if (estimate > free) {
            toast(getString(R.string.not_enough_space, formatSize(estimate), formatSize(free)));
            return;
        }

        boolean useJpeg = jpeg && preset.fitsJpeg(srcWidth, srcHeight);
        UpscaleJob job = new UpscaleJob(sourceUri, preset, t[0], t[1], model, stages,
                denoiseActive(), loadLevel.threads(device), loadLevel.tileSize(device),
                loadLevel.breatherMillis(), useJpeg, jpegQuality(),
                sharpenSeek.getProgress() / 100f);
        UpscaleJob.setCurrent(job);
        job.setListener(this);
        UpscaleService.start(this);

        try {
            JSONObject d = new JSONObject();
            d.put("preset", preset.label);
            d.put("model", model.label);
            d.put("passes", stages);
            d.put("load", loadLevel.name());
            Reporter.event(this, "job_started", d);
        } catch (Throwable ignored) {
        }

        resultCard.setVisibility(View.GONE);
        progressCard.setVisibility(View.VISIBLE);
        cancelButton.setEnabled(true);
        startButton.setEnabled(false);
        progressBar.setProgress(0);
        progressText.setText(R.string.preparing);
        progressPercent.setText("");
    }

    @Override public void onJobChanged(UpscaleJob job) {
        if (!job.finished) {
            progressCard.setVisibility(View.VISIBLE);
            progressBar.setProgress((int) (job.progress * 1000));
            progressText.setText(job.stage);
            progressPercent.setText(String.format(Locale.getDefault(), "%%%d",
                    (int) (job.progress * 100)));
            startButton.setEnabled(false);
            return;
        }

        UpscaleJob.setCurrent(null);
        job.setListener(null);
        progressCard.setVisibility(View.GONE);
        startButton.setEnabled(sourceUri != null);

        if (job.cancelled) {
            toast(getString(R.string.cancelled));
            return;
        }
        if (job.error != null) {
            resultCard.setVisibility(View.VISIBLE);
            resultTitle.setText(R.string.result_failed);
            resultMark.setAlpha(0.35f);
            resultInfo.setText(job.error);
            resultPreview.setImageDrawable(null);
            openButton.setEnabled(false);
            shareButton.setEnabled(false);
            return;
        }

        lastResult = job;
        resultCard.setVisibility(View.VISIBLE);
        resultTitle.setText(R.string.result_ready);
        resultMark.setAlpha(1f);
        openButton.setEnabled(true);
        shareButton.setEnabled(true);
        if (job.preview != null && job.previewWidth > 0) {
            resultPreview.setImageBitmap(Bitmap.createBitmap(job.preview, job.previewWidth,
                    job.previewHeight, Bitmap.Config.ARGB_8888));
        }
        String engine = job.usedModel != null && job.usedModel.isNeural()
                ? job.usedModel.label
                        + (job.usedStages > 1 ? getString(R.string.passes_suffix, job.usedStages) : "")
                        + (job.usedGpu ? " (GPU)" : " (CPU)")
                : SrModel.LANCZOS.label;
        if (job.usedDenoise) engine += getString(R.string.denoise_suffix);
        resultInfo.setText(getString(R.string.result_format,
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
            toast(getString(R.string.no_app_found));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
