package org.thoughtcrime.securesms.components.emoji;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.signal.emoji.AnimatingImageSpan;

public final class InlineMediaSpan extends AnimatingImageSpan {
  private final FontMetricsInt originalFm;

  public InlineMediaSpan(@NonNull Drawable drawable, @NonNull TextView textView) {
    super(drawable, textView);
    this.originalFm = textView.getPaint().getFontMetricsInt();
  }

  @Override
  public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, FontMetricsInt fm) {
    Drawable drawable = getDrawable();
    Rect bounds = drawable.getBounds();

    if (fm != null) {
      int originalHeight = originalFm.descent - originalFm.ascent;
      int imageHeight = bounds.height();

      if (imageHeight <= originalHeight) {
        fm.ascent = originalFm.ascent;
        fm.descent = originalFm.descent;
        fm.top = originalFm.top;
        fm.bottom = originalFm.bottom;
      } else {
        int extra = imageHeight - originalHeight;
        int extraTop = extra / 2;
        int extraBottom = extra - extraTop;

        fm.ascent = originalFm.ascent - extraTop;
        fm.descent = originalFm.descent + extraBottom;
        fm.top = fm.ascent;
        fm.bottom = fm.descent;
      }
    }

    return bounds.width();
  }

  @Override
  public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x,
                   int top, int y, int bottom, @NonNull Paint paint) {
    Drawable drawable = getDrawable();
    Rect bounds = drawable.getBounds();

    Paint.FontMetricsInt fm = paint.getFontMetricsInt();
    int textCenter = y + (fm.descent + fm.ascent) / 2;
    int transY = textCenter - bounds.height() / 2;

    canvas.save();
    canvas.translate(x, transY);
    drawable.draw(canvas);
    canvas.restore();
  }
}
