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
 * Converts a custom-emoji <b>literal</b> typed in a compose field into the emoji's canonical token, in
 * place. Literals are the colon-free counterpart of aliases: instead of being typed wrapped in colons
 * (e.g. {@code :smile:}), a literal is typed as a bare, whitespace-delimited word (e.g. {@code o7},
 * {@code :D}) and is recognised only once it is <b>completed with a space</b>.
 *
 * <p>A literal matches when it is a whole single word — its first character sits at the start of the
 * text or right after whitespace (a space, tab or newline), and it is immediately followed by a space.
 * That one triggering space is consumed as part of the swap: {@code "o7 "} becomes {@code ":salute:"}
 * (no trailing space). If more than one space follows, only the first is consumed, so {@code "o7  "}
 * becomes {@code ":salute: "}. Because a literal never starts a {@code :}-query, typing one shows no
 * autocomplete popup.
 *
 * <p>Like aliases, literals are a search / type-in convenience only; the token is the sole identity that
 * is rendered and sent. Swapping here means a sent message body always contains tokens, never literals —
 * so changing or removing a literal never affects messages that were already sent.
 */
public final class CustomEmojiLiteralResolver {

  private CustomEmojiLiteralResolver() {}

  /** Swaps every whole-word literal immediately followed by a space (that one space included) for its token. Returns true if it changed anything. */
  public static boolean swapCompletedLiterals(@NonNull Editable editable, @NonNull Context context) {
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
    List<String> literals = CustomEmojiRegistry.getLiterals(context);
    if (literals.isEmpty()) {
      return Collections.emptyList();
    }

    String      value = text.toString();
    List<Match> found = new ArrayList<>();

    for (String literal : literals) {
      String token = CustomEmojiRegistry.getLiteralToken(context, literal);
      if (token == null || literal.isEmpty()) {
        continue;
      }

      int from = 0;
      while (true) {
        int idx = value.indexOf(literal, from);
        if (idx < 0) {
          break;
        }

        int     after   = idx + literal.length();
        boolean leftOk  = idx == 0 || Character.isWhitespace(value.charAt(idx - 1));
        boolean rightOk = after < value.length() && value.charAt(after) == ' ';

        if (leftOk && rightOk) {
          // The match spans the literal plus the one triggering space, both replaced by the token.
          found.add(new Match(idx, after + 1, token));
        }

        from = after;
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
