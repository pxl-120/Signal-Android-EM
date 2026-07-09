package org.thoughtcrime.securesms.components.emoji;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.util.Log;
import android.util.LruCache;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.keyvalue.SignalStore;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Central rendering / cache / loading layer for inline media in text:
 *
 * <ul>
 *   <li>registered custom-emoji tokens like {@code :name:}, and</li>
 *   <li>colon-wrapped image URLs like {@code :https://….gif:}.</li>
 * </ul>
 *
 * <p>Both are located, then drawn as {@link InlineMediaSpan}s. The drawable for each match is loaded
 * (and byte-cached) asynchronously: a placeholder span is attached immediately and its drawable is
 * swapped in place once the bytes arrive — {@link #inlinify} never re-sets the host {@link TextView}'s
 * text from a background callback.
 */
public final class InlineMediaProvider {

  interface LoadListener {
    void onLoaded();
  }

  private static final String          TAG                = "InlineMedia";
  private static final ExecutorService EXECUTOR           = Executors.newCachedThreadPool();
  private static final int             MAX_CACHE_BYTES    = 20 * 1024 * 1024;
  private static final float           INLINE_MEDIA_SCALE = 1.6f;

  private static final LruCache<String, byte[]> DATA_CACHE = new LruCache<String, byte[]>(MAX_CACHE_BYTES) {
    @Override
    protected int sizeOf(@NonNull String key, @NonNull byte[] value) {
      return value.length;
    }
  };

  private static final Set<String>                                              IN_FLIGHT = ConcurrentHashMap.newKeySet();
  private static final ConcurrentHashMap<String, CopyOnWriteArrayList<LoadListener>> PENDING   = new ConcurrentHashMap<>();

  private InlineMediaProvider() {}

  /**
   * Returns a copy of {@code source} with an {@link InlineMediaSpan} over every custom token and
   * colon-wrapped image URL. Idempotent: any pre-existing inline spans are stripped first, so it is
   * safe to call repeatedly (e.g. on every keystroke in the compose field).
   */
  public static @NonNull SpannableStringBuilder inlinify(@NonNull CharSequence source, @NonNull TextView tv) {
    SpannableStringBuilder builder = new SpannableStringBuilder(source);

    for (InlineMediaSpan span : builder.getSpans(0, builder.length(), InlineMediaSpan.class)) {
      builder.removeSpan(span);
    }

    Context appContext = tv.getContext().getApplicationContext();

    for (Match match : collectMatches(appContext, builder)) {
      if (match.blocked) {
        // Blocked remote token: show the replacement glyph in display contexts, but leave the literal
        // text in an editable (compose) field so the user still sees / sends what they typed.
        if (!(tv instanceof EditText)) {
          attachBlockedGlyph(builder, tv, match.start, match.end);
        }
      } else {
        attachInlineSpan(builder, tv, match.start, match.end, match.cacheKey, match.source);
      }
    }

    return builder;
  }

  /** All inline-media matches (URLs + custom tokens) in {@code text}, earliest first, non-overlapping. */
  private static @NonNull List<Match> collectMatches(@NonNull Context appContext, @NonNull CharSequence text) {
    List<Match> matches = new ArrayList<>();

    // The toggle gates fetching remote media from the network on render (IP-leak / tracking-pixel
    // protection). When off: a bare :url: is left as plain text (so the user can still see/copy it),
    // a custom token backed by a remote URL is shown as the U+FFFD replacement glyph (in display
    // contexts), and a custom token backed by a local pack file still renders.
    boolean remoteMediaEnabled = SignalStore.settings().isInlineUrlMediaEnabled();

    if (remoteMediaEnabled) {
      for (InlineMediaParser.Candidate url : InlineMediaParser.find(text)) {
        matches.add(Match.media(url.start, url.end, url.url, url.url));
      }
    }

    for (CustomEmojiParser.Candidate token : CustomEmojiParser.find(appContext, text)) {
      String source = CustomEmojiRegistry.getSource(appContext, token.token);
      if (source == null) {
        continue;
      }
      if (!remoteMediaEnabled && isRemoteSource(source)) {
        matches.add(Match.blocked(token.start, token.end));
      } else {
        matches.add(Match.media(token.start, token.end, token.token, source));
      }
    }

    matches.sort(Comparator.<Match>comparingInt(m -> m.start).thenComparingInt(m -> -(m.end - m.start)));

    List<Match> nonOverlapping = new ArrayList<>();
    int         consumedUpTo   = 0;
    for (Match match : matches) {
      if (match.start >= consumedUpTo) {
        nonOverlapping.add(match);
        consumedUpTo = match.end;
      }
    }

    return nonOverlapping;
  }

  private static boolean isRemoteSource(@NonNull String source) {
    return source.startsWith("http://") || source.startsWith("https://");
  }

  /** Draws the U+FFFD replacement glyph (sized to the line) over a range whose remote media is blocked. */
  private static void attachBlockedGlyph(@NonNull SpannableStringBuilder text, @NonNull TextView tv, int start, int end) {
    int size = Math.round(
        Math.abs(tv.getPaint().getFontMetricsInt().ascent) +
        Math.abs(tv.getPaint().getFontMetricsInt().descent)
    );

    ReplacementCharDrawable glyph = new ReplacementCharDrawable(tv.getCurrentTextColor());
    glyph.setBounds(0, 0, size, size);

    text.setSpan(new InlineMediaSpan(glyph, tv), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  private static void attachInlineSpan(@NonNull SpannableStringBuilder text,
                                       @NonNull TextView tv,
                                       int start,
                                       int end,
                                       @NonNull String cacheKey,
                                       @NonNull String source)
  {
    final int baseLineHeight = Math.round(
        Math.abs(tv.getPaint().getFontMetricsInt().ascent) +
        Math.abs(tv.getPaint().getFontMetricsInt().descent)
    );
    final int displayHeight = Math.round(baseLineHeight * INLINE_MEDIA_SCALE);

    final InlineMediaDrawable wrapper = new InlineMediaDrawable(displayHeight);

    text.setSpan(new InlineMediaSpan(wrapper, tv), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

    Drawable cached = getCachedDrawable(tv.getContext(), cacheKey);
    if (cached != null) {
      wrapper.setLoadedDrawable(cached, displayHeight);
      return;
    }

    ensureLoadedAsync(cacheKey, source, () -> tv.post(() -> {
      Drawable loaded = getCachedDrawable(tv.getContext(), cacheKey);
      if (loaded == null) {
        return;
      }

      wrapper.setLoadedDrawable(loaded, displayHeight);

      // Re-layout only; do not replace the whole text.
      tv.requestLayout();
      tv.invalidate();
    }));
  }

  static @Nullable Drawable getCachedDrawable(@NonNull Context context, @NonNull String cacheKey) {
    byte[] data = DATA_CACHE.get(cacheKey);
    if (data == null) {
      return null;
    }

    try {
      return decodeDrawable(context, data);
    } catch (Throwable t) {
      Log.e(TAG, "Cache decode failed: " + cacheKey, t);
      DATA_CACHE.remove(cacheKey);
      return null;
    }
  }

  static void ensureLoadedAsync(@NonNull String cacheKey, @NonNull String source, @NonNull LoadListener listener) {
    if (DATA_CACHE.get(cacheKey) != null) {
      listener.onLoaded();
      return;
    }

    PENDING.computeIfAbsent(cacheKey, unused -> new CopyOnWriteArrayList<>()).add(listener);

    if (!IN_FLIGHT.add(cacheKey)) {
      return;
    }

    EXECUTOR.execute(() -> {
      try {
        byte[] data = loadBytes(source);
        if (data == null || data.length == 0) {
          Log.e(TAG, "Load failed or empty: " + source);
          return;
        }

        DATA_CACHE.put(cacheKey, data);
      } catch (Throwable t) {
        Log.e(TAG, "Inline media failed: " + source, t);
      } finally {
        IN_FLIGHT.remove(cacheKey);

        CopyOnWriteArrayList<LoadListener> listeners = PENDING.remove(cacheKey);
        if (listeners != null) {
          for (LoadListener pending : listeners) {
            try {
              pending.onLoaded();
            } catch (Throwable t) {
              Log.e(TAG, "Pending listener failed: " + cacheKey, t);
            }
          }
        }
      }
    });
  }

  private static @Nullable byte[] loadBytes(@NonNull String source) throws Exception {
    if (source.startsWith("http://") || source.startsWith("https://")) {
      return downloadHttp(source);
    }

    if (source.startsWith("file://")) {
      return readAllBytes(new FileInputStream(source.substring("file://".length())));
    }

    if (source.startsWith("/")) {
      return readAllBytes(new FileInputStream(source));
    }

    Log.e(TAG, "Unsupported inline media source: " + source);
    return null;
  }

  private static @Nullable byte[] downloadHttp(@NonNull String urlString) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
    connection.setInstanceFollowRedirects(true);
    connection.setConnectTimeout(10000);
    connection.setReadTimeout(15000);
    connection.setRequestProperty("User-Agent", "Mozilla/5.0");
    connection.connect();

    int code = connection.getResponseCode();
    if (code < 200 || code >= 300) {
      Log.e(TAG, "HTTP " + code + " for " + urlString);
      return null;
    }

    String contentType = connection.getContentType();
    if (contentType == null || !contentType.startsWith("image/")) {
      Log.e(TAG, "Not an image: " + urlString + " content-type=" + contentType);
      return null;
    }

    try {
      return readAllBytes(connection.getInputStream());
    } finally {
      connection.disconnect();
    }
  }

  private static byte[] readAllBytes(@NonNull InputStream in) throws Exception {
    try (InputStream input = in;
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int    read;
      while ((read = input.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }

  private static @Nullable Drawable decodeDrawable(@NonNull Context context, @NonNull byte[] data) throws Exception {
    if (Build.VERSION.SDK_INT >= 28) {
      // Force a software-allocated bitmap. ImageDecoder.decodeDrawable() defaults to a hardware bitmap
      // (Bitmap.Config.HARDWARE) for non-animated images, which cannot be drawn onto a software Canvas.
      // The long-press reaction overlay snapshots the message into a software Bitmap/Canvas
      // (ConversationItemSelection#snapshotMessage / V2ConversationItemSnapshotStrategy), and drawing a
      // hardware bitmap there fails with "Software rendering doesn't support hardware bitmaps" — a crash
      // on some devices, a blank glyph on others. Animated images decode to an AnimatedImageDrawable and
      // are unaffected by this hint (they already snapshot as a single frozen frame).
      return ImageDecoder.decodeDrawable(
          ImageDecoder.createSource(ByteBuffer.wrap(data)),
          (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
    } else {
      Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
      if (bitmap == null) {
        return null;
      }
      return new BitmapDrawable(context.getResources(), bitmap);
    }
  }

  static void clearCache() {
    DATA_CACHE.evictAll();
    IN_FLIGHT.clear();
    PENDING.clear();
  }

  private static final class Match {
    final int     start;
    final int     end;
    final String  cacheKey;
    final String  source;
    final boolean blocked;

    private Match(int start, int end, String cacheKey, String source, boolean blocked) {
      this.start    = start;
      this.end      = end;
      this.cacheKey = cacheKey;
      this.source   = source;
      this.blocked  = blocked;
    }

    static Match media(int start, int end, @NonNull String cacheKey, @NonNull String source) {
      return new Match(start, end, cacheKey, source, false);
    }

    /** A range whose remote media is blocked by the inline-media setting; rendered as a replacement glyph. */
    static Match blocked(int start, int end) {
      return new Match(start, end, null, null, true);
    }
  }
}
