package org.thoughtcrime.securesms.keyboard.emoji

import org.signal.emoji.EmojiCategory
import org.signal.emoji.EmojiPageModel
import org.signal.emoji.EmojiSource
import org.signal.emoji.parsing.EmojiTree
import org.thoughtcrime.securesms.components.emoji.EmojiPageViewGridAdapter
import org.thoughtcrime.securesms.components.emoji.RecentEmojiPageModel
import org.thoughtcrime.securesms.util.adapter.mapping.MappingModel

import org.thoughtcrime.securesms.components.emoji.CustomEmojiPageModel
import org.thoughtcrime.securesms.components.emoji.CustomEmojiRegistry
import org.thoughtcrime.securesms.dependencies.AppDependencies

fun EmojiPageModel.toMappingModels(): List<MappingModel<*>> {
  val emojiTree: EmojiTree = EmojiSource.latest.emojiTree
  return displayEmoji.map {
    val isCustomEmoji =
      key == CustomEmojiPageModel.KEY ||
      CustomEmojiRegistry.isCustomToken(AppDependencies.application, it.value)

    val isTextEmoji =
      !isCustomEmoji &&
      (
        EmojiCategory.EMOTICONS.key == key ||
        (RecentEmojiPageModel.KEY == key && emojiTree.getEmoji(it.value, 0, it.value.length) == null)
      )

    if (isTextEmoji) {
      EmojiPageViewGridAdapter.EmojiTextModel(key, it)
    } else {
      EmojiPageViewGridAdapter.EmojiModel(key, it)
    }
  }
}
