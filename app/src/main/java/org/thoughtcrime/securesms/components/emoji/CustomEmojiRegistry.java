package org.thoughtcrime.securesms.components.emoji;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Holds the custom-emoji name maps, loaded from the imported pack at
 * {@code filesDir/custom_emoji/current/custom_emoji.json} (see {@link CustomEmojiPackManager}). There
 * is no bundled/default pack: until the user imports one, the registry is empty.
 *
 * <p>Each emoji has:
 * <ul>
 *   <li>a <b>token</b> — its sole identity. The token is a bare name with <b>no</b> colons (e.g.
 *       {@code pepega}, or {@code D_}); the colon-wrapped {@code :pepega:} form is what is inserted,
 *       sent, and matched in message text. Static: the token must never change or old messages stop
 *       rendering.</li>
 *   <li>a <b>source</b> (URL or local pack file).</li>
 *   <li>zero or more optional <b>aliases</b> — alternative bare names (which <i>may</i> themselves
 *       contain a colon, e.g. {@code D:}) used <i>only</i> for search and type-in. An alias never
 *       renders and is never sent; the picker / autocomplete / type-in swap converts it to the token
 *       first.</li>
 * </ul>
 *
 * <p>Names are stored <b>bare</b> (without the wrapping colons). Every method that talks to the rest of
 * the app speaks the colon-wrapped {@code :name:} form — the form that lives in message text — so the
 * wrapping is applied/stripped at this boundary only ({@link #wrap} / {@link #unwrap}). Every token and
 * alias is globally unique across the whole pack, so a name resolves to exactly one emoji. Rendering
 * ({@link CustomEmojiParser}, {@link #isCustomToken}, {@link #getSource}) operates on tokens only;
 * aliases live in a separate map.
 */
public final class CustomEmojiRegistry {

  private static final String TAG                  = "CustomEmojiRegistry";
  private static final String CONFIG_RELATIVE_PATH = "custom_emoji/current/custom_emoji.json";

  private static final Object LOCK = new Object();

  private static volatile boolean loaded = false;

  // Internal maps key on BARE names (no wrapping colons), e.g. token "D_", alias "D:".
  private static Map<String, String> tokenToSource = Collections.emptyMap();  // bare token -> source
  private static Map<String, String> aliasToToken  = Collections.emptyMap();  // bare alias -> bare token

  // Exposed lists, colon-wrapped (":name:"), longest first.
  private static List<String> wrappedTokens  = Collections.emptyList();
  private static List<String> wrappedAliases = Collections.emptyList();

  private CustomEmojiRegistry() {}

  /** Wraps a bare name in its colon form: {@code pepega} -> {@code :pepega:}. */
  private static @NonNull String wrap(@NonNull String bareName) {
    return ":" + bareName + ":";
  }

  /**
   * Strips the wrapping colons from a {@code :name:} form, returning the bare name. Removes exactly one
   * leading and one trailing colon, so a name that itself ends with a colon (e.g. the alias {@code D:},
   * wrapped as {@code :D::}) round-trips back to {@code D:}. A string that isn't colon-wrapped is
   * returned unchanged.
   */
  private static @NonNull String unwrap(@NonNull String wrapped) {
    if (wrapped.length() >= 2 && wrapped.startsWith(":") && wrapped.endsWith(":")) {
      return wrapped.substring(1, wrapped.length() - 1);
    }
    return wrapped;
  }

  public static boolean isCustomToken(@NonNull Context context, @Nullable CharSequence token) {
    ensureLoaded(context);
    return token != null && tokenToSource.containsKey(unwrap(token.toString()));
  }

  public static @Nullable String getSource(@NonNull Context context, @Nullable CharSequence token) {
    ensureLoaded(context);
    return token == null ? null : tokenToSource.get(unwrap(token.toString()));
  }

  /** All registered tokens in {@code :name:} form, longest first (so longer tokens win when matching in text). */
  public static @NonNull List<String> getTokens(@NonNull Context context) {
    ensureLoaded(context);
    return wrappedTokens;
  }

  /** All registered aliases in {@code :alias:} form, longest first. */
  public static @NonNull List<String> getAliases(@NonNull Context context) {
    ensureLoaded(context);
    return wrappedAliases;
  }

  /** The canonical token ({@code :token:} form) for an alias ({@code :alias:} form), or null if it isn't a known alias. */
  public static @Nullable String getAliasToken(@NonNull Context context, @Nullable CharSequence alias) {
    ensureLoaded(context);
    if (alias == null) {
      return null;
    }
    String token = aliasToToken.get(unwrap(alias.toString()));
    return token == null ? null : wrap(token);
  }

  /**
   * Tokens matching a search query — matched by token <i>or</i> alias name — ordered alphabetically.
   * Always returns canonical tokens in {@code :token:} form, so searching by an alias still inserts the
   * token. Shared by the {@code :}-autocomplete popup and the emoji-picker search.
   */
  public static @NonNull List<String> searchTokens(@NonNull Context context, @NonNull String rawQuery) {
    ensureLoaded(context);

    String query = normalizeForSearch(rawQuery);
    if (query.isEmpty()) {
      return Collections.emptyList();
    }

    Set<String> matchedBare = new LinkedHashSet<>();

    for (String token : tokenToSource.keySet()) {
      if (normalizeForSearch(token).contains(query)) {
        matchedBare.add(token);
      }
    }
    for (Map.Entry<String, String> entry : aliasToToken.entrySet()) {
      if (normalizeForSearch(entry.getKey()).contains(query)) {
        matchedBare.add(entry.getValue());
      }
    }

    List<String> result = new ArrayList<>(matchedBare.size());
    for (String bare : matchedBare) {
      result.add(wrap(bare));
    }
    Collections.sort(result);
    return result;
  }

  public static void reload() {
    synchronized (LOCK) {
      loaded         = false;
      tokenToSource  = Collections.emptyMap();
      aliasToToken   = Collections.emptyMap();
      wrappedTokens  = Collections.emptyList();
      wrappedAliases = Collections.emptyList();
    }
  }

  /**
   * Bare, normalized form of a token or alias name as written in the pack JSON. Names are taken
   * literally — only surrounding whitespace is trimmed; <b>no</b> colons are added or removed, so a name
   * may itself contain colons (e.g. the alias {@code D:}). The colon-wrapped {@code :name:} form is
   * produced later, at the registry boundary ({@link #wrap}).
   */
  static @Nullable String normalizeName(@Nullable String raw) {
    if (raw == null) {
      return null;
    }
    String value = raw.trim();
    return value.isEmpty() ? null : value;
  }

  private static void ensureLoaded(@NonNull Context context) {
    if (loaded) {
      return;
    }

    synchronized (LOCK) {
      if (loaded) {
        return;
      }

      Map<String, String> sourceMap = new HashMap<>();
      Map<String, String> aliasMap  = new LinkedHashMap<>();
      loadFromJson(context.getApplicationContext(), sourceMap, aliasMap);

      List<String> sortedTokens = new ArrayList<>(sourceMap.size());
      for (String bare : sourceMap.keySet()) {
        sortedTokens.add(wrap(bare));
      }
      sortedTokens.sort(Comparator.comparingInt(String::length).reversed());

      List<String> sortedAliases = new ArrayList<>(aliasMap.size());
      for (String bare : aliasMap.keySet()) {
        sortedAliases.add(wrap(bare));
      }
      sortedAliases.sort(Comparator.comparingInt(String::length).reversed());

      tokenToSource  = Collections.unmodifiableMap(sourceMap);
      aliasToToken   = Collections.unmodifiableMap(aliasMap);
      wrappedTokens  = Collections.unmodifiableList(sortedTokens);
      wrappedAliases = Collections.unmodifiableList(sortedAliases);
      loaded         = true;
    }
  }

  private static void loadFromJson(@NonNull Context context, @NonNull Map<String, String> sourceMap, @NonNull Map<String, String> aliasMap) {
    File configFile = new File(context.getFilesDir(), CONFIG_RELATIVE_PATH);
    if (!configFile.isFile()) {
      return;
    }

    try (InputStream in = new FileInputStream(configFile)) {
      parseJson(in, sourceMap, aliasMap, configFile.getParentFile());
      Log.i(TAG, "Loaded " + sourceMap.size() + " custom emotes (" + aliasMap.size() + " aliases) from " + configFile.getAbsolutePath());
    } catch (Throwable t) {
      Log.w(TAG, "Could not load custom emotes from " + configFile.getAbsolutePath(), t);
    }
  }

  private static void parseJson(@NonNull InputStream in, @NonNull Map<String, String> sourceMap, @NonNull Map<String, String> aliasMap, @Nullable File baseDir) throws Exception {
    String json   = readAllText(in);
    Object parsed = new JSONTokener(json).nextValue();

    JSONArray emotes;
    if (parsed instanceof JSONObject) {
      emotes = ((JSONObject) parsed).optJSONArray("emotes");
    } else if (parsed instanceof JSONArray) {
      emotes = (JSONArray) parsed;
    } else {
      emotes = null;
    }

    if (emotes == null) {
      Log.w(TAG, "No emotes array found");
      return;
    }

    // Enforces global uniqueness of every token and alias (bare names) across the whole pack.
    Set<String> usedNames = new HashSet<>();

    for (int i = 0; i < emotes.length(); i++) {
      JSONObject obj = emotes.optJSONObject(i);
      if (obj == null) {
        continue;
      }

      String token = normalizeName(obj.optString("token", null));
      if (token == null) {
        continue;
      }

      String source = firstNonBlank(
          trimToNull(obj.optString("source", null)),
          trimToNull(obj.optString("url", null)),
          resolveFilePath(trimToNull(obj.optString("file", null)), baseDir)
      );

      if (source == null) {
        Log.w(TAG, "Skipping token with no source: " + token);
        continue;
      }

      if (!usedNames.add(token)) {
        Log.w(TAG, "Skipping duplicate name (token): " + token);
        continue;
      }

      sourceMap.put(token, source);

      JSONArray aliasArray = obj.optJSONArray("aliases");
      if (aliasArray != null) {
        for (int j = 0; j < aliasArray.length(); j++) {
          String alias = normalizeName(aliasArray.optString(j, null));
          if (alias == null) {
            continue;
          }
          if (!usedNames.add(alias)) {
            Log.w(TAG, "Skipping duplicate name (alias): " + alias);
            continue;
          }
          aliasMap.put(alias, token);
        }
      }
    }
  }

  /** Resolves a pack-relative {@code file} entry to an absolute {@code file://} URI. */
  private static @Nullable String resolveFilePath(@Nullable String value, @Nullable File baseDir) {
    if (value == null) {
      return null;
    }

    if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("file://")) {
      return value;
    }

    if (value.startsWith("/")) {
      return "file://" + value;
    }

    if (baseDir != null) {
      return "file://" + new File(baseDir, value).getAbsolutePath();
    }

    return null;
  }

  private static @NonNull String normalizeForSearch(@NonNull String value) {
    String out = value.toLowerCase(Locale.ROOT).trim();

    if (out.startsWith(":")) {
      out = out.substring(1);
    }
    if (out.endsWith(":")) {
      out = out.substring(0, out.length() - 1);
    }

    return out.replace('_', ' ').replace('-', ' ');
  }

  private static @NonNull String readAllText(@NonNull InputStream in) throws Exception {
    try (InputStream input = in;
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int    read;
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

  private static @Nullable String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isEmpty()) {
        return value;
      }
    }
    return null;
  }
}
