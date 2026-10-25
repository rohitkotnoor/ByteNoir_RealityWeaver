# RealityWeaver

RealityWeaver is a JavaFX desktop studio for collecting the places, people, and ideas that make an imagined world feel real. It includes local sign-in and registration, a world-building dashboard, and a wallpaper studio for importing local images, searching open image catalogs and wallpaper communities, previewing and favoriting images, and applying a selected image as the Windows desktop background.

## Requirements

- JDK 21 or newer
- Internet access on the first run, so the Maven Wrapper can download Maven and project dependencies

JavaFX 21.0.12 is used to match the installed Java 21 runtime. The included Maven Wrapper pins Apache Maven 3.9.16, so a separate Maven installation is not required.

## Build and run

From the project root, compile and package the application in PowerShell:

```powershell
.\mvnw.cmd clean package
```

Launch the desktop application with the JavaFX Maven plugin:

```powershell
.\mvnw.cmd javafx:run
```

On macOS or Linux, use `./mvnw` in place of `mvnw.cmd`. On first use, the wrapper downloads Maven and Maven fetches the project dependencies and plugins. The first launch opens account registration; later launches open sign-in. Local accounts are stored in `~/.realityweaver/accounts.json` with salted PBKDF2-HMAC-SHA256 password verifiers. This app does not connect to an online identity provider or provide password recovery or account sync. In the app, open **Wallpapers → Find online**, choose Openverse, Wikimedia Commons, or Wallhaven, then search for a destination or topic. **Trending** shows Wallhaven's safe-for-work monthly toplist. Select a result to preview it, then choose **Download to library** to save a copy. Search requires an internet connection. Check each linked source page for reuse terms and attribution; the app keeps the displayed creator, license, and source link with downloaded entries. Wallpaper references and favorites are saved in local user preferences; imported source images stay where they are. Applying a wallpaper is currently supported on Windows. Created world drafts currently live in memory for the duration of the application session.

## Project layout

| File | Purpose |
| --- | --- |
| `pom.xml` | Declares the Java 21 build, JavaFX and Gson dependencies, compiler settings, and `javafx:run` launcher. |
| `mvnw` / `mvnw.cmd` | Launches the project with the pinned Maven version without requiring Maven to be installed globally. |
| `.mvn/wrapper/maven-wrapper.properties` | Pins the Maven Wrapper version and Apache Maven distribution. |
| `src/main/java/module-info.java` | Declares the named Java module and its JavaFX, HTTP client, desktop, preferences, and Gson dependencies. |
| `src/main/java/com/realityweaver/RealityWeaverApplication.java` | JavaFX entry point; creates and shows the main window. |
| `src/main/java/com/realityweaver/auth/AuthService.java` | Registers local accounts and verifies salted password hashes. |
| `src/main/java/com/realityweaver/auth/LocalAccount.java` | Holds the non-secret name and email for a signed-in account. |
| `src/main/java/com/realityweaver/ui/AuthPageSupport.java` | Shares the layout and styling helpers used by the authentication pages. |
| `src/main/java/com/realityweaver/ui/LoginView.java` | Provides the local sign-in page and navigation to registration. |
| `src/main/java/com/realityweaver/ui/RegisterView.java` | Provides the local registration page and navigation to sign-in. |
| `src/main/java/com/realityweaver/ui/DashboardView.java` | Builds the authenticated dashboard, empty state, starter prompts, and create-world interaction. |
| `src/main/java/com/realityweaver/ui/WallpaperStudioView.java` | Provides the library and online search tabs, wallpaper preview, favorites filter, and desktop apply interface. |
| `src/main/java/com/realityweaver/ui/OnlineWallpaperView.java` | Searches multiple catalogs, previews selected results, shows creator/license details, and saves images into the library. |
| `src/main/java/com/realityweaver/wallpaper/OnlineWallpaper.java` | Represents an image result with its source and reuse information. |
| `src/main/java/com/realityweaver/wallpaper/WallpaperSearchService.java` | Calls Openverse, Commons, and Wallhaven APIs and downloads selected image files. |
| `src/main/java/com/realityweaver/wallpaper/WallpaperSource.java` | Lists the catalogs available in the online wallpaper browser. |
| `src/main/java/com/realityweaver/wallpaper/WallpaperLibrary.java` | Persists local wallpaper paths, favorite flags, and saved source attribution using Java Preferences. |
| `src/main/java/com/realityweaver/wallpaper/WallpaperService.java` | Applies a selected image as the Windows desktop wallpaper. |
| `src/main/resources/styles/app.css` | Defines the dark, purple-accented interface styling. |
| `.gitignore` | Excludes Maven build output and common IDE files from version control. |

