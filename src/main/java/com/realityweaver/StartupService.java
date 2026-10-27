package com.realityweaver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Manages the optional Windows sign-in shortcut for a packaged application. */
public final class StartupService {
    private static final String STARTUP_LINK_NAME = "RealityWeaver (auto-start).lnk";
    private static final String CREATE_SHORTCUT_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            try {
                $shell = New-Object -ComObject WScript.Shell
                $shortcut = $shell.CreateShortcut($env:REALITYWEAVER_STARTUP_LINK)
                $shortcut.TargetPath = $env:REALITYWEAVER_STARTUP_TARGET
                $shortcut.WorkingDirectory = [System.IO.Path]::GetDirectoryName($shortcut.TargetPath)
                $shortcut.IconLocation = $shortcut.TargetPath + ',0'
                $shortcut.Save()
            } catch {
                [Console]::Error.WriteLine($_.Exception.Message)
                exit 1
            }
            """;

    public boolean isSupported() {
        return startupTarget() != null && startupFolder() != null;
    }

    public boolean isEnabled() {
        Path link = startupLink();
        return link != null && Files.isRegularFile(link);
    }

    public void setEnabled(boolean enabled) throws IOException {
        if (!isSupported()) {
            throw new UnsupportedOperationException(
                    "Automatic sign-in launch is available for the packaged Windows app.");
        }
        Path link = startupLink();
        if (!enabled) {
            Files.deleteIfExists(link);
            return;
        }

        Files.createDirectories(link.getParent());
        String encodedScript = Base64.getEncoder().encodeToString(
                CREATE_SHORTCUT_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-EncodedCommand", encodedScript);
        builder.environment().put("REALITYWEAVER_STARTUP_LINK", link.toString());
        builder.environment().put("REALITYWEAVER_STARTUP_TARGET", startupTarget().toString());
        execute(builder);
        if (!Files.isRegularFile(link)) {
            throw new IOException("Windows did not create the RealityWeaver sign-in shortcut.");
        }
    }

    public Path dataFolder() {
        return Path.of(System.getProperty("user.home"), ".realityweaver").toAbsolutePath().normalize();
    }

    private Path startupTarget() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return null;
        }
        return ProcessHandle.current().info().command()
                .map(Path::of)
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equalsIgnoreCase("RealityWeaver.exe"))
                .map(path -> path.toAbsolutePath().normalize())
                .orElse(null);
    }

    private Path startupFolder() {
        String applicationData = System.getenv("APPDATA");
        if (applicationData == null || applicationData.isBlank()) {
            return null;
        }
        return Path.of(applicationData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup")
                .toAbsolutePath().normalize();
    }

    private Path startupLink() {
        Path folder = startupFolder();
        return folder == null ? null : folder.resolve(STARTUP_LINK_NAME);
    }

    private void execute(ProcessBuilder builder) throws IOException {
        Path outputFile = Files.createTempFile("realityweaver-startup-", ".log");
        try {
            builder.redirectErrorStream(true);
            builder.redirectOutput(outputFile.toFile());
            Process process = builder.start();
            boolean completed;
            try {
                completed = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Windows sign-in shortcut setup was interrupted.", interrupted);
            }
            if (!completed) {
                process.destroyForcibly();
                throw new IOException("Windows sign-in shortcut setup took too long.");
            }
            String output = Files.size(outputFile) > 1_000_000 ? "" : Files.readString(outputFile).strip();
            if (process.exitValue() != 0) {
                throw new IOException(output.isBlank()
                        ? "Windows could not create the sign-in shortcut." : output);
            }
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }
}
