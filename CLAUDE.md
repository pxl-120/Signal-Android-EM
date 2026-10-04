# CLAUDE.md

Guidance for Claude Code (and humans) working in this repository.

## What this project is

This is a **fork of the official Signal Android client** (the GitHub `signalapp/Signal-Android`
`main` branch, originally cloned at ~v8.8.2 and since kept current by merging upstream — last synced at
**v8.30.1**). The vast majority of the codebase is
unmodified upstream Signal and should be treated as a stable third-party dependency — **do not try
to understand or document all of it.** Only a thin, well-contained slice has been changed.

The purpose of the fork is to add a **custom animated emoji + inline media system** on top of
Signal:

- **Inline image/GIF URLs** in message text — a **colon-wrapped** `:https://….gif|png|jpg|webp|avif:`
  URL renders as an inline animated image, **gated behind an opt-in Appearance toggle (default off)**
  for privacy. A bare URL — or any `:url:` while the toggle is off — stays plain text and keeps its
  normal link preview.
- **Custom emoji tokens** — text like `:pepega:` renders as a (possibly animated) inline image,
  in message bubbles, the compose field, the emoji picker, and reactions.
- **Importable emoji packs** — a ZIP pack can be imported from the Appearance settings, either from a
  **local file** or from a **URL** (paired with a `{"version":…}` endpoint that auto-updates the pack on
  app start whenever the version string changes).
- **Custom emoji reactions** — custom tokens can be used as message reactions and are accepted on
  the receive side (upstream would otherwise drop any reaction that isn't a real Unicode emoji).
- **Searchable custom emoji (with aliases)** — custom emoji are found in the `:`-autocomplete popup and
  the picker search by their token *or* any alias; selecting or typing an alias resolves to the token.
- **Literal (colon-free) triggers** — an emoji can also declare **literals**: bare words typed *without*
  colons (e.g. `o7`, `:D`) that, once written as a whole word (after text-start, whitespace, or a colon)
  and completed with a space, are swapped to the emoji's `:token:` in the compose field (the triggering
  space is consumed).
  Like aliases they're search / type-in only and never sent, and they raise no autocomplete popup.

The fork is branded **"Signal+"** and installs side-by-side with official Signal (see *Build &
branding* below).

### Source of truth for the modifications: git history + `tools/`

This fork tracks upstream through **git**, so the **commit history is the authoritative changelog** —
every modification is a real commit rather than a hand-written design log. Branch model:

- **`main`** — a pristine, unmodified mirror of `signalapp/Signal-Android` `main`. Only ever
  fast-forwarded to upstream; never carries fork changes.
- **`dev`** — the shipping branch: `main` + the Signal+ modifications. Upstream is pulled in
  periodically by **merging `main` into `dev`** (never rebase — `dev` is long-lived), so the mods
  stay layered on top of official code as it advances.

The **`tools/`** directory (not shipped, not built) holds the fork's build/helper scripts and the
signed-APK output dir:

| Path | Contents |
| --- | --- |
| `build-and-sign.sh` | Build + zipalign + sign the website-flavor release APKs with your key. |
| `gen-custom-emoji-json.sh` | Generate an `emoji.json` (+ `media/`) from a set of media files. |
| `7tv-download-set.sh` | Download a 7TV emote set into a pack layout. |
| `release/` | Signed-APK output — contents are **git-ignored** (`release/.gitignore`); the dir itself is kept. |

## Build & branding

The only build change is in `app/build.gradle.kts`, in the `website` product flavor:

```kotlin
create("website") {
  dimension = "distribution"
  applicationIdSuffix = ".mod"                                     // -> org.thoughtcrime.securesms.mod
  buildConfigField("boolean", "MANAGES_APP_UPDATES", "false")      // was true
  buildConfigField("String", "APK_UPDATE_MANIFEST_URL", "null")    // was the real Signal updater URL
  buildConfigField("String", "BUILD_DISTRIBUTION_TYPE", "\"website\"")
}
```

Consequences:

- **`applicationIdSuffix = ".mod"`** gives the fork a distinct application id, so it installs
  **alongside** official Signal rather than refusing to install over it (different signing key).
- **The in-app self-updater is disabled** (`MANAGES_APP_UPDATES = false`, manifest URL `null`) so the
  fork won't try to pull and install official Signal release APKs over itself.
- Branding lives in **`app/src/website/res/`** (website-flavor resource overlay):
  - `values/strings.xml` → `app_name = "Signal+"`
  - `mipmap-*/ic_launcher.png` → custom launcher icon at all densities.

Build a sideloadable universal APK from the modified repo (or just run `tools/build-and-sign.sh`):

```bash
./gradlew assembleWebsiteProdRelease         # output: app/build/outputs/apk/websiteProdRelease/
# then sign with your own release key (apksigner) and verify
```

For Play-store style output use `bundlePlayProdRelease` (AAB) instead — but note the branding/flavor
changes above are on the **website** flavor.

## Architecture of the custom emoji / inline media subsystem

All new code lives in **`app/src/main/java/org/thoughtcrime/securesms/components/emoji/`** (14 new
files). It splits into two cooperating halves.

### A. Inline media rendering core

| File | Role |
| --- | --- |
| `InlineMediaParser.java` | Regex-finds **colon-wrapped** inline image URLs (`:http(s)://…\.(gif\|png\|jpe?g\|webp\|avif):`). The matched span includes the colons; the captured URL excludes them. |
| `CustomEmojiParser.java` | Finds **custom token** occurrences (`:foo:`) by scanning text for every registered token, longest-first (plain substring match via `indexOf`). |
| `InlineMediaProvider.java` | **The central engine.** `public static SpannableStringBuilder inlinify(CharSequence, TextView)` is the single entry point used by every text surface. Owns the byte cache + async loader. |
| `InlineMediaDrawable.java` | A `Drawable`+`Animatable` wrapper that lets a span be attached **immediately** (as a 0-size placeholder) and have its real drawable swapped in **later, in place**, once the bytes load. |
| `InlineMediaSpan.java` | An `AnimatingImageSpan` that sizes/aligns the wrapped drawable to the line (scaled to `1.6×` line height) without disturbing surrounding text metrics. |
| `ReplacementCharDrawable.java` | Draws the U+FFFD glyph (`�`), shown in place of remote media that the privacy toggle blocks from loading (see *pack format* / *caveats*). |
| `InlineMediaDeleter.java` | A compose-field `TextWatcher` (modeled on upstream `MentionDeleter`) that deletes a **whole `:token:` / `:url:` chip in one backspace** when a deletion lands inside its `InlineMediaSpan`, instead of nibbling one char at a time. Fixes older-Android where a backspace only *shrinks* the `ReplacementSpan` (leaving a half-token that keeps rendering as an image and can leak a stale span into a retyped token); a no-op on Android versions that already delete the span atomically. |

**Rendering data flow (`inlinify`):**

1. Copy the input into a fresh `SpannableStringBuilder` and **strip any pre-existing
   `InlineMediaSpan`s** (idempotent — safe to call repeatedly, e.g. on every keystroke).
2. Collect URL matches + custom-token matches into one list, sort by position and de-overlap
   (earliest / longest wins), then attach an `InlineMediaSpan` wrapping a fresh `InlineMediaDrawable`
   **immediately** for each.
3. If the bytes are already cached, set the real drawable synchronously. Otherwise kick off an async
   load; on completion update the **wrapper drawable in place** and call `tv.requestLayout()` /
   `tv.invalidate()`.
4. **It never calls `TextView.setText(...)` from async completion** — this is deliberate (an earlier
   stage-1 design did, and caused churn). When editing a touch-up of the whole text *is* needed,
   that's done by the caller (`ComposeText`), not here.

**Caching / loading:** a static 20 MB `LruCache<String, byte[]>` keyed by **cache key** (the URL, or
the token string), plus `IN_FLIGHT` / `PENDING` maps so concurrent requests for the same key
coalesce. Decoding uses `ImageDecoder` (API 28+, so animated WebP/GIF animate) or `BitmapDrawable`
fallback. `loadBytes` resolves `http(s)://` (download), `file://`, and absolute `/…` paths.

### B. Custom emoji pack / registry

| File | Role |
| --- | --- |
| `CustomEmojiRegistry.java` | Loads the token→source map from the **imported pack only** (`filesDir/custom_emoji/current/emoji.json`, media under `current/media/`); empty until a pack is imported. Stores names **bare** (no colons) internally and exposes them **colon-wrapped** (`wrap`/`unwrap` at the boundary). Exposes `isCustomToken`, `getSource`, `getTokens`, `getAliases`/`getAliasToken`, `getLiterals`/`getLiteralToken`, `searchTokens` (shared query normalization + matching), `reload`. Thread-safe, lazily loaded. |
| `CustomEmojiPackManager.java` | `importZip(Context, Uri)` — extracts a ZIP to a temp dir (with path-traversal guards), validates `emoji.json` (media resolved under `media/`), atomically rotates it into `current/`, then clears the media cache and reloads the registry. |
| `CustomEmojiPageModel.java` | An `EmojiPageModel` with key `"Custom"` that backs the dedicated picker tab and the reaction-picker custom block. |
| `CustomEmojiImageBinder.java` | Binds a custom token into an `ImageView` cell (picker / reaction grid) asynchronously, using the view's content-description/tag as the stable async identity guard. `createDrawable(...)` returns a standalone self-updating `InlineMediaDrawable` for hosts that take a `Drawable` instead of a view (the Compose media keyboard). |
| `CustomEmojiAliasResolver.java` | Swaps a hand-typed completed `:alias:` → its `:token:` in the compose field (picker/autocomplete already insert the token directly). |
| `CustomEmojiLiteralResolver.java` | Swaps a completed **literal** — a colon-free whole word (e.g. `o7`) that starts at text-start, after whitespace, or right after a colon (so it can follow a `:token:`) and is finished with a space — → its `:token:` in the compose field, consuming that one triggering space. |
| `CustomEmojiPackUpdater.java` | "Import from URL": downloads + imports a pack from a URL and tracks a `{"version":…}` endpoint; on app start, re-imports when the version string changes (`checkForUpdate`). |

## Integration points (modified upstream files)

~23 upstream files are modified. Grouped by surface. (Upstream moved the emoji core — `Emoji`,
`EmojiPageModel`, `EmojiProvider`, `EmojiUtil`, `AnimatingImageSpan`, `EmojiSource`, the category
attrs… — into the **`:lib:emoji`** module, package **`org.signal.emoji`**; fork code imports them from there.)

**Message transcript rendering**
- `components/emoji/EmojiTextView.kt` (Kotlin since upstream ~v8.2x) — `emojify(...)` runs its result
  through `InlineMediaProvider.inlinify(...)` so bubbles render tokens + inline URLs.
- `conversation/ConversationItem.java` — `shouldSuppressLinkPreview(...)`: when the inline-URL toggle
  is **on** and the body contains a `:url:`, suppress the normal link-preview card (avoids showing both
  the inline media and a preview for the same URL). When the toggle is off, never suppresses.

**Compose field (live preview)**
- `components/ComposeText.java` — a `TextWatcher` re-runs `inlinify` on edits and replaces the
  editable only when text/inline-span ranges actually changed (`sameTextAndInlineSpans`), preserving
  the selection. The same watcher swaps a completed hand-typed `:alias:` → `:token:`
  (`CustomEmojiAliasResolver`) and a completed colon-free **literal** — a whole word finished with a
  space — → `:token:`, consuming that space (`CustomEmojiLiteralResolver`). `findQueryStart(...)` is patched so a finished token/alias like
  `:aware:` does **not** re-trigger the `:`-autocomplete popup (`isClosingColonOfCustomEmojiToken`,
  which checks tokens and aliases). A separate `InlineMediaDeleter` watcher (registered just before the
  inline-media watcher) makes a backspace **into** a rendered chip delete the whole `:token:` / `:url:`
  at once — this is what upstream Android ≥16 already does for a `ReplacementSpan`, but older releases
  only shrink the span and fail to re-layout, so without it a partial `:to` keeps rendering as an image.

**Emoji picker — chat screen (Compose media keyboard)**

Upstream replaced the conversation screen's emoji picker with a Compose keyboard in the
**`:feature:media-keyboard`** module (`org.signal.mediakeyboard`). The classic `keyboard/emoji/**`
picker below is still used elsewhere (e.g. story replies). Custom emoji in the new keyboard:
- `feature/media-keyboard/.../data/EmojiKeyboardRepository.kt` — adds `EmojiKeyboardCategory.CUSTOM`
  (key `"Custom"`, string `MediaKeyboard__custom` in the module's `strings.xml`).
- `feature/media-keyboard/.../screens/emoji/EmojiPageScreen.kt` — tab icon for `CUSTOM`.
- `mediakeyboard/SignalEmojiKeyboardRepository.kt` (app) — inserts the custom page right after the first
  standard page, merges `CustomEmojiRegistry.searchTokens(...)` into `search` (honouring the
  "custom first" toggle), and `getEmojiDrawable` returns `CustomEmojiImageBinder.createDrawable(...)`
  for custom tokens (rendered via accompanist `DrawablePainter`, so animation + async load just work;
  `InlineMediaDrawable` reports intrinsic size for aspect ratio).

**Emoji picker — classic views**
- `components/emoji/EmojiImageView.java` — `setImageEmoji` routes custom tokens to
  `CustomEmojiImageBinder`, else normal Signal rendering.
- `components/emoji/EmojiPageViewGridAdapter.java` — picker grid cell renders custom tokens via the
  binder, else the normal emoji drawable.
- `keyboard/emoji/EmojiKeyboardPageRepository.kt` — page order: Recents, first standard page, **custom
  page**, remaining standard pages.
- `keyboard/emoji/EmojiKeyboardPageCategoryMappingModel.kt` — adds `CustomMappingModel` (the tab-strip
  model; **defined here, not in its own file**).
- `keyboard/emoji/EmojiKeyboardPageViewModel.kt` — special-cases `CustomEmojiPageModel.KEY` in `pages`
  and `categories` (doesn't run `"Custom"` through `EmojiCategory.forKey`).
- `keyboard/emoji/EmojiKeyboardPageCategoriesAdapter.kt` — registers the `CustomMappingModel` view-holder factory.
- `keyboard/emoji/EmojiPageModelExtensions.kt` — forces custom items to image cells (`EmojiModel`), not text cells.

**Reactions**
- `reactions/any/ReactWithAnyEmojiRepository.java` — inserts a custom block right after the first
  standard reaction page.
- `reactions/any/ReactWithAnyEmojiViewModel.java` — emits `CustomMappingModel` for the custom tab.
- `reactions/ReactionsConversationView.java` — groups reactions by **exact token** for custom emoji
  instead of Unicode canonicalization.
- `messages/DataMessageProcessor.kt` — **critical**: accepts incoming reactions that are custom tokens
  (`handleReaction` / `handleStoryReaction`), which upstream would reject via `EmojiUtil.isEmoji`.

**Search** (both delegate to `CustomEmojiRegistry.searchTokens(...)`, which matches by token, **alias or literal**
and always returns the canonical token)
- `keyboard/emoji/search/EmojiSearchRepository.kt` — merges custom tokens into picker search results.
- `conversation/ui/inlinequery/InlineQueryViewModelV2.kt` — merges custom tokens into the
  `:`-autocomplete popup (`queryEmoji`).

  Ordering of matched custom tokens vs. standard emoji is controlled by the **"Show custom emoji first
  in search"** toggle (`SignalStore.settings().isCustomEmojiSearchFirst()`, **default off**): off keeps
  the upstream-like order (standard first, custom appended); on lists custom matches first. Both
  surfaces read the flag (`EmojiSearchRepository.mergeCustomTokens`, `InlineQueryViewModelV2.queryEmoji`).

**Settings / import** — a "Signal+ settings" section in Settings → Appearance
- `components/settings/app/appearance/AppearanceSettingsFragment.kt` (+ `AppearanceSettingsState.kt` /
  `AppearanceSettingsViewModel.kt`) — the section header, the **"Render link images & GIFs inline"**
  `ToggleRow` (privacy opt-in), the **"Show custom emoji first in search"** `ToggleRow` (search-ordering
  opt-in, default off), the **"Type custom emoji without colons"** `ToggleRow` (treat every token/alias as
  a literal, default off), and a **pack source** `RadioListRow` (Local file / From URL):
  - *Local file* → "Import custom emote pack" `TextRow` → `OpenDocument()` →
    `CustomEmojiPackManager.importZip(uri)` (background thread).
  - *From URL* → two `TextFields` (pack `.zip` URL, version URL) + "Download & apply" →
    `CustomEmojiPackUpdater.applyFromUrl(...)`, plus status lines (current version, last check, last update).
- `keyvalue/SettingsValues.kt` (Kotlin since upstream ~v8.2x; properties keep the same Java accessors) — backs all of the above. Privacy toggle: `isInlineUrlMediaEnabled()` /
  `setInlineUrlMediaEnabled(...)`, key `settings.signalplus.inlineUrlMediaEnabled`, **default off**, read
  at render time by `InlineMediaProvider` (message text), `CustomEmojiImageBinder`
  (picker / reactions / autocomplete popup), and `ConversationItem` (link-preview suppression).
  Search-ordering toggle: `isCustomEmojiSearchFirst()` / `setCustomEmojiSearchFirst(...)`, key
  `settings.signalplus.customEmoji.searchFirst`, **default off** (see *Search* above).
  Names-as-literals toggle: `isCustomEmojiNamesAsLiterals()` / `setCustomEmojiNamesAsLiterals(...)`, key
  `settings.signalplus.customEmoji.namesAsLiterals`, **default off** — when on, `CustomEmojiRegistry`
  treats every token and alias as a literal too (a precomputed union list; the pack JSON is never
  modified), so any emoji can be typed by name without colons; read via `getLiterals` / `getLiteralToken`
  at compose time.
  URL-import config: `isCustomEmojiImportFromUrl`, `customEmojiPackZipUrl`, `customEmojiPackVersionUrl`,
  `customEmojiPackVersion`, `customEmojiPackLastCheck`, `customEmojiPackLastUpdate`
  (`settings.signalplus.customEmoji.*`).
- `ApplicationContext.java` — an `AppStartup.addPostRender` task dispatches
  `CustomEmojiPackUpdater.checkForUpdate(...)` to a background executor on every launch (re-imports if the
  remote version changed; no-op unless the URL method is active).

**Resources**
- `res/values/strings.xml` — adds `appearance_settings__import_custom_emote_pack` and
  `custom_emoji__category` ("Custom").

## Custom emoji pack format

`emoji.json` (at the ZIP root) is either `{ "emoji": [ … ] }` or a bare array. Each entry needs a
`token` and a source (precedence `source` → `url` → `file`), plus optional `aliases` and `literals` arrays. **The `token` and each
alias are the bare emoji name, without colons** (e.g. `"pepega"`). The name is the emoji's identity; the
colon-wrapped `:pepega:` form is what gets inserted, sent, matched in message text, and rendered.

The importer stores names **verbatim** (only whitespace-trimmed) — it no longer strips or adds colons —
so a name may itself contain a colon. That's the point of the split: to support an emoji whose natural
name has a colon, e.g. the `D:` face. Give it a colon-free **token** `"D_"` and an **alias** `"D:"`; it
sends/renders as `:D_:`, and the user can reach it by typing `:` then `D:` (popup → pick) or by typing
the alias in full as `:D::` (the compose field swaps the completed `:D::` → `:D_:`). Don't write a
`token`/alias with surrounding colons (`":pepega:"`) — under this scheme that would become a name that
literally contains colons.

**Literals** are a second kind of type-in name, for triggers you'd type *without* colons — e.g. `o7`,
`xdx`, `???`, `:D`, `D:`. In the compose field a literal is recognised only as a whole word: it must
start at the beginning of the message, right after whitespace (space/tab/newline), or right after a colon
(so it can be typed straight after a `:token:`), and be finished with
a **space**, which is consumed as the literal is swapped to `:token:` (typing `o7 ` yields `:salute:`; a
second trailing space is kept, so `o7  ` yields `:salute: `). Because a literal isn't colon-prefixed it
raises no autocomplete popup while typing — though a literal that itself begins with a colon (`:D`) can
still transiently open the standard `:` popup, resolving on the space regardless. Literals are also
searchable (`o7` finds the salute emoji). Like aliases they resolve to the token before send and never
reach the wire. A literal is **unique among all literals** and can't reuse a *different* emoji's token or
alias, but it **may** equal its own emoji's token or an alias — e.g. an emoji with token `o7` and literal
`o7`: `:o7:` renders it, and typing `o7 ` inserts `:o7:` (both resolve to the same emoji).

```json
{
  "emoji": [
    { "token": "pepega", "url": "https://cdn.7tv.app/emote/…/2x.webp", "aliases": ["pepe", "sadge"] },
    { "token": "D_",     "url": "https://cdn.7tv.app/emote/…/2x.webp", "aliases": ["D:"] },
    { "token": "salute", "url": "https://cdn.7tv.app/emote/…/2x.webp", "literals": ["o7"] },
    { "token": "foo",    "file": "catjam.png" }
  ]
}
```

- **`url`** → remote media (fetched and cached on first render; gated by the privacy toggle).
- **`file`** → media file inside the imported ZIP's **`media/`** directory, given as a bare filename
  (`"catjam.png"` → `media/catjam.png`) and resolved to a `file://` path. An explicit `"media/…"` path,
  another subdirectory, or the legacy flat layout also resolve — a relative `file` is looked up under
  `media/` first, then next to the config (`CustomEmojiRegistry.resolveMediaFile`, shared with the
  importer so validation and load-time resolution agree).
- **`aliases`** (optional) → alternative names for **search / type-in only**; never rendered or sent.
  Every token and alias must be **globally unique** across the pack (importer rejects duplicates; loader
  skips them). Resolved to the token before anything is sent, so changing/removing an alias never affects
  already-sent messages.
- **`literals`** (optional) → colon-free type-in triggers (`CustomEmojiLiteralResolver`): a whole word,
  typed without colons and completed with a space, swapped to the token in the compose field (the space is
  consumed). **Unique among all literals** and never equal to a *different* emoji's token/alias, but a
  literal **may** match its own emoji's token or alias; searchable; resolved to the token before send, so
  they never reach the wire.

**Location:** the imported pack is the only source — `filesDir/custom_emoji/current/emoji.json` plus its
`media/` directory. The ZIP is supplied either by picking a local file or by URL download
(`CustomEmojiPackUpdater`); in it, `emoji.json` must be at the **root** and bundled media lives under
**`media/`**:

```
pack.zip
├── emoji.json
└── media/
    ├── catjam.png
    └── …
```

(There is no bundled/default pack — see *Known deviations*.)

## Conventions / gotchas when modifying this code

- **`InlineMediaProvider.inlinify(...)` is the one true entry point** for turning text into spanned
  text. Any new text surface that should show custom emoji / inline media should call it. It is
  idempotent and safe to call repeatedly.
- **Cache keys are the URL or the colon-wrapped `:token:` string** (the form in message text / the
  `Emoji` value) — keep them stable if you touch the loader.
- **Never call `setText` from async load completion** inside the provider; update the wrapper drawable
  and request layout instead (see the data-flow note above).
- Custom tokens travel over the wire as **literal text** (message body or reaction string). They only
  render as images on a client that has this fork **and** the same pack/token registered; on stock
  Signal they appear as plain `:foo:` text. Receive-side acceptance of token reactions is handled in
  `DataMessageProcessor.kt`.
- Token matching is plain substring (`indexOf`), longest-token-first — a token can match inside a
  larger word/URL.
- A custom emoji's **token** is the only identity rendered/sent (static); **aliases** are search/type-in
  only and globally unique with tokens. **Names are stored bare (no colons)**; `CustomEmojiRegistry`
  applies the `:name:` wrapping at its own boundary (`wrap`/`unwrap`), so the rest of the app — parser,
  binder, search, reactions, compose suppression — keeps speaking the `:name:` form. A name may itself
  contain a colon (alias `D:` ⇒ stored `D:`, wire form `:D::`). Picker/autocomplete insert the `:token:`
  directly; a hand-typed completed `:alias:` is swapped to `:token:` in the compose field
  (`CustomEmojiAliasResolver`) before it's ever sent, so aliases never reach the wire.
- **Literals** are the colon-free sibling of aliases: bare whole-word triggers (`o7`, `:D`) swapped to
  `:token:` in the compose field only when completed with a space (that space is consumed);
  `CustomEmojiLiteralResolver` runs in the same `ComposeText` watcher as the alias swap. Stored/exposed
  **verbatim** (not colon-wrapped, since they're typed without colons); unique among literals and disjoint
  from *other* emojis' tokens/aliases, though a literal may equal its **own** emoji's token/alias;
  searchable; resolved to the token before send so they never reach the wire. The Appearance **"Type
  custom emoji without colons"** toggle (default off, `isCustomEmojiNamesAsLiterals()`) additionally makes
  every token and alias act as a literal at runtime — `CustomEmojiRegistry` returns a precomputed union
  from `getLiterals`/`getLiteralToken`; the pack JSON is untouched.
- When the inline-media toggle is **off** (`SignalStore.settings().isInlineUrlMediaEnabled()`, default
  off), **no remote media is fetched on any surface** (message text, compose, picker, reactions,
  autocomplete; the byte cache is bypassed too). Blocked remote `:token:`s render as the U+FFFD glyph
  (`ReplacementCharDrawable`); a bare `:url:` stays plain text; the compose field keeps the literal
  `:token:`. Local-file `:token:`s always render.

## Known deviations & caveats

- **The bundled asset pack is deprecated and to be removed.** The `assets/custom_emoji/` fallback in
  `CustomEmojiRegistry` was an early bootstrap/testing convenience from when the system was first built;
  the **in-app ZIP import is the canonical way** to add custom emoji now. The asset directory is **not
  present** in the repo, and this whole asset-loading code path is **slated for removal** — don't build
  on it or try to "restore" a default pack. With no imported pack, `getTokens` returns empty and there
  are simply zero custom emoji until a ZIP is imported (inline image URLs still work regardless).
- **`build.gradle.kts` and `app/src/website/res/`** — the website-flavor build changes (`.mod` suffix,
  updater off) and the branding overlay; see *Build & branding* above.
- `InlineMediaProvider.java` contains a leftover **unused private `applyInlineSpan(...)`** method (a
  vestige of the stage-1 design). Harmless; the live path is `attachInlineSpan(...)`.
- **Privacy/network behavior of remote URLs:** remote media is fetched with a plain `HttpURLConnection`
  (spoofed desktop `User-Agent`), **bypassing Signal's network/proxy/Tor stack**, with no host
  allowlist — a fetch leaks the device IP to that host **on render** (tracking-pixel exposure). This is
  gated behind the opt-in Appearance toggle (default off): **when off, nothing is fetched on any
  surface** (message text, compose, picker, reactions, autocomplete; the byte cache is bypassed too),
  and blocked remote media renders as the U+FFFD glyph (a bare `:url:` stays text so it can be read).
  **Remaining:** when the toggle is **on**, the fetch still uses a raw `HttpURLConnection`, not
  Signal's networking stack — keep this in mind before widening where remote media auto-loads.

## Quick file map

```
tools/                                       # build & helper scripts + release output (git-ignored APKs)
app/build.gradle.kts                         # website flavor: .mod suffix, updater off
app/src/website/res/                         # "Signal+" name + launcher icon
app/src/main/java/.../components/emoji/
  InlineMediaParser/Drawable/Span/Provider/
  Deleter                                    # inline media rendering core
  CustomEmojiRegistry/Parser/PackManager/
  PageModel/ImageBinder                      # custom emoji pack + registry
app/src/main/java/.../components/ComposeText.java          # compose live preview + popup suppression
app/src/main/java/.../conversation/ConversationItem.java   # link-preview suppression
app/src/main/java/.../keyboard/emoji/**                    # classic picker tab integration
app/src/main/java/.../mediakeyboard/SignalEmojiKeyboardRepository.kt  # Compose chat keyboard: custom tab/search/drawables
feature/media-keyboard/                      # +CUSTOM category (enum, icon, string)
app/src/main/java/.../reactions/**                         # custom reactions
app/src/main/java/.../messages/DataMessageProcessor.kt     # accept incoming token reactions
app/src/main/java/.../keyboard/emoji/search/EmojiSearchRepository.kt   # picker search
app/src/main/java/.../conversation/ui/inlinequery/InlineQueryViewModelV2.kt  # ":" autocomplete
app/src/main/java/.../settings/app/appearance/AppearanceSettingsFragment.kt  # pack import UI
app/src/main/res/values/strings.xml          # +2 strings
```
