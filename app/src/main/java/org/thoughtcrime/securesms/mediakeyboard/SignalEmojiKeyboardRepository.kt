/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.mediakeyboard

import android.content.Context
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.emoji.EmojiSource
import org.signal.emoji.EmojiUtil
import org.signal.mediakeyboard.data.EmojiCategoryPage
import org.signal.mediakeyboard.data.EmojiKeyboardCategory
import org.signal.mediakeyboard.data.EmojiKeyboardRepository
import org.signal.mediakeyboard.data.KeyboardEmoji
import org.thoughtcrime.securesms.components.emoji.CustomEmojiImageBinder
import org.thoughtcrime.securesms.components.emoji.CustomEmojiRegistry
import org.thoughtcrime.securesms.components.emoji.RecentEmojiPageModel
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore

/**
 * [EmojiKeyboardRepository] backed by the app's emoji data: [EmojiSource], the recent emoji store,
 * and the emoji search index.
 */
class SignalEmojiKeyboardRepository(
  private val context: Context,
  private val recentEmoji: RecentEmojiPageModel
) : EmojiKeyboardRepository {

  companion object {
    private const val EMOJI_SEARCH_LIMIT = 50
    private const val CUSTOM_EMOJI_SIZE_DP = 48
  }

  override suspend fun getEmojiPages(): List<EmojiCategoryPage> {
    return withContext(Dispatchers.Default) {
      val pages = EmojiSource.latest.displayPages.mapNotNull { page ->
        val category = EmojiKeyboardCategory.entries.firstOrNull { it.key == page.key } ?: return@mapNotNull null
        EmojiCategoryPage(
          category = category,
          emoji = page.displayEmoji.map { emoji -> KeyboardEmoji(canonical = emoji.value, variations = emoji.variations) }
        )
      }.toMutableList()

      // Signal+: the imported custom emoji pack gets its own category, right after the first standard page.
      val customTokens = CustomEmojiRegistry.getTokens(context)
      if (customTokens.isNotEmpty()) {
        val customPage = EmojiCategoryPage(EmojiKeyboardCategory.CUSTOM, customTokens.map { KeyboardEmoji(canonical = it) })
        pages.add(minOf(1, pages.size), customPage)
      }

      pages
    }
  }

  /** Stays on the caller's thread, since [RecentEmojiPageModel] is main-thread-only and shared with the host. */
  override suspend fun getRecentEmoji(): List<KeyboardEmoji> {
    return recentEmoji.emoji.map { KeyboardEmoji(canonical = it) }
  }

  /**
   * Deliberately gated only on there being something to search for. The two-character minimum and
   * the trailing-punctuation rule belong to inline `:word:` replacement, which cannot afford to
   * guess at every keystroke; an explicit search field can. Blank queries never arrive here, since
   * those show recents instead.
   */
  override suspend fun search(query: String): List<KeyboardEmoji> {
    if (query.isBlank()) {
      return emptyList()
    }

    return withContext(Dispatchers.Default) {
      val variationsByCanonical = EmojiSource.latest.canonicalToVariations
      val standard = SignalDatabase.emojiSearch.query(query, EMOJI_SEARCH_LIMIT).map { emoji ->
        KeyboardEmoji(canonical = emoji, variations = variationsByCanonical[emoji] ?: emptyList())
      }

      // Signal+: custom emoji matched by token, alias or literal, ordered per the "custom first" setting.
      val custom = CustomEmojiRegistry.searchTokens(context, query).map { KeyboardEmoji(canonical = it) }
      val ordered = if (SignalStore.settings.isCustomEmojiSearchFirst) custom + standard else standard + custom
      ordered.distinctBy { it.canonical }.take(EMOJI_SEARCH_LIMIT)
    }
  }

  override suspend fun getPreferredVariations(): Map<String, String> {
    return withContext(Dispatchers.Default) {
      val emojiValues = SignalStore.emoji
      EmojiSource.latest.canonicalToVariations.keys
        .mapNotNull { canonical ->
          val preferred = emojiValues.getPreferredVariation(canonical)
          if (preferred != canonical) canonical to preferred else null
        }
        .toMap()
    }
  }

  override fun setPreferredVariation(canonical: String, variation: String) {
    SignalStore.emoji.setPreferredVariation(variation)
  }

  override suspend fun onEmojiUsed(emoji: String) {
    recentEmoji.onCodePointSelected(emoji)
  }

  override fun getEmojiDrawable(emoji: String): Drawable? {
    if (CustomEmojiRegistry.isCustomToken(context, emoji)) {
      val sizePx = (CUSTOM_EMOJI_SIZE_DP * context.resources.displayMetrics.density).toInt()
      return CustomEmojiImageBinder.createDrawable(context, emoji, sizePx)
    }

    return if (SignalStore.settings.isPreferSystemEmoji) {
      null
    } else {
      EmojiUtil.convertToDrawable(context, emoji)
    }
  }
}
