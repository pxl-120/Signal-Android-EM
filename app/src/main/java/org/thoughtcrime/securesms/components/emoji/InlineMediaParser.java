package org.thoughtcrime.securesms.components.emoji;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds colon-wrapped inline image URLs in text, e.g. {@code :https://example.com/cat.gif:}.
 *
 * <p>The surrounding colons are required — mirroring the {@code :token:} custom-emoji syntax — so a
 * bare URL in a message is left as ordinary text (and keeps its normal link preview). The colons are
 * part of the returned {@link Candidate} range (they get covered by the rendered image); the captured
 * {@link Candidate#url} excludes them.
 */
public final class InlineMediaParser {

  private static final Pattern URL_PATTERN = Pattern.compile(
      ":(https?://\\S+?\\.(?:gif|png|jpe?g|webp|avif)(?:\\?\\S*?)?):",
      Pattern.CASE_INSENSITIVE
  );

  static final class Candidate {
    final int    start;
    final int    end;
    final String url;

    Candidate(int start, int end, @NonNull String url) {
      this.start = start;
      this.end   = end;
      this.url   = url;
    }
  }

  public static @NonNull List<Candidate> find(@NonNull CharSequence text) {
    List<Candidate> out     = new ArrayList<>();
    Matcher         matcher = URL_PATTERN.matcher(text);

    while (matcher.find()) {
      out.add(new Candidate(matcher.start(), matcher.end(), matcher.group(1)));
    }

    return out;
  }

  private InlineMediaParser() {}
}
