package org.thoughtcrime.securesms.components.emoji;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class CustomEmojiParser {

  static final class Candidate {
    final int start;
    final int end;
    final String token;

    Candidate(int start, int end, @NonNull String token) {
      this.start = start;
      this.end = end;
      this.token = token;
    }
  }

  static @NonNull List<Candidate> find(@NonNull Context context, @NonNull CharSequence text) {
    List<Candidate> out = new ArrayList<>();
    String value = text.toString();

    for (String token : CustomEmojiRegistry.getTokens(context)) {
      int from = 0;

      while (true) {
        int idx = value.indexOf(token, from);
        if (idx < 0) {
          break;
        }

        out.add(new Candidate(idx, idx + token.length(), token));
        from = idx + token.length();
      }
    }

    out.sort(Comparator
        .comparingInt((Candidate c) -> c.start)
        .thenComparingInt(c -> -(c.end - c.start)));

    return out;
  }

  private CustomEmojiParser() {}
}
