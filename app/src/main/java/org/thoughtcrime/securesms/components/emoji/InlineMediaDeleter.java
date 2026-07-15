package org.thoughtcrime.securesms.components.emoji;

import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;

import androidx.annotation.Nullable;

/**
 * Deletes a whole inline-media chip ({@code :token:} / {@code :url:}) at once when a backspace lands
 * inside its {@link InlineMediaSpan}, instead of nibbling one character at a time and leaving a
 * half-token that keeps rendering as an image.
 *
 * <p>This mirrors {@link org.thoughtcrime.securesms.components.mention.MentionDeleter}, which does the
 * same for mention chips. It exists because backspace-over-a-{@code ReplacementSpan} is handled
 * atomically by the framework only on newer Android: on older releases a single backspace merely
 * shrinks the span (`:token:` → `:token`), and the shrunk span both fails to re-layout reliably and
 * keeps drawing the stale image until a later edit forces a refresh. Removing the entire span range in
 * one step makes the behaviour deterministic across Android versions.
 *
 * <p>On a version where the framework already removes the whole span in one keystroke, the deletion
 * starts at the span's own start ({@code spanStart == start}), so the guard below does not fire and
 * this watcher is a harmless no-op — the framework has already done the right thing.
 */
public class InlineMediaDeleter implements TextWatcher {

  @Nullable private InlineMediaSpan toDelete;

  @Override
  public void beforeTextChanged(CharSequence sequence, int start, int count, int after) {
    if (count > 0 && sequence instanceof Spanned) {
      Spanned text = (Spanned) sequence;

      for (InlineMediaSpan span : text.getSpans(start, start + count, InlineMediaSpan.class)) {
        // Only when the removal begins strictly *inside* the span (i.e. the user is backspacing into
        // it) do we take over and delete the whole thing. A removal that begins exactly at the span
        // start is the framework already deleting the entire chip, which we leave alone.
        if (text.getSpanStart(span) < start && text.getSpanEnd(span) > start) {
          toDelete = span;
          return;
        }
      }
    }
  }

  @Override
  public void afterTextChanged(Editable editable) {
    if (toDelete == null) {
      return;
    }

    int spanStart = editable.getSpanStart(toDelete);
    int spanEnd   = editable.getSpanEnd(toDelete);
    editable.removeSpan(toDelete);
    toDelete = null;

    if (spanStart >= 0 && spanEnd > spanStart) {
      editable.delete(spanStart, spanEnd);
    }
  }

  @Override
  public void onTextChanged(CharSequence sequence, int start, int before, int count) { }
}
