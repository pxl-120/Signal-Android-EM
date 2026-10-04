package org.thoughtcrime.securesms.keyboard.emoji

import android.content.Context
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.emoji.EmojiPageModel
import org.signal.emoji.EmojiSource.Companion.latest
import org.thoughtcrime.securesms.components.emoji.CustomEmojiPageModel
import org.thoughtcrime.securesms.components.emoji.CustomEmojiRegistry
import org.thoughtcrime.securesms.components.emoji.RecentEmojiPageModel
import java.util.function.Consumer

class EmojiKeyboardPageRepository(private val context: Context) {
  fun getEmoji(consumer: Consumer<List<EmojiPageModel>>) {
    SignalExecutors.BOUNDED.execute {
      val list = mutableListOf<EmojiPageModel>()

      list += RecentEmojiPageModel(context, RecentEmojiPageModel.RECENT_STORAGE_KEY)

      val standardPages = latest.displayPages.toMutableList()
      val customTokens = CustomEmojiRegistry.getTokens(context)

      if (standardPages.isNotEmpty()) {
        list += standardPages.removeAt(0)
      }

      if (customTokens.isNotEmpty()) {
        list += CustomEmojiPageModel(customTokens)
      }

      list += standardPages

      consumer.accept(list)
    }
  }
}
