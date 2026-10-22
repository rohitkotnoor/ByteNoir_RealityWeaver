package com.realityweaver.wallpaper;

import java.net.URI;
import java.util.Locale;

/** Image search result with source and reuse attribution. */
public record OnlineWallpaper(
        String title,
        URI thumbnailUri,
        URI fileUri,
        URI descriptionUri,
        String creator,
        String license,
        String mimeType,
        int width,
        int height,
        String sourceName) {

    public String suggestedFileName() {
        String safeTitle = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        String extension = extensionFromUri(fileUri);
        if (safeTitle.isBlank()) {
            safeTitle = "commons-wallpaper";
        }
        if (!safeTitle.matches("(?i).*\\.[a-z0-9]{2,5}$")) {
            safeTitle += "." + extension;
        }
        return safeTitle;
    }

    public String attribution() {
        StringBuilder text = new StringBuilder("Source: ").append(sourceName);
        if (!creator.isBlank()) {
            text.append(" · Creator: ").append(creator);
        }
        if (!license.isBlank()) {
            text.append(" · License: ").append(license);
        }
        text.append(" · File page: ").append(descriptionUri);
        return text.toString();
    }

    public boolean isJavaFxImage() {
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "image/png", "image/jpeg", "image/gif", "image/bmp" -> true;
            default -> false;
        };
    }

    private static String extensionFromUri(URI uri) {
        String path = uri.getPath();
        if (path != null) {
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < path.length()) {
                String extension = path.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (extension.matches("[a-z0-9]{2,5}")) {
                    return extension;
                }
            }
        }
        return "jpg";
    }
}
