package com.soundbridge.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.soundbridge.app.R;

import java.util.Locale;

/**
 * Custom View rendering live ambient sound level, 20-bar smooth equalizer history,
 * pulse halo indicator, and active sound recognition status.
 * Fully Day/Night theme-aware with zero allocations in onDraw().
 */
public class SoundLevelMeterView extends View {
    private static final int BAR_COUNT = 20;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barInactivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF cardRect = new RectF();
    private final RectF barRect = new RectF();
    private final float[] barHistory = new float[BAR_COUNT];

    private boolean monitoringActive = false;
    private boolean alertHighlight = false;
    private float currentLevel = 0f;
    private String candidateText = "Ready to listen";

    public SoundLevelMeterView(Context context) {
        super(context);
        init();
    }

    public SoundLevelMeterView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SoundLevelMeterView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;

        bgPaint.setStyle(Paint.Style.FILL);
        barInactivePaint.setStyle(Paint.Style.FILL);
        barActivePaint.setStyle(Paint.Style.FILL);
        pulsePaint.setStyle(Paint.Style.FILL);

        textPaint.setTextSize(15f * scaledDensity);
        textPaint.setFakeBoldText(true);

        subTextPaint.setTextSize(12.5f * scaledDensity);

        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateAccessibilityDescription();
        setMinimumHeight((int) (118 * density));
    }

    public void setMonitoringState(boolean active, String label) {
        this.monitoringActive = active;
        if (!active) {
            this.currentLevel = 0f;
            this.alertHighlight = false;
            for (int i = 0; i < BAR_COUNT; i++) {
                barHistory[i] = 0f;
            }
        }
        if (label != null && !label.isEmpty()) {
            this.candidateText = label;
        } else {
            this.candidateText = active ? "Listening to your surroundings" : "Ready to listen";
        }
        updateAccessibilityDescription();
        invalidate();
    }

    public void updateAudioLevel(float rmsLevel, String topCandidate, float confidence, boolean isAlertFlash) {
        float visualLevel = Math.max(0f, Math.min(1f, (float) Math.sqrt(rmsLevel * 6.0f)));
        this.currentLevel = visualLevel;
        this.alertHighlight = isAlertFlash;

        System.arraycopy(barHistory, 1, barHistory, 0, BAR_COUNT - 1);
        barHistory[BAR_COUNT - 1] = visualLevel;

        if (topCandidate != null && !topCandidate.isEmpty()) {
            if (confidence >= 0.20f) {
                int parenIdx = topCandidate.indexOf(" (");
                String cleanLabel = parenIdx > 0 ? topCandidate.substring(0, parenIdx).trim() : topCandidate;
                this.candidateText = String.format(Locale.US, "%s • %d%% match", cleanLabel, Math.round(confidence * 100f));
            } else {
                this.candidateText = topCandidate;
            }
        }
        updateAccessibilityDescription();
        invalidate();
    }

    private void updateAccessibilityDescription() {
        String stateDesc = monitoringActive ? "Listening active" : "Listening paused";
        int pct = Math.round(currentLevel * 100f);
        setContentDescription(String.format(
                Locale.US,
                "Sound level meter: %s, room volume %d percent, %s",
                stateDesc,
                pct,
                candidateText
        ));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        int desiredWidth = (int) (320 * density);
        int desiredHeight = (int) (122 * density);

        int width = resolveSize(desiredWidth, widthMeasureSpec);
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        Context ctx = getContext();
        float density = getResources().getDisplayMetrics().density;
        float cornerRadius = 16f * density;
        float pad = 16f * density;

        int colorPrimaryText = ContextCompat.getColor(ctx, R.color.text_primary);
        int colorSecondaryText = ContextCompat.getColor(ctx, R.color.text_secondary);
        int colorInactiveBar = ContextCompat.getColor(ctx, R.color.meter_bar_inactive);
        int colorActiveTeal = ContextCompat.getColor(ctx, R.color.accent_teal);
        int colorWarningAmber = ContextCompat.getColor(ctx, R.color.alert_warning_amber);
        int colorUrgentRed = ContextCompat.getColor(ctx, R.color.alert_urgent_red);

        textPaint.setColor(colorPrimaryText);
        subTextPaint.setColor(colorSecondaryText);
        barInactivePaint.setColor(colorInactiveBar);

        if (alertHighlight) {
            bgPaint.setColor(ContextCompat.getColor(ctx, R.color.alert_urgent_bg));
            pulsePaint.setColor(colorUrgentRed);
        } else if (monitoringActive) {
            bgPaint.setColor(ContextCompat.getColor(ctx, R.color.status_monitoring_bg));
            pulsePaint.setColor(ContextCompat.getColor(ctx, R.color.status_monitoring_active));
        } else {
            bgPaint.setColor(ContextCompat.getColor(ctx, R.color.status_stopped_bg));
            pulsePaint.setColor(ContextCompat.getColor(ctx, R.color.status_monitoring_stopped));
        }

        cardRect.set(0, 0, w, h);
        canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, bgPaint);

        // Status indicator dot with soft live halo
        float dotX = pad + 8f * density;
        float dotY = pad + 11f * density;
        float baseRadius = 5.5f * density;
        if (monitoringActive) {
            pulsePaint.setAlpha(65);
            canvas.drawCircle(dotX, dotY, baseRadius + (currentLevel * 10f * density), pulsePaint);
            pulsePaint.setAlpha(255);
        }
        canvas.drawCircle(dotX, dotY, baseRadius, pulsePaint);

        // Headline & calm subtitle
        float textX = dotX + 16f * density;
        canvas.drawText(candidateText, textX, dotY + 5f * density, textPaint);

        String subLine;
        if (monitoringActive) {
            int volPct = Math.round(currentLevel * 100f);
            String levelWord = volPct < 15 ? "Quiet" : (volPct < 55 ? "Moderate" : "Loud");
            subLine = String.format(Locale.US, "Room sound: %s (%d%%)", levelWord, volPct);
        } else {
            subLine = "Tap Start Listening below to turn on alerts";
        }
        canvas.drawText(subLine, textX, dotY + 23f * density, subTextPaint);

        // 20-bar smooth rounded sound-wave meter
        float meterTop = dotY + 36f * density;
        float meterBottom = h - pad;
        float maxBarHeight = Math.max(16f * density, meterBottom - meterTop);
        float totalMeterWidth = w - (2 * pad);
        float gap = 5f * density;
        float barWidth = Math.max(4f, (totalMeterWidth - gap * (BAR_COUNT - 1)) / BAR_COUNT);
        float pillRadius = barWidth / 2f;

        for (int i = 0; i < BAR_COUNT; i++) {
            float left = pad + i * (barWidth + gap);
            float right = left + barWidth;

            barRect.set(left, meterTop, right, meterBottom);
            canvas.drawRoundRect(barRect, pillRadius, pillRadius, barInactivePaint);

            float lvl = barHistory[i];
            if (lvl > 0.02f) {
                if (lvl > 0.75f || alertHighlight) {
                    barActivePaint.setColor(colorUrgentRed);
                } else if (lvl > 0.45f) {
                    barActivePaint.setColor(colorWarningAmber);
                } else {
                    barActivePaint.setColor(colorActiveTeal);
                }
                float activeH = Math.max(barWidth, lvl * maxBarHeight);
                barRect.set(left, meterBottom - activeH, right, meterBottom);
                canvas.drawRoundRect(barRect, pillRadius, pillRadius, barActivePaint);
            }
        }
    }
}
