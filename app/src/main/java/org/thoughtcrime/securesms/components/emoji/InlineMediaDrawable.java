package org.thoughtcrime.securesms.components.emoji;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class InlineMediaDrawable extends Drawable implements Drawable.Callback, Animatable {
  private Drawable inner = new ColorDrawable(0x00000000);
  private int      intrinsicWidth;
  private int      intrinsicHeight;

  InlineMediaDrawable(int placeholderSizePx) {
    intrinsicWidth  = placeholderSizePx;
    intrinsicHeight = placeholderSizePx;
    setBounds(0, 0, placeholderSizePx, placeholderSizePx);
    inner.setBounds(getBounds());
    inner.setCallback(this);
  }

  void setLoadedDrawable(@NonNull Drawable drawable, int targetHeightPx) {
    int iw = Math.max(1, drawable.getIntrinsicWidth());
    int ih = Math.max(1, drawable.getIntrinsicHeight());
    int targetWidth = Math.max(1, iw * targetHeightPx / ih);

    if (inner instanceof Animatable) {
      ((Animatable) inner).stop();
    }

    inner.setCallback(null);
    inner = drawable;
    inner.setCallback(this);

    intrinsicWidth  = targetWidth;
    intrinsicHeight = targetHeightPx;

    Rect bounds = new Rect(0, 0, targetWidth, targetHeightPx);
    setBounds(bounds);
    inner.setBounds(bounds);

    invalidateSelf();

    if (inner instanceof Animatable) {
      ((Animatable) inner).start();
    }
  }

  @Override
  protected void onBoundsChange(Rect bounds) {
    super.onBoundsChange(bounds);
    inner.setBounds(bounds);
  }

  // Reported so hosts that size from intrinsics (e.g. a Compose DrawablePainter) keep the aspect ratio.
  @Override
  public int getIntrinsicWidth() {
    return intrinsicWidth;
  }

  @Override
  public int getIntrinsicHeight() {
    return intrinsicHeight;
  }

  @Override
  public void draw(@NonNull Canvas canvas) {
    inner.draw(canvas);
  }

  @Override
  public void setAlpha(int alpha) {
    inner.setAlpha(alpha);
  }

  @Override
  public void setColorFilter(@Nullable ColorFilter colorFilter) {
    inner.setColorFilter(colorFilter);
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }

  @Override
  public void invalidateDrawable(@NonNull Drawable who) {
    invalidateSelf();
  }

  @Override
  public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
    scheduleSelf(what, when);
  }

  @Override
  public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
    unscheduleSelf(what);
  }

  @Override
  public void start() {
    if (inner instanceof Animatable) {
      ((Animatable) inner).start();
    }
  }

  @Override
  public void stop() {
    if (inner instanceof Animatable) {
      ((Animatable) inner).stop();
    }
  }

  @Override
  public boolean isRunning() {
    return inner instanceof Animatable && ((Animatable) inner).isRunning();
  }
}
