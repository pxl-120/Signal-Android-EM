package org.thoughtcrime.securesms.components.emoji;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.thoughtcrime.securesms.keyvalue.SettingsValues;
import org.thoughtcrime.securesms.keyvalue.SignalStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * "Import from URL" support for custom emoji packs.
 *
 * <p>Two URLs are configured by the user: a {@code .zip} pack and a version endpoint returning
 * {@code {"version": "…"}}. {@link #applyFromUrl} downloads + imports the pack and records the version;
 * {@link #checkForUpdate} (run on every app start) re-fetches the version and, if it differs from the
 * stored one, re-downloads + re-imports the pack. Versions are compared by plain string equality.
 *
 * <p>Like the rest of this subsystem, downloads use a raw {@link HttpURLConnection} (outside Signal's
 * networking stack) — the URLs are the user's own configured pack source.
 */
public final class CustomEmojiPackUpdater {

  private static final String TAG = "CustomEmojiPackUpdater";

  private static final int CONNECT_TIMEOUT_MS = 15000;
  private static final int READ_TIMEOUT_MS    = 30000;
  private static final int MAX_VERSION_BYTES  = 1 * 1024 * 1024;
  private static final int MAX_PACK_BYTES     = 64 * 1024 * 1024;

  private CustomEmojiPackUpdater() {}

  /**
   * Persists the URLs, downloads + imports the pack, and records the fetched version + timestamps.
   * Blocking — call from a background thread. Throws on any failure (nothing is persisted then).
   */
  public static void applyFromUrl(@NonNull Context context, @NonNull String zipUrl, @NonNull String versionUrl) throws Exception {
    String version = fetchVersion(versionUrl);
    importPackFromUrl(context, zipUrl);

    SettingsValues settings = SignalStore.settings();
    long           now      = System.currentTimeMillis();

    settings.setCustomEmojiPackZipUrl(zipUrl);
    settings.setCustomEmojiPackVersionUrl(versionUrl);
    settings.setCustomEmojiPackVersion(version != null ? version : "");
    settings.setCustomEmojiPackLastCheck(now);
    settings.setCustomEmojiPackLastUpdate(now);
  }

  /**
   * Best-effort app-start check: if the URL import method is active and the remote version differs from
   * the stored one, re-download + re-import the pack. Never throws.
   */
  public static void checkForUpdate(@NonNull Context context) {
    SettingsValues settings = SignalStore.settings();

    if (!settings.isCustomEmojiImportFromUrl()) {
      return;
    }

    String zipUrl     = settings.getCustomEmojiPackZipUrl();
    String versionUrl = settings.getCustomEmojiPackVersionUrl();
    if (zipUrl.isEmpty() || versionUrl.isEmpty()) {
      return;
    }

    try {
      String remoteVersion = fetchVersion(versionUrl);
      if (remoteVersion == null) {
        return;
      }

      settings.setCustomEmojiPackLastCheck(System.currentTimeMillis());

      if (!remoteVersion.equals(settings.getCustomEmojiPackVersion())) {
        Log.i(TAG, "Custom emoji pack version changed; updating.");
        importPackFromUrl(context, zipUrl);
        settings.setCustomEmojiPackVersion(remoteVersion);
        settings.setCustomEmojiPackLastUpdate(System.currentTimeMillis());
      }
    } catch (Throwable t) {
      Log.w(TAG, "Custom emoji pack update check failed", t);
    }
  }

  private static void importPackFromUrl(@NonNull Context context, @NonNull String zipUrl) throws Exception {
    File tmp = downloadToTempFile(context, zipUrl);
    try (InputStream in = new FileInputStream(tmp)) {
      CustomEmojiPackManager.importZip(context, in);
    } finally {
      //noinspection ResultOfMethodCallIgnored
      tmp.delete();
    }
  }

  private static @Nullable String fetchVersion(@NonNull String versionUrl) throws Exception {
    byte[] data = download(versionUrl, MAX_VERSION_BYTES);
    if (data == null) {
      return null;
    }

    Object parsed = new JSONTokener(new String(data, StandardCharsets.UTF_8)).nextValue();
    if (!(parsed instanceof JSONObject)) {
      Log.w(TAG, "Version endpoint did not return a JSON object");
      return null;
    }

    String version = ((JSONObject) parsed).optString("version", null);
    return version != null ? version.trim() : null;
  }

  private static @NonNull File downloadToTempFile(@NonNull Context context, @NonNull String urlString) throws Exception {
    HttpURLConnection connection = open(urlString);
    try {
      int code = connection.getResponseCode();
      if (code < 200 || code >= 300) {
        throw new IOException("HTTP " + code + " for " + urlString);
      }

      File tmp = File.createTempFile("custom_emoji_pack", ".zip", context.getCacheDir());
      try (InputStream in = connection.getInputStream();
           FileOutputStream out = new FileOutputStream(tmp)) {
        byte[] buffer = new byte[8192];
        long   total  = 0;
        int    read;
        while ((read = in.read(buffer)) != -1) {
          total += read;
          if (total > MAX_PACK_BYTES) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Pack exceeds size limit: " + urlString);
          }
          out.write(buffer, 0, read);
        }
      }
      return tmp;
    } finally {
      connection.disconnect();
    }
  }

  private static @Nullable byte[] download(@NonNull String urlString, int maxBytes) throws Exception {
    HttpURLConnection connection = open(urlString);
    try {
      int code = connection.getResponseCode();
      if (code < 200 || code >= 300) {
        Log.w(TAG, "HTTP " + code + " for " + urlString);
        return null;
      }

      try (InputStream in = connection.getInputStream();
           ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int    total  = 0;
        int    read;
        while ((read = in.read(buffer)) != -1) {
          total += read;
          if (total > maxBytes) {
            throw new IOException("Response exceeds size limit: " + urlString);
          }
          out.write(buffer, 0, read);
        }
        return out.toByteArray();
      }
    } finally {
      connection.disconnect();
    }
  }

  private static @NonNull HttpURLConnection open(@NonNull String urlString) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
    connection.setInstanceFollowRedirects(true);
    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
    connection.setReadTimeout(READ_TIMEOUT_MS);
    connection.setRequestProperty("User-Agent", "Mozilla/5.0");
    connection.connect();
    return connection;
  }
}
