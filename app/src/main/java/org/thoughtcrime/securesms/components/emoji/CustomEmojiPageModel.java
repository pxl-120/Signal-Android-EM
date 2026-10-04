package org.thoughtcrime.securesms.components.emoji;

import android.net.Uri;

import androidx.annotation.Nullable;

import org.signal.emoji.Emoji;
import org.signal.emoji.EmojiPageModel;
import org.signal.emoji.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CustomEmojiPageModel implements EmojiPageModel {
  public static final String KEY = "Custom";

  private final List<Emoji> emoji;

  public CustomEmojiPageModel(List<String> tokens) {
    List<Emoji> out = new ArrayList<>();
    for (String token : tokens) {
      out.add(new Emoji(token));
    }
    this.emoji = Collections.unmodifiableList(out);
  }

  @Override
  public String getKey() {
    return KEY;
  }

  @Override
  public int getIconAttr() {
    return R.attr.emoji_category_objects;
  }

  @Override
  public List<String> getEmoji() {
    List<String> out = new ArrayList<>();
    for (Emoji e : emoji) {
      out.addAll(e.getVariations());
    }
    return out;
  }

  @Override
  public List<Emoji> getDisplayEmoji() {
    return emoji;
  }

  @Override
  public @Nullable Uri getSpriteUri() {
    return null;
  }

  @Override
  public boolean isDynamic() {
    return false;
  }
}
