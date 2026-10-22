package com.realityweaver.wallpaper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Applies a wallpaper through the native Windows desktop API. */
public final class WallpaperService {
    private static final String WINDOWS_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            Add-Type -TypeDefinition @'
            using System.Runtime.InteropServices;
            public static class RealityWeaverWallpaperNative {
                [DllImport(\"user32.dll\", CharSet = CharSet.Unicode, SetLastError = true)]
                public static extern bool SystemParametersInfo(uint action, uint parameter, string imagePath, uint flags);
            }
            '@
            $imagePath = $env:REALITYWEAVER_WALLPAPER_PATH
            $applied = [RealityWeaverWallpaperNative]::SystemParametersInfo(20, 0, $imagePath, 3)
            if (-not $applied) {
                [Console]::Error.WriteLine('Windows could not apply the selected wallpaper.')
                exit 1
            }
            exit 0
            """;

    public void apply(Path imagePath) throws IOException {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) {
            throw new UnsupportedOperationException("Setting the desktop wallpaper is currently supported on Windows.");
        }

        Path normalized = imagePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("The selected wallpaper file could not be found.");
        }

        ProcessBuilder processBuilder = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                WINDOWS_SCRIPT);
        processBuilder.environment().put("REALITYWEAVER_WALLPAPER_PATH", normalized.toString());
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException(output.isBlank()
                        ? "Windows could not apply the selected wallpaper."
                        : output);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Wallpaper application was interrupted.", interrupted);
        }
    }
}
