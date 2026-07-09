package org.thoughtcrime.securesms.components.emoji;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Draws the Unicode replacement character (U+FFFD, "&#65533;"), scaled to fill its bounds.
 *
 * <p>Shown in place of remote inline media that the inline-media privacy setting prevents from
 * loading, so the user sees a clear "blocked" marker rather than empty space. Purely local: it never
 * touches the network.
 */
final class ReplacementCharDrawable extends Drawable {

  private static final String GLYPH             = "�";
  private static final int    INTRINSIC_SIZE_PX = 64;

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  ReplacementCharDrawable(int color) {
    paint.setColor(color);
    paint.setTextAlign(Paint.Align.CENTER);
  }

  @Override
  public void draw(@NonNull Canvas canvas) {
    Rect bounds = getBounds();
    if (bounds.isEmpty()) {
      return;
    }

    paint.setTextSize(bounds.height() * 0.9f);

    Paint.FontMetrics fm = paint.getFontMetrics();
    float x = bounds.exactCenterX();
    float y = bounds.exactCenterY() - (fm.ascent + fm.descent) / 2f;

    canvas.drawText(GLYPH, x, y, paint);
  }

  @Override
  public int getIntrinsicWidth() {
    return INTRINSIC_SIZE_PX;
  }

  @Override
  public int getIntrinsicHeight() {
    return INTRINSIC_SIZE_PX;
  }

  @Override
  public void setAlpha(int alpha) {
    paint.setAlpha(alpha);
  }

  @Override
  public void setColorFilter(@Nullable ColorFilter colorFilter) {
    paint.setColorFilter(colorFilter);
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
