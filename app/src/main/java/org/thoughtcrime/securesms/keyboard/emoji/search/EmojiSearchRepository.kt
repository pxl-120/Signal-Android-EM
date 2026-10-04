package org.thoughtcrime.securesms.keyboard.emoji.search

import android.content.Context
import android.net.Uri
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.emoji.Emoji
import org.signal.emoji.EmojiPageModel
import org.signal.emoji.EmojiSource
import org.thoughtcrime.securesms.components.emoji.CustomEmojiRegistry
import org.thoughtcrime.securesms.components.emoji.RecentEmojiPageModel
import org.thoughtcrime.securesms.database.EmojiSearchTable
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.keyvalue.SignalStore
import java.util.function.Consumer

private const val MINIMUM_QUERY_THRESHOLD = 1
private const val MINIMUM_INLINE_QUERY_THRESHOLD = 2
private const val EMOJI_SEARCH_LIMIT = 50

private val NOT_PUNCTUATION = "[^\\p{Punct}]".toRegex()

class EmojiSearchRepository(private val context: Context) {

  private val emojiSearchTable: EmojiSearchTable = SignalDatabase.emojiSearch

  fun submitQuery(query: String, limit: Int = EMOJI_SEARCH_LIMIT): Single<List<String>> {
    val result =
      if (query.length >= MINIMUM_INLINE_QUERY_THRESHOLD &&
        NOT_PUNCTUATION.matches(query.substring(query.lastIndex))
      ) {
        Single.fromCallable {
          val standard = emojiSearchTable.query(query, limit)
          mergeCustomTokens(query, standard, limit)
        }
      } else {
        Single.just(emptyList())
      }

    return result.subscribeOn(Schedulers.io())
  }

  fun submitQuery(
    query: String,
    includeRecents: Boolean,
    limit: Int = EMOJI_SEARCH_LIMIT,
    consumer: Consumer<EmojiPageModel>
  ) {
    if (query.length < MINIMUM_QUERY_THRESHOLD && includeRecents) {
      consumer.accept(RecentEmojiPageModel(context, RecentEmojiPageModel.RECENT_STORAGE_KEY))
    } else {
      SignalExecutors.SERIAL.execute {
        val standard: List<String> = emojiSearchTable.query(query, limit)
        val merged: List<String> = mergeCustomTokens(query, standard, limit)

        val displayEmoji: List<Emoji> = merged.mapNotNull { value ->
          EmojiSource.latest.canonicalToVariations[value]?.let { Emoji(it) }
            ?: if (CustomEmojiRegistry.isCustomToken(context.applicationContext, value)) Emoji(value) else null
        }

        consumer.accept(EmojiSearchResultsPageModel(merged, displayEmoji))
      }
    }
  }

  private fun mergeCustomTokens(query: String, standard: List<String>, limit: Int): List<String> {
    val custom = CustomEmojiRegistry.searchTokens(context.applicationContext, query)
    if (custom.isEmpty()) {
      return standard.take(limit)
    }

    val ordered = if (SignalStore.settings.isCustomEmojiSearchFirst) {
      custom + standard
    } else {
      standard + custom
    }

    return ordered
      .distinct()
      .take(limit)
  }

  private class EmojiSearchResultsPageModel(
    private val emoji: List<String>,
    private val displayEmoji: List<Emoji>
  ) : EmojiPageModel {
    override fun getKey(): String = ""

    override fun getIconAttr(): Int = -1

    override fun getEmoji(): List<String> = emoji

    override fun getDisplayEmoji(): List<Emoji> = displayEmoji

    override fun getSpriteUri(): Uri? = null

    override fun isDynamic(): Boolean = false
  }
}
