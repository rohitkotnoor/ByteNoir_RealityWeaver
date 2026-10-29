# RealityWeaver — Smart Desktop Wallpaper Studio

RealityWeaver is a smart desktop wallpaper manager and creative environment built with **Java 21** and **JavaFX**. It combines multi-provider wallpaper discovery, direct desktop application, wallpaper vaults (collections), dynamic atmospheric time/weather/calendar automation, and background system-tray execution.

---

## Quickstart & Launch Commands

### Running the Application

Launch RealityWeaver in development mode with a single command:

```powershell
.\run.ps1
```

Or using Maven wrapper directly:

```powershell
.\mvnw.cmd javafx:run
```

### Running the Test Suite

Execute the full automated test suite (35 unit tests covering persistence, discovery, caching, rules, and collections):

```powershell
.\mvnw.cmd test
```

### Building the Native Windows Package

Create a self-contained portable Windows package with bundled Java runtime under `target\dist\RealityWeaver\RealityWeaver.exe`:

```powershell
.\package-windows.ps1
```

---

## Softablitz Requirements Implementation

### B1. The Multiversal Portal — Discover & Explore Wallpapers

RealityWeaver provides external wallpaper discovery across 5 providers with a provider abstraction:

- **Providers Supported**:
  - **Openverse**: Millions of Creative Commons and public domain photos.
  - **Wikimedia Commons**: High-resolution encyclopedic photography and art.
  - **Wallhaven**: Curated desktop wallpapers (including 4K/8K, anime, and toplists).
  - **NASA Images**: Astronomical and space exploration imagery from NASA's archives.
  - **Art Institute of Chicago**: Classical fine art and paintings in the public domain.
- **Search & Filtering**: Search by keyword with curated category pills (Anime, Nature, Cyberpunk, Space, Architecture, Minimal, Fantasy).
- **High-Resolution Thumbnails & On-Disk Caching**: Thumbnails are fetched and cached in `~/.realityweaver/thumbnail-cache/` with SHA-256 keys to conserve bandwidth and speed up browsing. The cache size can be inspected and cleared from Settings.
- **Detailed Previews & Attribution**: Each wallpaper card offers a high-res inspection dialog detailing dimensions, author/photographer, license, source provider, and clickable links to the original landing page.
- **Offline Resilience**: When disconnected from the internet, local libraries and saved collections remain accessible and functional.

### B2. Reality Manifestation — Apply Wallpapers Directly to the Desktop

Wallpapers are applied directly to the operating system's desktop background without external scripts:

- **Windows Native Application**: Uses Win32 `SystemParametersInfoW` (`SPI_SETDESKWALLPAPER = 0x0014`) via safe non-blocking PowerShell execution with flags `0x0003` (`SPIF_UPDATEINIFILE | SPIF_SENDCHANGE`).
- **Active Wallpaper Detection**: Queries the current desktop wallpaper from Windows (`GetDesktopWallpaper` 0x0073) and displays it inside the application dashboard in a desktop monitor mockup.
- **Display Modes (Fit / Scaling)**: Configurable scaling modes:
  - **Fill** (crop to screen aspect ratio)
  - **Fit** (letterbox to maintain full image)
  - **Stretch** (stretch to full resolution)
  - **Center** (1:1 pixel rendering at screen center)
- **Image Safety Validation**: Checks file validity, format (PNG, JPG, BMP, GIF), and dimensions before applying.
- **Cross-Platform Abstraction**: Isolated behind `WallpaperService`, with platform support for Windows, macOS (`osascript`), and Linux GNOME (`gsettings`).

### B3. The Sanctum of Memories — Collections & Wallpaper Vaults

Users can organize, manage, and cycle named wallpaper vaults:

- **Collection Management**: Create, rename, delete vaults with safety confirmations.
- **Adding & Removing**: Add external discovery images or import local files. Removing an image from a collection only removes the reference; original local files are never deleted.
- **Local Multi-File Import**: Direct multi-file picker to import folders of images into collections.
- **Automated Slideshow / Cycle**: Configurable rotation intervals (1 min, 5 min, 15 min, 30 min, 1 hour, 2 hours, 6 hours, 12 hours, 24 hours).
- **Shuffling with History**: Fisher-Yates shuffle algorithm with history tracking ensures all wallpapers cycle before repeating.
- **Portability (Export / Import)**: Export collections as portable `.rwcollection` JSON manifests or full self-contained ZIP archives with images.
- **Durable Local Persistence**: Collections are persisted as JSON files under `~/.realityweaver/collections/`.

### A1. The Eye of Agamotto — Time- and Weather-Aware Automation

A rule-based automation engine that reacts dynamically to local clock and atmospheric weather:

- **Local Time Rules**: Schedule specific wallpapers or collections for exact times of day (e.g. 06:00 Morning, 18:00 Evening, 21:00 Night).
- **Atmospheric Weather Rules (Open-Meteo)**: Real-time weather fetched every 15 minutes from Open-Meteo (Clear, Cloudy, Rain, Snow, Fog, Storm). No API key required.
- **Geolocation**: Supports either automatic location estimation (via internet IP) or explicit city name input.
- **Per-Rule Toggles**: Every rule can be enabled or disabled via checkboxes without having to delete and re-create it.
- **Collection Mapping**: Rules can map to a specific wallpaper or a collection vault to rotate through.
- **Manual Override & Resumption**: When a user manually sets a wallpaper, automation pauses for 1 hour. A **Resume Automation** button allows users to immediately return to automatic adaptation.
- **Predictable Precedence**: Rules evaluate with strict deterministic hierarchy:
  1. *Calendar Meeting Event* (highest priority while meeting is active)
  2. *Live Weather Condition*
  3. *Local Time of Day*
- **Repeat Prevention**: Prevents unnecessary disk writes or OS desktop re-renders when nothing relevant has changed.

### A2. The Time Stone — Calendar Automation & System-Tray Operation

Connects wallpaper ambiance to the user's schedule and operates continuously in the background:

- **Remote Calendar Feeds (Google Calendar / iCal / WebCal)**: Paste any remote calendar iCal secret address (e.g. `https://calendar.google.com/calendar/ical/.../basic.ics` or `webcal://...`) to automatically sync events.
- **Local `.ics` & Classic Outlook**: Supports local `.ics` calendar files and Classic Outlook via Windows COM.
- **Keyword Matching**: Apply dedicated wallpapers or vaults when event titles match keywords (e.g. "meeting", "focus", "standup").
- **System-Tray Presence**:
  - RealityWeaver installs a native system tray icon with a cosmic crest.
  - Closing the main window minimizes RealityWeaver to the system tray (`stage.hide()`) so automation continues uninterrupted.
  - Tray popup menu provides:
    - *Open RealityWeaver*
    - *Trigger Next / Check Rules Now*
    - *Resume Automation*
    - *Toggle wallpaper automation*
    - *Exit RealityWeaver*

---

## Architecture & Storage

All user data and configuration persist locally on the user's computer:

| Component | Storage Path | Description |
|-----------|--------------|-------------|
| **Preferences & Rules** | `~/.realityweaver/preferences.json` | Cross-platform JSON-backed preferences engine (`FilePreferences`) eliminating registry permissions issues. |
| **Wallpaper Vaults** | `~/.realityweaver/collections/*.json` | Named collection definitions and image references. |
| **Downloaded Wallpapers** | `~/.realityweaver/wallpapers/` | Full-resolution wallpapers downloaded from discovery sources. |
| **Thumbnail Cache** | `~/.realityweaver/thumbnail-cache/` | SHA-256 hashed thumbnail preview cache. |
| **Applied Copies** | `Pictures/RealityWeaver/` | Formatted wallpaper files for native Windows desktop application. |
| **World Drafts & Lore** | `~/.realityweaver/worlds.json` | Creative worlds and world-building notes. |

---

## Optional Integrations & Keys

- **Open-Meteo**: Built-in; requires zero configuration and no API keys.
- **Google Calendar / iCal**: Paste your calendar's private/secret iCal address into the **Automation** tab.
- **Wallhaven API Key**: Optional. Enter in the **Settings** tab to unlock personalized saved collections and remove public search rate limits.
