# Editorial design tokens and YouTube-embed compliance

This document explains two architectural decisions behind the Android app's
Editorial Reader redesign: the design-token layer that replaced Material
theming (`android/app/src/main/java/com/github/jayteealao/playster/ui/editorial/`),
and the YouTube-embed constraints that shaped the Player and Transcript
screens (`android/app/src/main/java/com/github/jayteealao/playster/screens/player/playback/`).
It is a *why*, not a *how* — for how any individual screen is built, read the
screen's own source and KDoc.

---

## Contents

1. [The token architecture](#1-the-token-architecture)
2. [Why not MaterialTheme alone](#2-why-not-materialtheme-alone)
3. [The cold-start no-flash gate](#3-the-cold-start-no-flash-gate)
4. [YouTube-embed compliance constraints](#4-youtube-embed-compliance-constraints)
5. [The shared PlaybackSession single-embed model](#5-the-shared-playbacksession-single-embed-model)

---

## 1. The token architecture

Every editorial surface reads its colors and type from one resolved object,
[`EditorialTokens`](../../android/app/src/main/java/com/github/jayteealao/playster/ui/editorial/EditorialTokens.kt)
(`@Immutable`), reached through a single composition local,
`LocalEditorialTokens`. `EditorialTokens` bundles four adjustable axes plus
the two values derived from them:

| Field | What it holds |
|---|---|
| `palette` | one of four `PaperPalette`s — nine color roles (`paper`, `paperDeep`, `paperEdge`, `ink`, `inkSoft`, `inkFaint`, `rule`, `ruleFaint`, `highlight`) plus a `dark` flag |
| `accent` | one of four `EditorialAccent` pairs (a chromatic color + its soft companion) |
| `face` | one of four `EditorialFace`s — a display family paired with a body family |
| `sizeStep` | S/M/L/XL, a multiplier applied to every type-ramp size |
| `lineHeightStep` | Tight/Comfortable/Airy — scales body line-height only |
| `density` | Compact/Default/Roomy (defined; v1 ships Default only) |
| `type` | the resolved `EditorialTypeRamp` — ten `TextStyle`s (kicker, appBarKicker, display, deck, body, dropcap, pullQuote, folio, navLabel, navLabelInactive) computed once from `face` + `sizeStep` + `lineHeightStep` |

`EditorialPalettes`, `EditorialAccents`, and `EditorialFaces` each expose a
fixed `All` list and a `fromKey` lookup (unknown/absent keys resolve to the
prototype's defaults — Cream, Oxblood, Source Serif), so persisted preference
strings always resolve to a valid token set.

**One local, not one per category.** `EditorialTheme` resolves every axis
into a single `EditorialTokens` instance inside `remember(palette, accent,
face, sizeStep, lineHeightStep, density)`, then provides that one object
through `LocalEditorialTokens`, a `staticCompositionLocalOf`. Two design
choices are doing the work here:

- **Bundling** — a screen changing the palette (Settings) touches every one
  of these axes potentially at once; one composite object means that change
  is one value swap on one local, not a cascade across N independent locals
  each firing their own invalidation.
- **`remember` keyed on every axis** — without it, each recomposition of
  `EditorialTheme` would allocate a new, content-equal `EditorialTokens`
  instance. Keying `remember` on the axis inputs means the object's identity
  is stable across recompositions that don't touch any axis, and only
  actually changes when the reader picks a new palette/face/size/line-height.
- **`staticCompositionLocalOf`, not `compositionLocalOf`** — a *dynamic*
  local (`compositionLocalOf`) wraps its value in snapshot state so Compose
  can invalidate only the specific composables that read `.current`, at the
  cost of per-read tracking overhead. A *static* local skips that tracking
  entirely: reads are cheap, but a value change recomposes everything inside
  the `CompositionLocalProvider`'s content. Editorial tokens are read from
  nearly every leaf composable in the tree (every piece of text, every rule,
  every row background), so the fine-grained tracking a dynamic local buys
  has little to offer — almost everything would be invalidated either way —
  while every one of those reads pays the dynamic local's tracking cost on
  every recomposition, change or not. Given palette/face/size/line-height
  changes are rare (one Settings action) and token reads are constant, the
  static local's cheap-read/full-recompose-on-change trade is the right one.
  This is also the Android team's own stated guidance for values that change
  rarely (see [Custom design systems in Compose](https://developer.android.com/develop/ui/compose/designsystems/custom)).

## 2. Why not MaterialTheme alone

`EditorialTheme` wraps its content in a thin `MaterialTheme` *inside* the
`CompositionLocalProvider` — but strictly as an interop seam for stock
Material components (dialogs, ripples, any third-party composable), not as
the design system itself:

```kotlin
// EditorialTheme.kt
CompositionLocalProvider(LocalEditorialTokens provides tokens) {
    MaterialTheme(colorScheme = colorScheme, content = content)
}
```

The bridged `ColorScheme` maps `background`/`surface` to the palette's
`paper`, `onBackground`/`onSurface` to `ink`, `primary` to the accent,
`surfaceVariant`/`onSurfaceVariant` to `paperDeep`/`inkSoft`, and switches
`darkColorScheme`/`lightColorScheme` on `palette.dark` — enough for a
borrowed Material component to render in the right paper tone, nothing more.
No editorial screen composable reads `MaterialTheme.colorScheme` or
`MaterialTheme.typography`; every one reads `LocalEditorialTokens.current`.

Material's own theming model doesn't fit the source design for two concrete
reasons:

- **Color roles.** Material 3's `ColorScheme` has ~25 roles built around
  tonal elevation, containers, and (optionally) dynamic color extraction from
  wallpaper. The editorial palette is nine flat roles on a paper/ink
  metaphor with no tonal elevation and no dynamic color — the design brief
  explicitly bans in-screen card elevation and pure `#FFFFFF`/`#000000`
  surfaces (enforced at the Roborazzi/token-lint layer, AC4 in the shape's
  acceptance criteria). There is no faithful mapping from nine paper/ink
  roles onto 25 Material roles without inventing meaning Material doesn't
  have and the mock doesn't need.
- **Type ramp.** Material's `Typography` is fifteen fixed, named styles
  (`displayLarge` … `labelSmall`) with one font family assumption per style
  tier. The editorial ramp is ten purpose-named styles (kicker, dropcap,
  pull quote, folio, nav label, …) that swap font *family* per face
  selection — including a face-specific degradation rule (Cormorant
  Garamond and Fraunces read beautifully large but not small, so their body
  text falls back to Source Serif 4 while their display size keeps the
  chosen face) — and independently scale by a size step and, for body text
  only, a line-height step. None of that is expressible as a Material
  `Typography` swap; it requires the ramp to be a first-class resolved
  object, not a set of named `TextStyle` slots.

## 3. The cold-start no-flash gate

One consequence of the token model is worth naming because it isn't a
per-screen concern: the palette must be known *before* the first Compose
frame, or the window paints the manifest default and visibly flashes to the
saved paper a moment later.
[`EditorialThemeGate`](../../android/app/src/main/java/com/github/jayteealao/playster/ui/editorial/EditorialThemeGate.kt)
reads the saved palette key synchronously from its own `SharedPreferences`
file — deliberately not the app's async DataStore, which cannot be read
before `setContent` — and applies a matching window theme (`applyPreSetContent`,
called before `super.onCreate`) so even the pre-Compose window paints the
right paper. `writePalette` uses `commit()` rather than `apply()` for the
same reason: the value must be durably on disk before the *next* cold start
can read it, and an async write racing a force-stop is exactly the flash bug
this gate exists to prevent. Everything else the token model needs (face,
size, line-height) lives in the ordinary async DataStore, because nothing
needs those values before the first frame.

## 4. YouTube-embed compliance constraints

No official Android-native YouTube player library exists anymore — Google
retired it in 2023 — leaving the [YouTube IFrame Player
API](https://developers.google.com/youtube/terms/required-minimum-functionality)
loaded inside a WebView as the only compliant playback path. The app uses
`android-youtube-player:13.0.0`, a wrapper around exactly that: an
`AndroidView`-hosted `YouTubePlayerView` that loads the official IFrame
player and exposes play/pause/seek/speed and a position stream
(`onCurrentSecond`) as listener callbacks.

YouTube's Required Minimum Functionality (RMF) terms — which the shape's
non-functional requirements rank *above* pixel fidelity to the mock — impose
constraints that visibly shaped the Player and Transcript screens, because
the source mock never drew a video surface at all:

- **The player must stay visible while playing** — no audio-only-while-hidden,
  no background playback.
- **No overlay chrome painted on the video surface** — custom UI may sit
  around the player, never on top of it.
- **A minimum player size** (200×200px) and **restricted autoplay**.

How the code encodes each of these:

- **IFrame options with no escape hatches.**
  [`PlaybackSession.view()`](../../android/app/src/main/java/com/github/jayteealao/playster/screens/player/playback/PlaybackSession.kt)
  builds the embed with `controls(0)`, `rel(0)`, `ivLoadPolicy(3)`, and
  `fullscreen(0)` — no native web chrome, no related-video suggestions, no
  fullscreen mode that could carry the video outside the editorial frame.
- **Cue, don't autoplay.**
  [`PlaybackController`](../../android/app/src/main/java/com/github/jayteealao/playster/screens/player/playback/PlaybackController.kt)
  calls `cueVideo` (not `loadVideo`) on `onReady`, so nothing plays until the
  reader taps play — RMF autoplay compliance and the deliberate editorial
  "press to listen" beat, doing double duty.
- **A real 16:9 surface, furniture kept off it.** Both derived video
  surfaces —
  [`VideoPanel`](../../android/app/src/main/java/com/github/jayteealao/playster/screens/player/VideoPanel.kt)
  on the Player and
  [`TranscriptEmbed`](../../android/app/src/main/java/com/github/jayteealao/playster/screens/transcript/TranscriptEmbed.kt)
  on the Transcript — render a genuine 16:9 band (≈232dp at the 412dp
  reference width, clearing the 200×200px floor) and confine the
  "▶ PLAYING"/"PAUSED" label and any collapse control to a thin bar
  *beneath* the video rectangle, never painted over it. `VideoPanel`
  collapses to a slim strip that still shows the live embed rather than a
  fake now-playing bar, so the video is never hidden behind chrome even
  when minimized.
- **Failure never fakes a playing state.**
  [`PlaybackError`](../../android/app/src/main/java/com/github/jayteealao/playster/screens/player/playback/PlaybackError.kt)
  maps the library's `PlayerConstants.PlayerError` enum (which already
  folds the IFrame's raw 100/101/150/153 codes before any listener sees
  them) onto an editorial error surface that replaces the player area
  entirely — no seek bar or mini-player pretends playback is happening.

**Why background play, skip-silences, and downloads were dropped** (not
disabled — removed from Settings entirely):

- **Background play** requires either keeping the video "visible" per RMF —
  which a backgrounded app cannot do — or a separate audio-only playback API
  that YouTube's ToS does not grant to third-party embeds. There is no
  compliant way to honor this row.
- **Skip-silences** would require detecting silence in the raw decoded
  audio stream. The IFrame API exposes only playback verbs (play, pause,
  seek, speed, cue) and a position stream — no audio buffer of any kind — so
  this is not an implementation gap, it is a capability the embed simply
  does not expose.
- **Downloads/offline** assume a local media file to write and a local
  decoder to play it back. The embed is a live, network-backed WebView
  surface with neither; there is nothing to download, and the reader cannot
  play a cached IFrame session offline.

## 5. The shared PlaybackSession single-embed model

The Transcript screen is reachable as its own route (not just a way back to
the Player) and is specified to keep the video playing while the reader
reads — but the source mock drew no video surface there. Two prior
paragraphs' worth of RMF constraints collide here: a standalone route that
keeps playing audio needs the video to stay *visible* on that route too, and
"visible" must mean the *same* embed the Player was showing, continuously,
not a second player instance starting fresh.

[`PlaybackSession`](../../android/app/src/main/java/com/github/jayteealao/playster/screens/player/playback/PlaybackSession.kt)
is the answer: an `@ActivityRetainedScoped` class holding the one live
`PlaybackController` and the one retained `YouTubePlayerView` for as long as
the activity lives, injected into both `PlayerViewModel` and
`TranscriptViewModel`. It is a client-side architecture change only — no
backend surface.

- `controllerFor(videoId, …)` returns the existing controller when the
  requested video is already live (re-targeting a pending seek if one is
  queued, so a Search jump-to-timestamp or a `?t=` deep link is never
  silently dropped just because the controller was reused), and only tears
  down and rebuilds on an actual video change.
- `view(host)` hands out the one retained `YouTubePlayerView`, detaching it
  from whichever Compose container held it before and re-parenting it into
  the new host — never recreating the WebView on a mere route change, which
  is what makes the Player→Transcript handoff a continuous, un-flashed
  video rather than a stop/restart.
- `attach()`/`detach()` reference-count the playback surfaces currently in
  composition (`YouTubePlayerHost`'s `DisposableEffect`). When the count
  drops to zero and stays there past a 400ms debounce, playback pauses —
  nothing plays off a visible surface. The 400ms window is exactly wide
  enough to bridge the Player→Transcript handoff's one-frame gap (both
  routes host a playback surface) without pausing, while a genuine
  navigation away — Home, Playlist, Search — still pauses promptly.
- The progress-write throttle also lives on this session rather than on
  either screen, so a reader who keeps listening on the Transcript route
  keeps accruing persisted position exactly as they would on the Player.

The result: exactly one YouTube embed exists per activity lifetime, it is
visible on every route that plays it, and it survives the one navigation the
product actually needs it to survive.
