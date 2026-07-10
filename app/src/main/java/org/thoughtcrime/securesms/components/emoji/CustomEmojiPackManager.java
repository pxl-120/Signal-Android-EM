package org.thoughtcrime.securesms.components.emoji;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import androidx.annotation.Nullable;

public final class CustomEmojiPackManager {

  private static final String ROOT_DIR = "custom_emoji";
  private static final String CURRENT_DIR = "current";
  private static final String TMP_DIR = "import_tmp";
  private static final String CONFIG_NAME = "emoji.json";

  private CustomEmojiPackManager() {}

  public static void importZip(@NonNull Context context, @NonNull Uri uri) throws Exception {
    try (InputStream in = context.getContentResolver().openInputStream(uri)) {
      if (in == null) {
        throw new IllegalArgumentException("Could not open ZIP");
      }
      importZip(context, in);
    }
  }

  public static void importZip(@NonNull Context context, @NonNull InputStream zipStream) throws Exception {
    Context appContext = context.getApplicationContext();

    File rootDir = new File(appContext.getFilesDir(), ROOT_DIR);
    File tmpDir = new File(rootDir, TMP_DIR);
    File currentDir = new File(rootDir, CURRENT_DIR);

    deleteRecursively(tmpDir);
    if (!tmpDir.mkdirs() && !tmpDir.isDirectory()) {
      throw new IllegalStateException("Could not create temp import dir");
    }

    try {
      extractZip(zipStream, tmpDir);

      File configFile = new File(tmpDir, CONFIG_NAME);
      if (!configFile.isFile()) {
        throw new IllegalArgumentException("ZIP must contain " + CONFIG_NAME + " at root");
      }

      validatePack(configFile);

      File backupDir = new File(rootDir, "previous");
      deleteRecursively(backupDir);

      if (currentDir.exists() && !currentDir.renameTo(backupDir)) {
        throw new IllegalStateException("Could not rotate current pack");
      }

      if (!tmpDir.renameTo(currentDir)) {
        throw new IllegalStateException("Could not activate imported pack");
      }

      deleteRecursively(backupDir);

      CustomEmojiRegistry.reload();
      InlineMediaProvider.clearCache();

    } catch (Throwable t) {
      deleteRecursively(tmpDir);
      throw t;
    }
  }

  private static void extractZip(@NonNull InputStream raw, @NonNull File outDir) throws Exception {
    try (ZipInputStream zis = new ZipInputStream(raw)) {
      ZipEntry entry;

      while ((entry = zis.getNextEntry()) != null) {
        String name = entry.getName();

        if (name == null || name.isEmpty()) {
          zis.closeEntry();
          continue;
        }

        if (name.startsWith("/") || name.contains("..")) {
          throw new IllegalArgumentException("Invalid ZIP entry: " + name);
        }

        File target = new File(outDir, name);
        String outCanonical = outDir.getCanonicalPath() + File.separator;
        String targetCanonical = target.getCanonicalPath();

        if (!targetCanonical.startsWith(outCanonical) && !targetCanonical.equals(outDir.getCanonicalPath())) {
          throw new IllegalArgumentException("ZIP path traversal blocked: " + name);
        }

        if (entry.isDirectory()) {
          if (!target.mkdirs() && !target.isDirectory()) {
            throw new IllegalStateException("Could not create directory: " + name);
          }
        } else {
          File parent = target.getParentFile();
          if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Could not create parent dir for: " + name);
          }

          try (FileOutputStream fos = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = zis.read(buffer)) != -1) {
              fos.write(buffer, 0, read);
            }
          }
        }

        zis.closeEntry();
      }
    }
  }

  private static void validatePack(@NonNull File configFile) throws Exception {
    String json = readAllText(new FileInputStream(configFile));
    Object parsed = new JSONTokener(json).nextValue();

    JSONArray emoji;
    if (parsed instanceof JSONObject) {
      emoji = ((JSONObject) parsed).optJSONArray("emoji");
    } else if (parsed instanceof JSONArray) {
      emoji = (JSONArray) parsed;
    } else {
      emoji = null;
    }

    if (emoji == null) {
      throw new IllegalArgumentException("No emoji array in " + CONFIG_NAME);
    }

    // Tokens and aliases share one global namespace; literals are checked after the loop (a literal may
    // collide with a token/alias defined on a later emoji, and may match a token/alias only of its own emoji).
    Set<String>         tokenAliasNames = new HashSet<>();   // token + alias names
    Map<String, String> nameToOwner     = new HashMap<>();   // token/alias name -> owning token
    List<String[]>      literalEntries  = new ArrayList<>(); // [literal, owning token], checked after the loop
    File baseDir = configFile.getParentFile();

    for (int i = 0; i < emoji.length(); i++) {
      JSONObject obj = emoji.optJSONObject(i);
      if (obj == null) {
        continue;
      }

      String token = CustomEmojiRegistry.normalizeName(obj.optString("token", null));
      String url = trimToNull(obj.optString("url", null));
      String file = trimToNull(obj.optString("file", null));
      String source = trimToNull(obj.optString("source", null));

      if (token == null) {
        throw new IllegalArgumentException("Entry " + i + " has no token");
      }

      if (!tokenAliasNames.add(token)) {
        throw new IllegalArgumentException("Duplicate name: " + token);
      }
      nameToOwner.put(token, token);

      String effectiveFile = file;
      if (effectiveFile == null && source != null &&
          !source.startsWith("http://") &&
          !source.startsWith("https://") &&
          !source.startsWith("file://")) {
        effectiveFile = source;
      }

      if (url == null && effectiveFile == null) {
        throw new IllegalArgumentException("Entry " + token + " must have url or file");
      }

      if (effectiveFile != null) {
        File media = CustomEmojiRegistry.resolveMediaFile(baseDir, effectiveFile);
        if (!media.isFile()) {
          throw new IllegalArgumentException("Missing media file for " + token + ": " + effectiveFile);
        }
      }

      JSONArray aliases = obj.optJSONArray("aliases");
      if (aliases != null) {
        for (int j = 0; j < aliases.length(); j++) {
          String alias = CustomEmojiRegistry.normalizeName(aliases.optString(j, null));
          if (alias == null) {
            continue;
          }
          if (!tokenAliasNames.add(alias)) {
            throw new IllegalArgumentException("Duplicate name (alias): " + alias);
          }
          nameToOwner.put(alias, token);
        }
      }

      JSONArray literals = obj.optJSONArray("literals");
      if (literals != null) {
        for (int j = 0; j < literals.length(); j++) {
          String literal = CustomEmojiRegistry.normalizeName(literals.optString(j, null));
          if (literal == null) {
            continue;
          }
          literalEntries.add(new String[] { literal, token });
        }
      }
    }

    // A literal must be unique among all literals, and may equal a token/alias only of its own emoji
    // (never one owned by a different emoji).
    Set<String> seenLiterals = new HashSet<>();
    for (String[] entry : literalEntries) {
      String literal = entry[0];
      String owner   = entry[1];
      if (!seenLiterals.add(literal)) {
        throw new IllegalArgumentException("Duplicate literal: " + literal);
      }
      String nameOwner = nameToOwner.get(literal);
      if (nameOwner != null && !nameOwner.equals(owner)) {
        throw new IllegalArgumentException("Literal also used as another emoji's token/alias: " + literal);
      }
    }
  }

  private static @NonNull String readAllText(@NonNull InputStream in) throws Exception {
    try (InputStream input = in;
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int read;
      while ((read = input.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }

  private static @Nullable String trimToNull(@Nullable String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static void deleteRecursively(@NonNull File file) {
    if (!file.exists()) {
      return;
    }

    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) {
        for (File child : children) {
          deleteRecursively(child);
        }
      }
    }

    //noinspection ResultOfMethodCallIgnored
    file.delete();
  }
}
