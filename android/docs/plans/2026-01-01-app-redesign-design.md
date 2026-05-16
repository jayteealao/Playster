# Playster App Redesign

## Overview

Complete UI redesign for Playster, a YouTube content management app. The design follows a **bold & playful** visual style with electric blue/cyan accents on a clean light-mode canvas.

## Design Principles

- **Playful**: Rounded corners, energetic colors, friendly feel
- **Clean**: White canvas lets YouTube content (thumbnails) pop
- **Focused**: Minimal UI, single clear actions per screen
- **Branded**: Consistent cyan accent creates recognition

---

## Color System

### Primary Palette

| Token | Hex | Usage |
|-------|-----|-------|
| `Cyan500` | #00B8D9 | Primary accent, buttons, highlights |
| `Purple500` | #7B61FF | Secondary accent, gradients |
| `Cyan600` | #0095B3 | Pressed/active states |

### Backgrounds

| Token | Hex | Usage |
|-------|-----|-------|
| `White` | #FFFFFF | Primary background |
| `Gray50` | #F8FAFC | Card backgrounds, surfaces |
| `Gray100` | #F1F5F9 | Input fields, subtle sections |
| `Gray200` | #E2E8F0 | Borders, dividers |

### Text

| Token | Hex | Usage |
|-------|-----|-------|
| `Gray900` | #0F172A | Primary text |
| `Gray600` | #475569 | Secondary text |
| `Gray400` | #94A3B8 | Placeholders, hints |

### Semantic

| Token | Hex | Usage |
|-------|-----|-------|
| `Success` | #10B981 | Success states |
| `Error` | #EF4444 | Error states |

---

## Typography

| Style | Size | Weight | Usage |
|-------|------|--------|-------|
| `DisplayLarge` | 32sp | Bold | App name on sign-in |
| `HeadlineMedium` | 24sp | SemiBold | Screen titles |
| `TitleLarge` | 20sp | SemiBold | Section headers |
| `TitleMedium` | 16sp | SemiBold | Playlist titles |
| `BodyLarge` | 16sp | Regular | Primary body text |
| `BodyMedium` | 14sp | Regular | Secondary info |
| `LabelMedium` | 12sp | Medium | Buttons, tags |

**Font**: System default (Roboto)

---

## Shape System

| Element | Corner Radius |
|---------|---------------|
| Buttons | 12dp |
| Cards | 16dp |
| Input fields | 12dp |
| Small chips/tags | 8dp |
| Bottom sheets | 24dp (top only) |

**Spacing Scale**: 4, 8, 12, 16, 24, 32, 48dp

---

## Screen Designs

### 1. Loading Screen (Splash)

- **Background**: White with subtle cyan tint at top
- **Logo**: Centered, animated scale-in on appear
- **App name**: "Playster" in Gray900, DisplayLarge
- **Loading indicator**: Three dots pulsing in sequence (Cyan500)
- **Behavior**: 2 second check, routes to auth or playlist

### 2. Auth Screen (Sign In)

- **Background**: Full-screen gradient (Cyan500 top-left → Purple500 bottom-right)
- **Logo**: White, centered in upper 60%
- **App name**: "Playster" in white, DisplayLarge
- **Tagline**: "Your YouTube, organized" in white, BodyLarge
- **Button**: Single "Sign in with Google" button
  - White background
  - Google's official icon
  - 12dp corner radius
  - Subtle shadow
- **Footer**: "Terms · Privacy" links in white at 60% opacity
- **Simplification**: Remove email/password fields, remove multiple sign-in options

### 3. Playlist Screen (Main Content)

**Top Bar**:
- App name "Playster" on left (TitleLarge, Gray900)
- User avatar on right (circular, 40dp)

**Header Section**:
- "Your Playlists" (HeadlineMedium, Gray900)
- "X playlists" count below (BodyMedium, Gray600)
- 24dp top padding, 16dp horizontal padding

**Playlist List**:
- Vertical scrolling LazyColumn
- 12dp gap between cards
- 16dp horizontal padding

**Empty State**:
- Centered illustration/icon
- "No playlists yet" title
- "Your YouTube playlists will appear here" subtitle

### 4. PlayCard Component

**Layout**: Horizontal card
- Thumbnail on left: 80x80dp, 12dp corner radius
- Content stacked vertically on right

**Content**:
- Title: TitleMedium, Gray900, max 2 lines with ellipsis
- Subtitle: BodyMedium, Gray600 — "Channel name · X videos"

**Card Styling**:
- Background: Gray50
- Corner radius: 16dp
- Padding: 16dp
- Elevation: 2dp (or subtle border)

**Interaction**:
- Scale to 0.98 on press
- Ripple effect in Cyan500

---

## Logo Concept

A simple geometric mark representing "play" + "organization":
- Stylized play button made of stacked layers
- Or: Abstract "P" with play button integrated
- Colors: Cyan500 primary, Purple500 accent

(To be designed — use placeholder for now)

---

## Implementation Notes

1. Update `Color.kt` with new color tokens
2. Update `Theme.kt` to use new colors, light mode default
3. Update `Type.kt` with typography scale
4. Create new `Shape.kt` for corner radius tokens
5. Redesign `LoadingScreen.kt` with dot animation
6. Redesign `AuthScreen.kt` with gradient + single Google button
7. Redesign `PlaylistScreen.kt` with new header layout
8. Redesign `PlayCard.kt` with horizontal layout
