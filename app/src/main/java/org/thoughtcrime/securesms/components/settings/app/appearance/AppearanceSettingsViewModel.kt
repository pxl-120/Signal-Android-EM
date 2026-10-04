package org.thoughtcrime.securesms.components.settings.app.appearance

import android.app.Activity
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.signal.core.util.AppUtil
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.jobs.EmojiSearchIndexDownloadJob
import org.thoughtcrime.securesms.keyvalue.PlainTextKeyValueStore
import org.thoughtcrime.securesms.keyvalue.SettingsValues.Theme
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.SplashScreenUtil

class AppearanceSettingsViewModel : ViewModel() {
  private val store = MutableStateFlow(getState())
  val state: StateFlow<AppearanceSettingsState> = store

  fun refreshState() {
    store.update { getState() }
  }

  fun setTheme(activity: Activity?, theme: Theme) {
    store.update { it.copy(theme = theme) }
    SignalStore.settings.theme = theme
    SplashScreenUtil.setSplashScreenThemeIfNecessary(activity, theme)
  }

  fun setLanguage(language: String) {
    store.update { it.copy(language = language) }
    PlainTextKeyValueStore.language = language
    EmojiSearchIndexDownloadJob.scheduleImmediately()
    AppUtil.restart(AppDependencies.application)
  }

  fun setMessageFontSize(size: Int) {
    store.update { it.copy(messageFontSize = size) }
    SignalStore.settings.messageFontSize = size
  }

  fun setInlineUrlMediaEnabled(enabled: Boolean) {
    store.update { it.copy(inlineUrlMediaEnabled = enabled) }
    SignalStore.settings.isInlineUrlMediaEnabled = enabled
  }

  fun setCustomEmojiSearchFirst(enabled: Boolean) {
    store.update { it.copy(customEmojiSearchFirst = enabled) }
    SignalStore.settings.isCustomEmojiSearchFirst = enabled
  }

  fun setCustomEmojiNamesAsLiterals(enabled: Boolean) {
    store.update { it.copy(customEmojiNamesAsLiterals = enabled) }
    SignalStore.settings.isCustomEmojiNamesAsLiterals = enabled
  }

  fun setCustomEmojiImportFromUrl(enabled: Boolean) {
    store.update { it.copy(customEmojiImportFromUrl = enabled) }
    SignalStore.settings.isCustomEmojiImportFromUrl = enabled
  }

  private fun getState(): AppearanceSettingsState {
    return AppearanceSettingsState(
      SignalStore.settings.theme,
      SignalStore.settings.messageFontSize,
      PlainTextKeyValueStore.language,
      SignalStore.settings.useCompactNavigationBar,
      SignalStore.settings.isInlineUrlMediaEnabled,
      SignalStore.settings.isCustomEmojiSearchFirst,
      SignalStore.settings.isCustomEmojiNamesAsLiterals,
      SignalStore.settings.isCustomEmojiImportFromUrl,
      SignalStore.settings.customEmojiPackZipUrl,
      SignalStore.settings.customEmojiPackVersionUrl,
      SignalStore.settings.customEmojiPackVersion,
      SignalStore.settings.customEmojiPackLastCheck,
      SignalStore.settings.customEmojiPackLastUpdate
    )
  }
}
