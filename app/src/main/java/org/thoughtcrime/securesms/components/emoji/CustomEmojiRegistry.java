package org.thoughtcrime.securesms.components.emoji;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import org.thoughtcrime.securesms.keyvalue.SignalStore;

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
 * {@code filesDir/custom_emoji/current/emoji.json} (see {@link CustomEmojiPackManager}). Bundled media
 * files live in the pack's {@code media/} subdirectory. There is no bundled/default pack: until the
 * user imports one, the registry is empty.
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
 *   <li>zero or more optional <b>literals</b> — like aliases, alternative names used <i>only</i> for
 *       search and type-in that never render and are never sent, but typed with <b>no</b> surrounding
 *       colons (e.g. {@code o7}, {@code :D}). A literal is recognised in the compose field only as a whole
 *       word — bounded on the left by text start, whitespace, or a colon — completed by a space (see
 *       {@link CustomEmojiLiteralResolver}).</li>
 * </ul>
 *
 * <p>Token and alias names are stored <b>bare</b> (without the wrapping colons); the methods that expose
 * them speak the colon-wrapped {@code :name:} form — the form that lives in message text — so the
 * wrapping is applied/stripped at this boundary only ({@link #wrap} / {@link #unwrap}). Literals are the
 * exception: because they are typed without colons, they are stored and exposed verbatim. Tokens and
 * aliases are globally unique across the whole pack; a literal is unique among all literals and may
 * coincide with a token or alias only of its <i>own</i> emoji (never a different one), so every name
 * still resolves to exactly one emoji. Rendering ({@link CustomEmojiParser}, {@link #isCustomToken},
 * {@link #getSource}) operates on tokens only; aliases and literals live in separate maps.
 */
public final class CustomEmojiRegistry {

  private static final String TAG                  = "CustomEmojiRegistry";
  private static final String CONFIG_RELATIVE_PATH = "custom_emoji/current/emoji.json";
  private static final String MEDIA_DIR            = "media";

  private static final Object LOCK = new Object();

  private static volatile boolean loaded = false;

  // Internal maps key on BARE names (no wrapping colons), e.g. token "D_", alias "D:".
  private static Map<String, String> tokenToSource = Collections.emptyMap();  // bare token -> source
  private static Map<String, String> aliasToToken  = Collections.emptyMap();  // bare alias -> bare token

  // Exposed lists, colon-wrapped (":name:"), longest first.
  private static List<String> wrappedTokens  = Collections.emptyList();
  private static List<String> wrappedAliases = Collections.emptyList();

  // Literals are typed WITHOUT wrapping colons, so — unlike tokens and aliases — they are stored and
  // exposed verbatim (never wrapped), and matched as whole words by CustomEmojiLiteralResolver.
  private static Map<String, String> literalToToken = Collections.emptyMap();  // verbatim literal -> bare token
  private static List<String>        literals       = Collections.emptyList(); // verbatim literals, longest first

  // When the "treat every token/alias as a literal" setting is on, getLiterals returns this instead:
  // explicit literals plus every token and alias name (longest first). Precomputed once at load; the pack
  // JSON is never modified. See isCustomEmojiNamesAsLiterals().
  private static List<String>        literalsWithNames = Collections.emptyList();

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
   * Literals to match in the compose field, verbatim (as typed, no wrapping colons), longest first — the
   * pack's explicit literals, plus (when the "treat every token/alias as a literal" setting is on, see
   * {@link org.thoughtcrime.securesms.keyvalue.SettingsValues#isCustomEmojiNamesAsLiterals()}) every token
   * and alias name too, so any emoji can be typed by name without colons.
   */
  public static @NonNull List<String> getLiterals(@NonNull Context context) {
    ensureLoaded(context);
    return namesAsLiteralsEnabled() ? literalsWithNames : literals;
  }

  /**
   * The canonical token ({@code :token:} form) for a literal, or null if it isn't a known literal. The
   * literal is matched verbatim (it carries no wrapping colons), unlike {@link #getAliasToken}. When the
   * "treat every token/alias as a literal" setting is on, a token or alias name also resolves here (to its
   * own token) so it can be typed without colons; an explicit literal registered in the pack still wins.
   */
  public static @Nullable String getLiteralToken(@NonNull Context context, @Nullable CharSequence literal) {
    ensureLoaded(context);
    if (literal == null) {
      return null;
    }
    String key   = literal.toString();
    String token = literalToToken.get(key);
    if (token != null) {
      return wrap(token);
    }
    if (namesAsLiteralsEnabled()) {
      if (tokenToSource.containsKey(key)) {
        return wrap(key);
      }
      String aliasToken = aliasToToken.get(key);
      if (aliasToken != null) {
        return wrap(aliasToken);
      }
    }
    return null;
  }

  private static boolean namesAsLiteralsEnabled() {
    return SignalStore.settings().isCustomEmojiNamesAsLiterals();
  }

  /**
   * Tokens matching a search query — matched by token, alias <i>or</i> literal name — ordered
   * alphabetically. Always returns canonical tokens in {@code :token:} form, so searching by an alias or
   * literal still inserts the token. Shared by the {@code :}-autocomplete popup and the emoji-picker search.
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
    for (Map.Entry<String, String> entry : literalToToken.entrySet()) {
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
      loaded            = false;
      tokenToSource     = Collections.emptyMap();
      aliasToToken      = Collections.emptyMap();
      literalToToken    = Collections.emptyMap();
      wrappedTokens     = Collections.emptyList();
      wrappedAliases    = Collections.emptyList();
      literals          = Collections.emptyList();
      literalsWithNames = Collections.emptyList();
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

      Map<String, String> sourceMap  = new HashMap<>();
      Map<String, String> aliasMap   = new LinkedHashMap<>();
      Map<String, String> literalMap = new LinkedHashMap<>();
      loadFromJson(context.getApplicationContext(), sourceMap, aliasMap, literalMap);

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

      // Literals are used verbatim (no wrapping); still longest-first so a longer literal wins a tie.
      List<String> sortedLiterals = new ArrayList<>(literalMap.keySet());
      sortedLiterals.sort(Comparator.comparingInt(String::length).reversed());

      // Union used when the "treat every token/alias as a literal" setting is on: explicit literals plus
      // every token and alias name. The LinkedHashSet drops overlaps (an explicit literal that already
      // equals a token/alias of its own emoji); they resolve to the same emoji regardless.
      Set<String> namesAndLiterals = new LinkedHashSet<>(literalMap.keySet());
      namesAndLiterals.addAll(sourceMap.keySet());
      namesAndLiterals.addAll(aliasMap.keySet());
      List<String> sortedLiteralsWithNames = new ArrayList<>(namesAndLiterals);
      sortedLiteralsWithNames.sort(Comparator.comparingInt(String::length).reversed());

      tokenToSource     = Collections.unmodifiableMap(sourceMap);
      aliasToToken      = Collections.unmodifiableMap(aliasMap);
      literalToToken    = Collections.unmodifiableMap(literalMap);
      wrappedTokens     = Collections.unmodifiableList(sortedTokens);
      wrappedAliases    = Collections.unmodifiableList(sortedAliases);
      literals          = Collections.unmodifiableList(sortedLiterals);
      literalsWithNames = Collections.unmodifiableList(sortedLiteralsWithNames);
      loaded            = true;
    }
  }

  private static void loadFromJson(@NonNull Context context, @NonNull Map<String, String> sourceMap, @NonNull Map<String, String> aliasMap, @NonNull Map<String, String> literalMap) {
    File configFile = new File(context.getFilesDir(), CONFIG_RELATIVE_PATH);
    if (!configFile.isFile()) {
      return;
    }

    try (InputStream in = new FileInputStream(configFile)) {
      parseJson(in, sourceMap, aliasMap, literalMap, configFile.getParentFile());
      Log.i(TAG, "Loaded " + sourceMap.size() + " custom emotes (" + aliasMap.size() + " aliases, " + literalMap.size() + " literals) from " + configFile.getAbsolutePath());
    } catch (Throwable t) {
      Log.w(TAG, "Could not load custom emotes from " + configFile.getAbsolutePath(), t);
    }
  }

  private static void parseJson(@NonNull InputStream in, @NonNull Map<String, String> sourceMap, @NonNull Map<String, String> aliasMap, @NonNull Map<String, String> literalMap, @Nullable File baseDir) throws Exception {
    String json   = readAllText(in);
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
      Log.w(TAG, "No emoji array found");
      return;
    }

    // Tokens and aliases share one global namespace (each unique across both). Literals are checked
    // separately after the loop: unique among all literals, and allowed to coincide with a token/alias
    // only of the same emoji. A literal may collide with a token/alias defined on a later emoji, so the
    // literal checks are deferred until every token/alias owner is known.
    Set<String>         tokenAliasNames = new HashSet<>();   // token + alias names
    Map<String, String> nameToOwner     = new HashMap<>();   // token/alias name -> owning token
    List<String[]>      literalEntries  = new ArrayList<>(); // [literal, owning token], checked after the loop

    for (int i = 0; i < emoji.length(); i++) {
      JSONObject obj = emoji.optJSONObject(i);
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

      if (!tokenAliasNames.add(token)) {
        Log.w(TAG, "Skipping duplicate name (token): " + token);
        continue;
      }

      nameToOwner.put(token, token);
      sourceMap.put(token, source);

      JSONArray aliasArray = obj.optJSONArray("aliases");
      if (aliasArray != null) {
        for (int j = 0; j < aliasArray.length(); j++) {
          String alias = normalizeName(aliasArray.optString(j, null));
          if (alias == null) {
            continue;
          }
          if (!tokenAliasNames.add(alias)) {
            Log.w(TAG, "Skipping duplicate name (alias): " + alias);
            continue;
          }
          nameToOwner.put(alias, token);
          aliasMap.put(alias, token);
        }
      }

      JSONArray literalArray = obj.optJSONArray("literals");
      if (literalArray != null) {
        for (int j = 0; j < literalArray.length(); j++) {
          String literal = normalizeName(literalArray.optString(j, null));
          if (literal == null) {
            continue;
          }
          literalEntries.add(new String[] { literal, token });
        }
      }
    }

    // A literal must be unique among all literals, and may equal a token/alias only of its own emoji
    // (never one owned by a different emoji), so it always resolves to the same emoji as that name.
    Set<String> usedLiterals = new HashSet<>();
    for (String[] entry : literalEntries) {
      String literal = entry[0];
      String owner   = entry[1];
      if (!usedLiterals.add(literal)) {
        Log.w(TAG, "Skipping duplicate literal: " + literal);
        continue;
      }
      String nameOwner = nameToOwner.get(literal);
      if (nameOwner != null && !nameOwner.equals(owner)) {
        Log.w(TAG, "Skipping literal that matches another emoji's token/alias: " + literal);
        continue;
      }
      literalMap.put(literal, owner);
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
      return "file://" + resolveMediaFile(baseDir, value).getAbsolutePath();
    }

    return null;
  }

  /**
   * Resolves a pack-relative media reference (a {@code file} entry, or a local {@code source}) to a
   * {@link File}. Media conventionally lives in the pack's {@code media/} subdirectory, so a relative
   * name is looked up there first (canonical layout: a bare {@code "pepega.webp"} maps to
   * {@code media/pepega.webp}); if it isn't found under {@code media/}, it falls back to a path relative
   * to the config itself, which keeps an explicit {@code "media/…"} path, any other subdirectory, and the
   * legacy flat layout working. An absolute path is used verbatim. Shared with
   * {@link CustomEmojiPackManager} so import-time validation and load-time resolution agree.
   */
  static @NonNull File resolveMediaFile(@NonNull File baseDir, @NonNull String relativeName) {
    if (relativeName.startsWith("/")) {
      return new File(relativeName);
    }

    File inMedia = new File(new File(baseDir, MEDIA_DIR), relativeName);
    if (inMedia.isFile()) {
      return inMedia;
    }

    return new File(baseDir, relativeName);
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
