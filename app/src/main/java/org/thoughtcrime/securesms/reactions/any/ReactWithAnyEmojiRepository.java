package org.thoughtcrime.securesms.reactions.any;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.stream.Collectors;

import org.signal.core.util.ThreadUtil;
import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.emoji.CustomEmojiPageModel;
import org.thoughtcrime.securesms.components.emoji.CustomEmojiRegistry;
import org.thoughtcrime.securesms.components.emoji.RecentEmojiPageModel;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.database.model.ReactionRecord;
import org.signal.emoji.EmojiCategory;
import org.signal.emoji.EmojiPageModel;
import org.signal.emoji.EmojiSource;
import org.thoughtcrime.securesms.reactions.ReactionDetails;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.MessageSender;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

final class ReactWithAnyEmojiRepository {

  private static final String TAG = Log.tag(ReactWithAnyEmojiRepository.class);

  private final Context                     context;
  private final RecentEmojiPageModel        recentEmojiPageModel;
  private final List<ReactWithAnyEmojiPage> emojiPages;

  ReactWithAnyEmojiRepository(@NonNull Context context, @NonNull String storageKey) {
    this.context              = context;
    this.recentEmojiPageModel = new RecentEmojiPageModel(context, storageKey);
    this.emojiPages           = new LinkedList<>();

    List<EmojiPageModel> standardPages = EmojiSource.getLatest().getDisplayPages().stream()
        .filter(p -> p.getIconAttr() != EmojiCategory.EMOTICONS.getIcon())
        .collect(Collectors.toList());

    if (!standardPages.isEmpty()) {
      EmojiPageModel first = standardPages.remove(0);
      emojiPages.add(new ReactWithAnyEmojiPage(
          Collections.singletonList(
              new ReactWithAnyEmojiPageBlock(EmojiCategory.getCategoryLabel(first.getIconAttr()), first)
          )
      ));
    }

    List<String> customTokens = CustomEmojiRegistry.getTokens(context);
    if (!customTokens.isEmpty()) {
      EmojiPageModel customPage = new CustomEmojiPageModel(customTokens);
      emojiPages.add(new ReactWithAnyEmojiPage(
          Collections.singletonList(
              new ReactWithAnyEmojiPageBlock(R.string.custom_emoji__category, customPage)
          )
      ));
    }

    emojiPages.addAll(
        standardPages.stream()
            .map(page -> new ReactWithAnyEmojiPage(
                Collections.singletonList(
                    new ReactWithAnyEmojiPageBlock(EmojiCategory.getCategoryLabel(page.getIconAttr()), page)
                )
            ))
            .collect(Collectors.toList())
    );
  }

  List<ReactWithAnyEmojiPage> getEmojiPageModels(@NonNull List<ReactionDetails> thisMessagesReactions) {
    List<ReactWithAnyEmojiPage> pages       = new LinkedList<>();
    List<String>                thisMessage = thisMessagesReactions.stream()
                                                                   .map(ReactionDetails::getDisplayEmoji)
                                                                   .distinct().collect(Collectors.toList());

    if (thisMessage.isEmpty()) {
      pages.add(new ReactWithAnyEmojiPage(Collections.singletonList(new ReactWithAnyEmojiPageBlock(R.string.ReactWithAnyEmojiBottomSheetDialogFragment__recently_used, recentEmojiPageModel))));
    } else {
      pages.add(new ReactWithAnyEmojiPage(Arrays.asList(new ReactWithAnyEmojiPageBlock(R.string.ReactWithAnyEmojiBottomSheetDialogFragment__this_message, new ThisMessageEmojiPageModel(thisMessage)),
                                                        new ReactWithAnyEmojiPageBlock(R.string.ReactWithAnyEmojiBottomSheetDialogFragment__recently_used, recentEmojiPageModel))));
    }

    pages.addAll(emojiPages);

    return pages;
  }

  void addEmojiToMessage(@NonNull String emoji, @NonNull MessageId messageId) {
    SignalExecutors.BOUNDED.execute(() -> {
      ReactionRecord  oldRecord = SignalDatabase.reactions().getReactions(messageId).stream()
                                                .filter(record -> record.getAuthor().equals(Recipient.self().getId()))
                                                .findFirst()
                                                .orElse(null);

      if (oldRecord != null && oldRecord.getEmoji().equals(emoji)) {
        MessageSender.sendReactionRemoval(context, messageId, oldRecord);
      } else {
        MessageSender.sendNewReaction(context, messageId, emoji);
        ThreadUtil.runOnMain(() -> recentEmojiPageModel.onCodePointSelected(emoji));
      }
    });
  }
}
