package org.thoughtcrime.securesms.components.settings.app.appearance

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.signal.core.ui.compose.ComposeFragment
import org.signal.core.ui.compose.DayNightPreviews
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.Texts
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.components.settings.app.appearance.navbar.ChooseNavigationBarStyleFragment
import org.thoughtcrime.securesms.keyvalue.SettingsValues
import org.thoughtcrime.securesms.util.navigation.safeNavigate

import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import org.thoughtcrime.securesms.components.emoji.CustomEmojiPackManager
import org.thoughtcrime.securesms.components.emoji.CustomEmojiPackUpdater
import org.signal.core.ui.compose.TextFields
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * Allows the user to change language, theme, etc. from application settings.
 */
class AppearanceSettingsFragment : ComposeFragment() {

  private val viewModel: AppearanceSettingsViewModel by viewModels()

  private val importCustomEmojiPack =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri == null) return@registerForActivityResult

      Thread {
        try {
          CustomEmojiPackManager.importZip(requireContext(), uri)

          requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), "Custom emote pack imported", Toast.LENGTH_SHORT).show()
          }
        } catch (t: Throwable) {
          requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), "Import failed: ${t.message}", Toast.LENGTH_LONG).show()
          }
        }
      }.start()
    }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    childFragmentManager.setFragmentResultListener(ChooseNavigationBarStyleFragment.REQUEST_KEY, viewLifecycleOwner) { key, bundle ->
      if (bundle.getBoolean(key, false)) {
        viewModel.refreshState()
      }
    }
  }

  @Composable
  override fun FragmentContent() {
    val callbacks = remember { Callbacks() }
    val state by viewModel.state.collectAsStateWithLifecycle()

    AppearanceSettingsScreen(
      state = state,
      callbacks = callbacks
    )
  }

  private inner class Callbacks : AppearanceSettingsCallbacks {

    override fun onImportCustomEmojiPackClick() {
      importCustomEmojiPack.launch(arrayOf("application/zip", "application/octet-stream"))
    }

    override fun onInlineUrlMediaToggled(enabled: Boolean) {
      viewModel.setInlineUrlMediaEnabled(enabled)
    }

    override fun onCustomEmojiSearchFirstToggled(enabled: Boolean) {
      viewModel.setCustomEmojiSearchFirst(enabled)
    }

    override fun onCustomEmojiNamesAsLiteralsToggled(enabled: Boolean) {
      viewModel.setCustomEmojiNamesAsLiterals(enabled)
    }

    override fun onCustomEmojiImportMethodSelected(fromUrl: Boolean) {
      viewModel.setCustomEmojiImportFromUrl(fromUrl)
    }

    override fun onApplyUrlPack(zipUrl: String, versionUrl: String) {
      if (zipUrl.isBlank() || versionUrl.isBlank()) {
        Toast.makeText(requireContext(), "Enter both the pack URL and the version URL", Toast.LENGTH_SHORT).show()
        return
      }

      Toast.makeText(requireContext(), "Downloading custom emote pack…", Toast.LENGTH_SHORT).show()

      Thread {
        try {
          CustomEmojiPackUpdater.applyFromUrl(requireContext(), zipUrl, versionUrl)

          requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), "Custom emote pack imported", Toast.LENGTH_SHORT).show()
            viewModel.refreshState()
          }
        } catch (t: Throwable) {
          requireActivity().runOnUiThread {
            Toast.makeText(requireContext(), "Import failed: ${t.message}", Toast.LENGTH_LONG).show()
          }
        }
      }.start()
    }

    override fun onNavigationClick() {
      requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    override fun onLanguageSelected(selection: String) {
      MaterialAlertDialogBuilder(requireContext())
        .setMessage(R.string.preferences_language_change_confirmation_message)
        .setPositiveButton(android.R.string.ok) { _, _ -> viewModel.setLanguage(selection) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    }

    override fun onThemeSelected(selection: String) {
      viewModel.setTheme(activity, SettingsValues.Theme.deserialize(selection))
    }

    override fun onChatColorAndWallpaperClick() {
      findNavController().safeNavigate(R.id.action_appearanceSettings_to_wallpaperActivity)
    }

    override fun onAppIconClick() {
      findNavController().safeNavigate(R.id.action_appearanceSettings_to_appIconActivity)
    }

    override fun onMessageFontSizeSelected(selection: String) {
      viewModel.setMessageFontSize(selection.toInt())
    }

    override fun onNavigationBarSizeClick() {
      ChooseNavigationBarStyleFragment().show(childFragmentManager, null)
    }
  }
}

interface AppearanceSettingsCallbacks {
  fun onNavigationClick() = Unit
  fun onLanguageSelected(selection: String) = Unit
  fun onThemeSelected(selection: String) = Unit
  fun onChatColorAndWallpaperClick() = Unit
  fun onAppIconClick() = Unit
  fun onMessageFontSizeSelected(selection: String) = Unit
  fun onNavigationBarSizeClick() = Unit
  fun onImportCustomEmojiPackClick() = Unit
  fun onInlineUrlMediaToggled(enabled: Boolean) = Unit
  fun onCustomEmojiSearchFirstToggled(enabled: Boolean) = Unit
  fun onCustomEmojiNamesAsLiteralsToggled(enabled: Boolean) = Unit
  fun onCustomEmojiImportMethodSelected(fromUrl: Boolean) = Unit
  fun onApplyUrlPack(zipUrl: String, versionUrl: String) = Unit

  object Empty : AppearanceSettingsCallbacks
}

@Composable
private fun AppearanceSettingsScreen(
  state: AppearanceSettingsState,
  callbacks: AppearanceSettingsCallbacks
) {
  var zipUrl by remember(state.customEmojiPackZipUrl) { mutableStateOf(state.customEmojiPackZipUrl) }
  var versionUrl by remember(state.customEmojiPackVersionUrl) { mutableStateOf(state.customEmojiPackVersionUrl) }

  Scaffolds.Settings(
    title = stringResource(R.string.preferences__appearance),
    onNavigationClick = callbacks::onNavigationClick,
    navigationIcon = SignalIcons.ArrowStart.imageVector
  ) { paddingValues ->
    LazyColumn(
      modifier = Modifier
        .padding(paddingValues)
    ) {
      item {
        Rows.RadioListRow(
          text = stringResource(R.string.preferences__language),
          labels = stringArrayResource(R.array.language_entries),
          values = stringArrayResource(R.array.language_values),
          selectedValue = state.language,
          onSelected = callbacks::onLanguageSelected
        )
      }

      item {
        Rows.RadioListRow(
          text = stringResource(R.string.preferences__theme),
          labels = stringArrayResource(R.array.pref_theme_entries),
          values = stringArrayResource(R.array.pref_theme_values),
          selectedValue = state.theme.serialize(),
          onSelected = callbacks::onThemeSelected
        )
      }

      item {
        Rows.TextRow(
          text = stringResource(R.string.preferences__chat_color_and_wallpaper),
          onClick = callbacks::onChatColorAndWallpaperClick
        )
      }

      if (Build.VERSION.SDK_INT >= 26) {
        item {
          Rows.TextRow(
            text = stringResource(R.string.preferences__app_icon),
            onClick = callbacks::onAppIconClick
          )
        }
      }

      item {
        Rows.RadioListRow(
          text = stringResource(R.string.preferences_chats__message_text_size),
          labels = stringArrayResource(R.array.pref_message_font_size_entries),
          values = integerArrayResource(R.array.pref_message_font_size_values).map { it.toString() }.toTypedArray(),
          selectedValue = state.messageFontSize.toString(),
          onSelected = callbacks::onMessageFontSizeSelected
        )
      }

      item {
        val label = if (state.isCompactNavigationBar) {
          R.string.preferences_compact
        } else {
          R.string.preferences_normal
        }

        Rows.TextRow(
          text = stringResource(R.string.preferences_navigation_bar_size),
          label = stringResource(label),
          onClick = callbacks::onNavigationBarSizeClick
        )
      }

      item {
        Dividers.Default()
      }

      item {
        Texts.SectionHeader(text = stringResource(R.string.appearance_settings__signal_plus_section))
      }

      item {
        Rows.ToggleRow(
          checked = state.inlineUrlMediaEnabled,
          text = stringResource(R.string.appearance_settings__render_inline_url_media),
          label = stringResource(R.string.appearance_settings__render_inline_url_media_warning),
          onCheckChanged = callbacks::onInlineUrlMediaToggled
        )
      }

      item {
        Rows.ToggleRow(
          checked = state.customEmojiSearchFirst,
          text = stringResource(R.string.appearance_settings__show_custom_emoji_first),
          label = stringResource(R.string.appearance_settings__show_custom_emoji_first_label),
          onCheckChanged = callbacks::onCustomEmojiSearchFirstToggled
        )
      }

      item {
        Rows.ToggleRow(
          checked = state.customEmojiNamesAsLiterals,
          text = stringResource(R.string.appearance_settings__custom_emoji_names_as_literals),
          label = stringResource(R.string.appearance_settings__custom_emoji_names_as_literals_label),
          onCheckChanged = callbacks::onCustomEmojiNamesAsLiteralsToggled
        )
      }

      item {
        Rows.RadioListRow(
          text = stringResource(R.string.appearance_settings__custom_emote_pack_source),
          labels = arrayOf(
            stringResource(R.string.appearance_settings__import_from_local_file),
            stringResource(R.string.appearance_settings__import_from_url)
          ),
          values = arrayOf("local", "url"),
          selectedValue = if (state.customEmojiImportFromUrl) "url" else "local",
          onSelected = { callbacks.onCustomEmojiImportMethodSelected(it == "url") }
        )
      }

      if (state.customEmojiImportFromUrl) {
        item {
          TextFields.TextField(
            value = zipUrl,
            onValueChange = { zipUrl = it },
            label = { Text(stringResource(R.string.appearance_settings__pack_zip_url)) },
            singleLine = true,
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 24.dp, vertical = 4.dp)
          )
        }

        item {
          TextFields.TextField(
            value = versionUrl,
            onValueChange = { versionUrl = it },
            label = { Text(stringResource(R.string.appearance_settings__pack_version_url)) },
            singleLine = true,
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 24.dp, vertical = 4.dp)
          )
        }

        item {
          Rows.TextRow(
            text = stringResource(R.string.appearance_settings__download_and_apply),
            onClick = { callbacks.onApplyUrlPack(zipUrl.trim(), versionUrl.trim()) }
          )
        }

        item {
          Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.appearance_settings__current_version, state.customEmojiPackVersion.ifEmpty { "—" }))
            Text(stringResource(R.string.appearance_settings__last_version_check, formatTimestamp(state.customEmojiPackLastCheck)))
            Text(stringResource(R.string.appearance_settings__last_update, formatTimestamp(state.customEmojiPackLastUpdate)))
          }
        }
      } else {
        item {
          Rows.TextRow(
            text = stringResource(R.string.appearance_settings__import_custom_emote_pack),
            onClick = callbacks::onImportCustomEmojiPackClick
          )
        }
      }
    }
  }
}

@DayNightPreviews
@Composable
private fun AppearanceSettingsScreenPreview() {
  Previews.Preview {
    AppearanceSettingsScreen(
      state = AppearanceSettingsState(
        theme = SettingsValues.Theme.SYSTEM,
        messageFontSize = 0,
        language = "en-US",
        isCompactNavigationBar = false,
        inlineUrlMediaEnabled = false,
        customEmojiSearchFirst = false,
        customEmojiNamesAsLiterals = false,
        customEmojiImportFromUrl = false,
        customEmojiPackZipUrl = "",
        customEmojiPackVersionUrl = "",
        customEmojiPackVersion = "",
        customEmojiPackLastCheck = 0L,
        customEmojiPackLastUpdate = 0L
      ),
      callbacks = AppearanceSettingsCallbacks.Empty
    )
  }
}

private fun formatTimestamp(timestamp: Long): String {
  return if (timestamp <= 0L) {
    "—"
  } else {
    java.text.DateFormat
      .getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
      .format(java.util.Date(timestamp))
  }
}
