package org.thoughtcrime.securesms.components.emoji;

import android.content.Context;
import android.text.Editable;
import android.text.Selection;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Converts a completed custom-emoji alias typed in a compose field (e.g. {@code :smile:}) into the
 * emoji's canonical token (e.g. {@code :smiley123:}), in place, keeping the cursor sensible.
 *
 * <p>Aliases are a search / type-in convenience only; the token is the sole identity that is rendered
 * and sent. Swapping here means a sent message body always contains tokens, never aliases — so changing
 * or removing an alias never affects messages that were already sent. (Selecting from the picker or the
 * {@code :}-autocomplete popup already inserts the token directly, so those paths don't need this.)
 */
public final class CustomEmojiAliasResolver {

  private CustomEmojiAliasResolver() {}

  /** Swaps every completed {@code :alias:} in {@code editable} for its token. Returns true if it changed anything. */
  public static boolean swapCompletedAliases(@NonNull Editable editable, @NonNull Context context) {
    List<Match> matches = findMatches(editable, context);
    if (matches.isEmpty()) {
      return false;
    }

    int selStart = Selection.getSelectionStart(editable);
    int selEnd   = Selection.getSelectionEnd(editable);

    // Apply right-to-left so earlier indices remain valid as the text length changes.
    for (int i = matches.size() - 1; i >= 0; i--) {
      Match m     = matches.get(i);
      int   delta = m.token.length() - (m.end - m.start);

      editable.replace(m.start, m.end, m.token);

      selStart = adjust(selStart, m.start, m.end, m.token.length(), delta);
      selEnd   = adjust(selEnd, m.start, m.end, m.token.length(), delta);
    }

    if (selStart >= 0 && selEnd >= 0) {
      int length = editable.length();
      Selection.setSelection(editable, Math.min(selStart, length), Math.min(selEnd, length));
    }

    return true;
  }

  private static int adjust(int pos, int start, int end, int tokenLength, int delta) {
    if (pos < 0 || pos <= start) {
      return pos;
    }
    if (pos >= end) {
      return pos + delta;
    }
    return start + tokenLength;
  }

  private static @NonNull List<Match> findMatches(@NonNull CharSequence text, @NonNull Context context) {
    List<String> aliases = CustomEmojiRegistry.getAliases(context);
    if (aliases.isEmpty()) {
      return Collections.emptyList();
    }

    String      value = text.toString();
    List<Match> found = new ArrayList<>();

    for (String alias : aliases) {
      String token = CustomEmojiRegistry.getAliasToken(context, alias);
      if (token == null) {
        continue;
      }

      int from = 0;
      while (true) {
        int idx = value.indexOf(alias, from);
        if (idx < 0) {
          break;
        }
        found.add(new Match(idx, idx + alias.length(), token));
        from = idx + alias.length();
      }
    }

    found.sort(Comparator.comparingInt((Match m) -> m.start).thenComparingInt(m -> -(m.end - m.start)));

    List<Match> nonOverlapping = new ArrayList<>();
    int         consumedUpTo   = 0;
    for (Match m : found) {
      if (m.start >= consumedUpTo) {
        nonOverlapping.add(m);
        consumedUpTo = m.end;
      }
    }

    return nonOverlapping;
  }

  private static final class Match {
    final int    start;
    final int    end;
    final String token;

    Match(int start, int end, @NonNull String token) {
      this.start = start;
      this.end   = end;
      this.token = token;
    }
  }
}
