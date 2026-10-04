package org.thoughtcrime.securesms.components.emoji;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.core.util.ThreadUtil;
import org.thoughtcrime.securesms.keyvalue.SignalStore;

public final class CustomEmojiImageBinder {

  private CustomEmojiImageBinder() {}

  static void bind(@NonNull ImageView view, @NonNull CharSequence token) {
    String tokenString = token.toString();
    String source = CustomEmojiRegistry.getSource(view.getContext().getApplicationContext(), tokenString);

    view.setTag(tokenString);

    Drawable previous = view.getDrawable();
    if (previous instanceof Animatable) {
      ((Animatable) previous).stop();
    }

    if (source == null) {
      view.setImageDrawable(null);
      return;
    }

    // When the inline-media setting is off, never fetch remote media (not even from cache): show the
    // U+FFFD replacement glyph instead. Local pack files still render.
    if (!SignalStore.settings().isInlineUrlMediaEnabled() && isRemoteSource(source)) {
      view.setImageDrawable(new ReplacementCharDrawable(resolveGlyphColor(view.getContext())));
      return;
    }

    Drawable cached = InlineMediaProvider.getCachedDrawable(view.getContext(), tokenString);
    if (cached != null) {
      view.setImageDrawable(cached);
      if (cached instanceof Animatable) {
        ((Animatable) cached).start();
      }
      return;
    }

    view.setImageDrawable(null);

    InlineMediaProvider.ensureLoadedAsync(
        tokenString,
        source,
        () -> view.post(() -> {
          Object boundTag = view.getTag();
          if (!(boundTag instanceof String) || !tokenString.equals(boundTag)) {
            return;
          }

          Drawable drawable = InlineMediaProvider.getCachedDrawable(view.getContext(), tokenString);
          if (drawable == null) {
            return;
          }

          Drawable current = view.getDrawable();
          if (current instanceof Animatable) {
            ((Animatable) current).stop();
          }

          view.setImageDrawable(drawable);
          if (drawable instanceof Animatable) {
            ((Animatable) drawable).start();
          }
        })
    );
  }

  /**
   * A standalone drawable for a custom token, for hosts that render a {@link Drawable} rather than an
   * {@link ImageView} (the Compose media keyboard). Returns a placeholder immediately and swaps the real
   * (possibly animated) image in once it loads, invalidating itself. Follows the same privacy rule as
   * {@link #bind}: blocked remote media is drawn as the U+FFFD glyph. Null if the token isn't registered.
   */
  public static @Nullable Drawable createDrawable(@NonNull Context context, @NonNull String token, int sizePx) {
    String source = CustomEmojiRegistry.getSource(context.getApplicationContext(), token);
    if (source == null) {
      return null;
    }

    if (!SignalStore.settings().isInlineUrlMediaEnabled() && isRemoteSource(source)) {
      return new ReplacementCharDrawable(resolveGlyphColor(context));
    }

    InlineMediaDrawable wrapper = new InlineMediaDrawable(sizePx);

    Drawable cached = InlineMediaProvider.getCachedDrawable(context, token);
    if (cached != null) {
      wrapper.setLoadedDrawable(cached, sizePx);
      return wrapper;
    }

    InlineMediaProvider.ensureLoadedAsync(token, source, () -> ThreadUtil.runOnMain(() -> {
      Drawable loaded = InlineMediaProvider.getCachedDrawable(context, token);
      if (loaded != null) {
        wrapper.setLoadedDrawable(loaded, sizePx);
      }
    }));

    return wrapper;
  }

  private static boolean isRemoteSource(@NonNull String source) {
    return source.startsWith("http://") || source.startsWith("https://");
  }

  private static int resolveGlyphColor(@NonNull Context context) {
    TypedArray attrs = context.obtainStyledAttributes(new int[]{ android.R.attr.textColorSecondary });
    try {
      return attrs.getColor(0, 0xFF888888);
    } finally {
      attrs.recycle();
    }
  }
}
