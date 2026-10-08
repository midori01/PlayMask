package com.midori.playmask;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * Custom vector drawable that renders Material Design Eye (Visible)
 * and Eye-Off (Hidden with diagonal slash and transparent cutout gaps).
 * Fully self-contained, GPU-accelerated, zero XML/asset dependencies.
 */
public class MaskDrawable extends Drawable {
    private boolean mMasked;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mClearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mEyePath = new Path();
    private ColorStateList mTint;
    private PorterDuff.Mode mTintMode = PorterDuff.Mode.SRC_IN;

    public MaskDrawable(boolean masked) {
        this.mMasked = masked;
        mPaint.setColor(Color.WHITE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeJoin(Paint.Join.ROUND);

        mClearPaint.setStyle(Paint.Style.STROKE);
        mClearPaint.setStrokeCap(Paint.Cap.ROUND);
        mClearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        mClearPaint.setColor(Color.TRANSPARENT);

        initEyePath();
    }

    private void initEyePath() {
        mEyePath.reset();
        // Standard Material Design 24x24 Eye contour
        mEyePath.moveTo(2.0f, 12.0f);
        mEyePath.cubicTo(6.5f, 6.0f, 17.5f, 6.0f, 22.0f, 12.0f);
        mEyePath.cubicTo(17.5f, 18.0f, 6.5f, 18.0f, 2.0f, 12.0f);
        mEyePath.close();
    }

    public void setMasked(boolean masked) {
        if (this.mMasked != masked) {
            this.mMasked = masked;
            invalidateSelf();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;

        float width = bounds.width();
        float height = bounds.height();
        float size = Math.min(width, height);
        float s = size / 24.0f;
        float dx = bounds.left + (width - 24.0f * s) / 2.0f;
        float dy = bounds.top + (height - 24.0f * s) / 2.0f;

        canvas.save();
        canvas.translate(dx, dy);
        canvas.scale(s, s);

        if (mMasked) {
            // Masked state: Eye with diagonal slash and transparent cutout buffer
            canvas.saveLayer(0, 0, 24.0f, 24.0f, null);

            // 1. Outer eye outline
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(1.8f);
            canvas.drawPath(mEyePath, mPaint);

            // 2. Center pupil
            mPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(12.0f, 12.0f, 3.2f, mPaint);

            // 3. Clear cutout along diagonal slash (stroke 3.8f)
            mClearPaint.setStrokeWidth(3.8f);
            canvas.drawLine(3.5f, 3.5f, 20.5f, 20.5f, mClearPaint);

            // 4. Foreground diagonal slash line (stroke 2.0f)
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(2.0f);
            canvas.drawLine(3.5f, 3.5f, 20.5f, 20.5f, mPaint);

            canvas.restore();
        } else {
            // Unmasked state: Eye Open
            // 1. Outer eye outline
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(1.8f);
            canvas.drawPath(mEyePath, mPaint);

            // 2. Center pupil
            mPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(12.0f, 12.0f, 3.2f, mPaint);
        }

        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public void setTintList(ColorStateList tint) {
        this.mTint = tint;
        updateTintFilter();
        invalidateSelf();
    }

    @Override
    public void setTintMode(PorterDuff.Mode tintMode) {
        this.mTintMode = tintMode;
        updateTintFilter();
        invalidateSelf();
    }

    private void updateTintFilter() {
        if (mTint != null && mTintMode != null) {
            int color = mTint.getColorForState(getState(), Color.WHITE);
            mPaint.setColorFilter(new PorterDuffColorFilter(color, mTintMode));
        } else {
            mPaint.setColorFilter(null);
        }
    }

    @Override
    public boolean isStateful() {
        return mTint != null && mTint.isStateful();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        if (mTint != null) {
            updateTintFilter();
            invalidateSelf();
            return true;
        }
        return super.onStateChange(state);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
