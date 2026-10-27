# RealityWeaver

RealityWeaver is a Java 21 and JavaFX desktop studio for finding wallpapers, keeping a local image library, making named collections, and changing the desktop background automatically.

## Open the application

On Windows, the portable bundle is produced by the developer packaging script:

```powershell
.\package-windows.ps1
```

The bundled application is written to `target\dist\RealityWeaver\RealityWeaver.exe`. Copy the complete `RealityWeaver` folder to a location you control, then double-click `RealityWeaver.exe`. It includes a Java runtime and does not open a console window. The app image is portable; it does not create an installer or shortcuts.

To create an installer with Start Menu and desktop shortcuts, run `.\package-windows.ps1 -PackageType exe` or use `msi`. This requires JDK 21 and WiX Toolset 3.x (`candle.exe` and `light.exe`) on the build machine. The script builds the app and runs the Maven tests before packaging. `jpackage` creates the Windows package on Windows; Linux and macOS bundles must be built on their respective platforms and have not been verified here.

## First launch and everyday use

The first launch opens a short setup wizard. Choose a starting wallpaper topic and image fit, configure optional weather or calendar access, or skip setup. RealityWeaver opens a local workspace without requiring an account. Sign-in and registration remain available from the profile control.

The Home screen links to wallpaper discovery, your library, collections, and automation. In the wallpaper studio:

- **Discover** searches supported public image libraries, shows image previews and available creator/license/source details, and supports loading more results.
- **My Wallpapers** imports local images, previews them, organizes favorites by category, and shows recently opened wallpapers.
- **Collections** creates, renames, and deletes named collections; adds or removes images; marks favorites; shuffles and cycles through a collection; and imports or exports JSON manifests and ZIP archives.
- **Automation** manages daily time rules, weather rules, local `.ics` calendar rules, and the Windows Classic Outlook calendar where available. Automation runs while RealityWeaver is running, including in the system tray where the desktop supports it.
- **Settings** controls desktop image fit, shows where local data is stored, opens that folder, and can add or remove a Windows sign-in shortcut in the packaged app. Optional integration details are listed there too.

Choose **Set as Wallpaper** to apply an image. Windows, macOS, and GNOME-based Linux have platform handlers. Fit options are supported on Windows and GNOME; macOS uses its desktop scaling behavior. Other Linux desktop environments and operating systems report that automatic application is unsupported.

Weather checks use Open-Meteo. A weather rule can use a city or, with explicit consent, an approximate location from the internet connection (ipapi.co). Calendar access is optional: select a local `.ics` file, or enable the default calendar from Classic Outlook on Windows if it is installed. If an optional source is unavailable, the rest of the app remains usable.

## Saved data

Collections, world drafts, local account records, downloaded wallpapers, and imported archive images are stored under `%USERPROFILE%\.realityweaver`. Applied Windows-compatible wallpaper copies and generated world-memory wallpapers are stored in the user’s `Pictures\RealityWeaver` folder. Preferences and automation rules are stored in the current user’s Java preferences. World drafts keep the photo’s original local path; imported source images stay where they were selected. Removing a photo from a collection does not delete its original file. Export a ZIP with images to share a self-contained collection. Keep the complete data folders when upgrading or moving the app.

The app must be running for time, weather, calendar, and collection-cycle automation to execute. Closing from the tray menu exits the app; closing the main window hides it when the system tray is available. Saved rules resume when the app next starts. On the packaged Windows app, you can optionally start RealityWeaver when you sign in.

## Developer commands

Requirements: JDK 21 and network access to Maven Central the first time dependencies are needed.

```powershell
.\run.ps1              # open the JavaFX app
.\run.ps1 test         # run the test suite
.\run.ps1 clean package
.\package-windows.ps1  # build tests and a Windows app image with bundled Java
```

The tests cover world-draft persistence and recovery, local library favorites/categories/recent items, collection persistence and duplicate handling, collection import/export validation, shuffle history, automation repeat prevention, and search response failures.

## Current scope and limits

- Wallpaper search and downloads require an internet connection and depend on the selected public provider.
- Weather and calendar rules are optional and depend on their source being reachable or available.
- Collection cycle and automation schedules run while the app is open; this is not a Windows background service or a login-startup task.
- The Windows portable app image is the current packaging target. A native installer needs WiX 3.x. macOS and Linux application bundles are not included in this Windows build.
- World names, places, characters, story ideas, memory-photo paths, stories, quotes, and custom screen ratios are saved locally in `worlds.json`.
