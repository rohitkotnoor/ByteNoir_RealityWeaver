package com.realityweaver.wallpaper;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Applies wallpapers through the native desktop APIs supported by each platform adapter. */
public final class WallpaperService {
    private static final long MAX_IMAGE_BYTES = 500L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 150_000_000L;
    private static final String FIT_PREFERENCE = "wallpaper.fit";
    private static final String RECENT_APPLIED_COUNT = "wallpaper.recentApplied.count";
    private static final int MAX_RECENT_APPLIED = 8;
    private final Preferences preferences = Preferences.userNodeForPackage(WallpaperService.class);

    public enum WallpaperFit {
        FILL("Fill", "10"), FIT("Fit", "6"), STRETCH("Stretch", "2"), CENTER("Center", "0");

        private final String label;
        private final String windowsStyle;

        WallpaperFit(String label, String windowsStyle) {
            this.label = label;
            this.windowsStyle = windowsStyle;
        }

        public String windowsStyle() {
            return windowsStyle;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final String WINDOWS_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            Add-Type -TypeDefinition @'
            using System.Runtime.InteropServices;
            using System.Text;
            public static class RealityWeaverWallpaperNative {
                [DllImport("user32.dll", EntryPoint = "SystemParametersInfoW", CharSet = CharSet.Unicode, SetLastError = true)]
                [return: MarshalAs(UnmanagedType.Bool)]
                public static extern bool SetDesktopWallpaper(uint action, uint parameter, string imagePath, uint flags);

                [DllImport("user32.dll", EntryPoint = "SystemParametersInfoW", CharSet = CharSet.Unicode, SetLastError = true)]
                [return: MarshalAs(UnmanagedType.Bool)]
                public static extern bool GetDesktopWallpaper(uint action, uint bufferSize, StringBuilder imagePath, uint flags);
            }
            '@
            $desktopSettings = 'HKCU:\\Control Panel\\Desktop'
            Set-ItemProperty -Path $desktopSettings -Name WallpaperStyle -Value $env:REALITYWEAVER_WALLPAPER_STYLE
            Set-ItemProperty -Path $desktopSettings -Name TileWallpaper -Value '0'
            $imagePath = $env:REALITYWEAVER_WALLPAPER_PATH
            $applied = [RealityWeaverWallpaperNative]::SetDesktopWallpaper(0x0014, 0, $imagePath, 0x0003)
            if (-not $applied) {
                $errorCode = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
                [Console]::Error.WriteLine("Windows could not apply the selected wallpaper (error $errorCode).")
                exit 1
            }

            $currentWallpaper = [System.Text.StringBuilder]::new(260)
            $readBack = [RealityWeaverWallpaperNative]::GetDesktopWallpaper(
                0x0073, [uint32]$currentWallpaper.Capacity, $currentWallpaper, 0)
            if (-not $readBack -or -not [String]::Equals(
                    $currentWallpaper.ToString(), $imagePath, [System.StringComparison]::OrdinalIgnoreCase)) {
                [Console]::Error.WriteLine('Windows did not confirm the requested wallpaper path.')
                exit 1
            }
            exit 0
            """;

    public void apply(Path imagePath) throws IOException {
        apply(imagePath, preferredFit());
    }

    public WallpaperFit preferredFit() {
        try {
            return WallpaperFit.valueOf(preferences.get(FIT_PREFERENCE, WallpaperFit.FILL.name()));
        } catch (IllegalArgumentException invalid) {
            return WallpaperFit.FILL;
        }
    }

    public void setPreferredFit(WallpaperFit fit) {
        if (fit != null) {
            preferences.put(FIT_PREFERENCE, fit.name());
        }
    }

    public List<Path> recentApplied() {
        int count = Math.min(preferences.getInt(RECENT_APPLIED_COUNT, 0), MAX_RECENT_APPLIED);
        List<Path> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String stored = preferences.get("wallpaper.recentApplied." + index, "");
            try {
                Path path = Path.of(URI.create(stored)).toAbsolutePath().normalize();
                if (Files.isRegularFile(path) && !result.contains(path)) {
                    result.add(path);
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore invalid or stale application history entries.
            }
        }
        return List.copyOf(result);
    }

    public void apply(Path imagePath, WallpaperFit fit) throws IOException {
        if (fit == null) {
            throw new IllegalArgumentException("Choose how the wallpaper should fit the desktop.");
        }

        Path normalized = imagePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("The selected wallpaper file could not be found.");
        }

        String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!operatingSystem.contains("windows")) {
            readAndValidate(normalized);
        }
        if (operatingSystem.contains("windows")) {
            applyWindows(normalized, fit);
        } else if (operatingSystem.contains("mac")) {
            applyMac(normalized);
        } else if (operatingSystem.contains("linux")) {
            applyLinux(normalized, fit);
        } else {
            throw new UnsupportedOperationException("Wallpaper application is not supported on this operating system.");
        }
        recordApplied(normalized);
    }

    private void applyWindows(Path source, WallpaperFit fit) throws IOException {
        Path windowsImage = createWindowsBitmap(source);
        String encodedScript = Base64.getEncoder().encodeToString(
                WINDOWS_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder processBuilder = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encodedScript);
        processBuilder.environment().put("REALITYWEAVER_WALLPAPER_PATH", windowsImage.toString());
        processBuilder.environment().put("REALITYWEAVER_WALLPAPER_STYLE", fit.windowsStyle());
        execute(processBuilder, "Windows could not apply the selected wallpaper.");
    }

    private void applyMac(Path image) throws IOException {
        String quotedPath = image.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"");
        String script = "tell application \"System Events\" to set picture of every desktop to POSIX file \""
                + quotedPath + "\"";
        execute(new ProcessBuilder("osascript", "-e", script),
                "macOS could not apply the selected wallpaper. Allow RealityWeaver in System Settings if prompted.");
    }

    private void applyLinux(Path image, WallpaperFit fit) throws IOException {
        String desktop = (System.getenv().getOrDefault("XDG_CURRENT_DESKTOP", "") + ":"
                + System.getenv().getOrDefault("XDG_SESSION_DESKTOP", "")).toLowerCase(Locale.ROOT);
        if (!desktop.contains("gnome") && !desktop.contains("ubuntu")) {
            throw new UnsupportedOperationException(
                    "Automatic wallpaper application is currently supported on GNOME-based Linux desktops.");
        }
        String pictureOptions = switch (fit) {
            case FILL -> "zoom";
            case FIT -> "scaled";
            case STRETCH -> "stretched";
            case CENTER -> "centered";
        };
        String uri = image.toUri().toASCIIString();
        execute(new ProcessBuilder("gsettings", "set", "org.gnome.desktop.background", "picture-uri", uri),
                "GNOME could not apply the selected wallpaper.");
        execute(new ProcessBuilder("gsettings", "set", "org.gnome.desktop.background", "picture-options",
                pictureOptions), "GNOME could not update the wallpaper fit setting.");
    }

    private void execute(ProcessBuilder builder, String defaultFailure) throws IOException {
        Path outputFile = Files.createTempFile("realityweaver-wallpaper-", ".log");
        try {
            builder.redirectErrorStream(true);
            builder.redirectOutput(outputFile.toFile());
            Process process = builder.start();
            boolean completed;
            try {
                completed = process.waitFor(45, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Wallpaper application was interrupted.", interrupted);
            }
            if (!completed) {
                process.destroyForcibly();
                throw new IOException("Wallpaper application took too long. Try again.");
            }
            String output = Files.size(outputFile) > 1_000_000 ? "" : Files.readString(outputFile).strip();
            if (process.exitValue() != 0) {
                throw new IOException(output.isBlank() ? defaultFailure : output);
            }
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private void recordApplied(Path path) {
        List<Path> recent = new ArrayList<>(recentApplied());
        recent.remove(path);
        recent.add(0, path);
        if (recent.size() > MAX_RECENT_APPLIED) {
            recent = new ArrayList<>(recent.subList(0, MAX_RECENT_APPLIED));
        }
        for (int index = 0; index < recent.size(); index++) {
            preferences.put("wallpaper.recentApplied." + index, recent.get(index).toUri().toString());
        }
        for (int index = recent.size(); index < MAX_RECENT_APPLIED; index++) {
            preferences.remove("wallpaper.recentApplied." + index);
        }
        preferences.putInt(RECENT_APPLIED_COUNT, recent.size());
    }

    private Path createWindowsBitmap(Path sourcePath) throws IOException {
        BufferedImage source = readAndValidate(sourcePath);

        BufferedImage bitmap = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D graphics = bitmap.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, bitmap.getWidth(), bitmap.getHeight());
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }

        Path outputDirectory = Path.of(System.getProperty("user.home"), "Pictures", "RealityWeaver",
                "Applied Wallpapers");
        Files.createDirectories(outputDirectory);
        String fileName = sourcePath.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        if (extension > 0) {
            fileName = fileName.substring(0, extension);
        }
        fileName = fileName.replaceAll("[\\\\/:*?\"<>|]", "-").strip();
        if (fileName.length() > 80) {
            fileName = fileName.substring(0, 80);
        }
        if (fileName.isBlank()) {
            fileName = "wallpaper";
        }

        Path bitmapPath = outputDirectory.resolve(fileName + "-windows.bmp");
        if (!ImageIO.write(bitmap, "bmp", bitmapPath.toFile())) {
            throw new IOException("The Windows-compatible wallpaper image could not be saved.");
        }
        return bitmapPath;
    }

    private BufferedImage readAndValidate(Path sourcePath) throws IOException {
        long size = Files.size(sourcePath);
        if (size <= 0 || size > MAX_IMAGE_BYTES) {
            throw new IOException("Choose a supported image smaller than 500 MB.");
        }
        try (InputStream input = Files.newInputStream(sourcePath);
                ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
            if (imageInput == null) {
                throw new IOException("Windows could not read the selected wallpaper image.");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new IOException("Choose a supported PNG, JPEG, GIF, or BMP wallpaper image.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_IMAGE_PIXELS) {
                    throw new IOException("The wallpaper image dimensions are too large to apply safely.");
                }
                BufferedImage source = reader.read(0);
                if (source == null) {
                    throw new IOException("Windows could not decode the selected wallpaper image.");
                }
                return source;
            } finally {
                reader.dispose();
            }
        }
    }
}
