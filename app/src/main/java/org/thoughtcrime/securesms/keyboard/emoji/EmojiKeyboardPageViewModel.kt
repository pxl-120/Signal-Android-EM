package org.thoughtcrime.securesms.keyboard.emoji

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.signal.emoji.EmojiCategory
import org.signal.emoji.EmojiPageModel
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.emoji.EmojiPageViewGridAdapter.EmojiHeader
import org.thoughtcrime.securesms.components.emoji.RecentEmojiPageModel
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.util.DefaultValueLiveData
import org.thoughtcrime.securesms.util.adapter.mapping.MappingModelList
import org.thoughtcrime.securesms.util.livedata.LiveDataUtil

import org.thoughtcrime.securesms.components.emoji.CustomEmojiPageModel

class EmojiKeyboardPageViewModel(private val repository: EmojiKeyboardPageRepository) : ViewModel() {

  private val internalSelectedKey = DefaultValueLiveData<String>(getStartingTab())

  val selectedKey: LiveData<String>
    get() = internalSelectedKey

  val allEmojiModels: MutableLiveData<List<EmojiPageModel>> = MutableLiveData()
  val pages: LiveData<MappingModelList>
  val categories: LiveData<MappingModelList>

  init {
    pages = LiveDataUtil.mapAsync(allEmojiModels) { models ->
      val list = MappingModelList()

      models.forEach { pageModel ->
        when {
          RecentEmojiPageModel.KEY == pageModel.key -> {
            if (pageModel.displayEmoji.isNotEmpty()) {
              list += EmojiHeader(
                pageModel.key,
                R.string.ReactWithAnyEmojiBottomSheetDialogFragment__recently_used
              )
              list += pageModel.toMappingModels()
            }
          }

          CustomEmojiPageModel.KEY == pageModel.key -> {
            list += EmojiHeader(
              pageModel.key,
              R.string.custom_emoji__category
            )
            list += pageModel.toMappingModels()
          }

          else -> {
            val category = EmojiCategory.forKey(pageModel.key)
            list += EmojiHeader(pageModel.key, category.getCategoryLabel())
            list += pageModel.toMappingModels()
          }
        }
      }

      list
    }

    categories = LiveDataUtil.combineLatest(allEmojiModels, internalSelectedKey) { models, selectedKey ->
      val list = MappingModelList()

      models.forEach { m ->
        val model = when {
          RecentEmojiPageModel.KEY == m.key -> {
            RecentsMappingModel(m.key == selectedKey)
          }

          CustomEmojiPageModel.KEY == m.key -> {
            CustomMappingModel(m.key == selectedKey)
          }

          else -> {
            val category = EmojiCategory.forKey(m.key)
            EmojiCategoryMappingModel(category, category.key == selectedKey)
          }
        }

        list += model
      }

      list
    }
  }

  fun onKeySelected(key: String) {
    internalSelectedKey.value = key
  }

  fun refreshRecentEmoji() {
    repository.getEmoji(allEmojiModels::postValue)
  }

  companion object {
    fun getStartingTab(): String {
      return if (RecentEmojiPageModel.hasRecents(AppDependencies.application, RecentEmojiPageModel.RECENT_STORAGE_KEY)) {
        RecentEmojiPageModel.KEY
      } else {
        EmojiCategory.PEOPLE.key
      }
    }
  }

  class Factory(context: Context) : ViewModelProvider.Factory {

    private val repository = EmojiKeyboardPageRepository(context)

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return requireNotNull(modelClass.cast(EmojiKeyboardPageViewModel(repository)))
    }
  }
}
