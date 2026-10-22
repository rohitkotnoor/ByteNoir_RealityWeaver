package com.realityweaver.wallpaper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Searches image catalogs and downloads selected images for the local wallpaper library. */
public final class WallpaperSearchService {
    private static final String COMMONS_API = "https://commons.wikimedia.org/w/api.php";
    private static final String OPENVERSE_API = "https://api.openverse.org/v1/images/";
    private static final String WALLHAVEN_API = "https://wallhaven.cc/api/v1/search";
    private static final String USER_AGENT = "RealityWeaver/1.0 (JavaFX wallpaper discovery)";
    private static final long MAX_IMAGE_BYTES = 100L * 1024 * 1024;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public List<OnlineWallpaper> search(String query, WallpaperSource source)
            throws IOException, InterruptedException {
        return switch (source) {
            case OPENVERSE -> searchOpenverse(query);
            case WIKIMEDIA_COMMONS -> searchCommons(query);
            case WALLHAVEN -> searchWallhaven(query, false);
        };
    }

    public List<OnlineWallpaper> trending() throws IOException, InterruptedException {
        return searchWallhaven("", true);
    }

    private List<OnlineWallpaper> searchCommons(String query) throws IOException, InterruptedException {
        String search = "filetype:bitmap " + query.strip();
        String encoded = URLEncoder.encode(search, StandardCharsets.UTF_8);
        URI requestUri = URI.create(COMMONS_API
                + "?action=query&generator=search&gsrnamespace=6&gsrlimit=24&gsrsearch=" + encoded
                + "&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata"
                + "&iiextmetadatafilter=Artist%7CLicenseShortName%7CLicense"
                + "&iiurlwidth=720&format=json&formatversion=2");

        HttpRequest request = HttpRequest.newBuilder(requestUri)
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Wikimedia Commons returned HTTP " + response.statusCode() + ".");
        }

        try {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (root.has("error")) {
                String message = stringValue(root.getAsJsonObject("error"), "info");
                throw new IOException("Wikimedia Commons search failed: " + message);
            }

            JsonObject queryObject = root.getAsJsonObject("query");
            if (queryObject == null || !queryObject.has("pages") || !queryObject.get("pages").isJsonArray()) {
                return List.of();
            }

            List<OnlineWallpaper> results = new ArrayList<>();
            JsonArray pages = queryObject.getAsJsonArray("pages");
            for (JsonElement pageElement : pages) {
                JsonObject page = pageElement.getAsJsonObject();
                if (!page.has("imageinfo") || !page.get("imageinfo").isJsonArray()) {
                    continue;
                }
                JsonArray imageInfoArray = page.getAsJsonArray("imageinfo");
                if (imageInfoArray.isEmpty()) {
                    continue;
                }

                JsonObject info = imageInfoArray.get(0).getAsJsonObject();
                String fullUrl = stringValue(info, "url");
                String thumbUrl = stringValue(info, "thumburl");
                String sourceUrl = stringValue(info, "descriptionurl");
                String mimeType = stringValue(info, "mime");
                if (fullUrl.isBlank() || sourceUrl.isBlank() || !mimeType.startsWith("image/")) {
                    continue;
                }
                if (thumbUrl.isBlank()) {
                    thumbUrl = fullUrl;
                }

                JsonObject metadata = info.has("extmetadata") && info.get("extmetadata").isJsonObject()
                        ? info.getAsJsonObject("extmetadata")
                        : new JsonObject();
                String rawTitle = stringValue(page, "title");
                String title = rawTitle.startsWith("File:") ? rawTitle.substring("File:".length()) : rawTitle;

                results.add(new OnlineWallpaper(
                        title,
                        URI.create(thumbUrl),
                        URI.create(fullUrl),
                        URI.create(sourceUrl),
                        metadataValue(metadata, "Artist"),
                        firstNonBlank(metadataValue(metadata, "LicenseShortName"), metadataValue(metadata, "License")),
                        mimeType,
                        integerValue(info, "width"),
                        integerValue(info, "height"),
                        "Wikimedia Commons"));
            }
            return List.copyOf(results);
        } catch (RuntimeException parseFailure) {
            throw new IOException("Wikimedia Commons returned an unexpected search response.", parseFailure);
        }
    }

    private List<OnlineWallpaper> searchOpenverse(String query) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(query.strip(), StandardCharsets.UTF_8);
        URI requestUri = URI.create(OPENVERSE_API + "?q=" + encoded
                + "&page_size=24&extension=jpg%2Cjpeg%2Cpng%2Cgif%2Cbmp");
        JsonObject root = getJson(requestUri, "Openverse");
        JsonArray items = root.has("results") && root.get("results").isJsonArray()
                ? root.getAsJsonArray("results")
                : new JsonArray();

        List<OnlineWallpaper> results = new ArrayList<>();
        for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            String fullUrl = stringValue(item, "url");
            String thumbnailUrl = stringValue(item, "thumbnail");
            String sourceUrl = stringValue(item, "foreign_landing_url");
            String mimeType = imageMimeType(stringValue(item, "filetype"));
            if (fullUrl.isBlank() || sourceUrl.isBlank() || !mimeType.startsWith("image/")) {
                continue;
            }
            if (thumbnailUrl.isBlank()) {
                thumbnailUrl = fullUrl;
            }

            String provider = stringValue(item, "source");
            String sourceName = provider.isBlank() ? "Openverse" : "Openverse · " + provider;
            results.add(new OnlineWallpaper(
                    stringValue(item, "title"),
                    URI.create(thumbnailUrl),
                    URI.create(fullUrl),
                    URI.create(sourceUrl),
                    stringValue(item, "creator"),
                    firstNonBlank(stringValue(item, "license"), stringValue(item, "license_version")),
                    mimeType,
                    integerValue(item, "width"),
                    integerValue(item, "height"),
                    sourceName));
        }
        return List.copyOf(results);
    }

    private List<OnlineWallpaper> searchWallhaven(String query, boolean trending)
            throws IOException, InterruptedException {
        StringBuilder requestUrl = new StringBuilder(WALLHAVEN_API)
                .append("?categories=100&purity=100&sorting=")
                .append(trending ? "toplist" : "relevance")
                .append("&order=desc&atleast=1920x1080&page=1");
        if (trending) {
            requestUrl.append("&topRange=1M");
        }
        if (!query.isBlank()) {
            requestUrl.append("&q=").append(URLEncoder.encode(query.strip(), StandardCharsets.UTF_8));
        }

        JsonObject root = getJson(URI.create(requestUrl.toString()), "Wallhaven");
        JsonArray items = root.has("data") && root.get("data").isJsonArray()
                ? root.getAsJsonArray("data")
                : new JsonArray();

        List<OnlineWallpaper> results = new ArrayList<>();
        for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            String id = stringValue(item, "id");
            String fullUrl = stringValue(item, "path");
            String sourceUrl = stringValue(item, "url");
            String thumbnailUrl = "";
            if (item.has("thumbs") && item.get("thumbs").isJsonObject()) {
                JsonObject thumbs = item.getAsJsonObject("thumbs");
                thumbnailUrl = firstNonBlank(stringValue(thumbs, "large"), stringValue(thumbs, "original"));
            }
            if (id.isBlank() || fullUrl.isBlank() || sourceUrl.isBlank()) {
                continue;
            }
            if (thumbnailUrl.isBlank()) {
                thumbnailUrl = fullUrl;
            }

            JsonArray tags = item.has("tags") && item.get("tags").isJsonArray()
                    ? item.getAsJsonArray("tags")
                    : new JsonArray();
            String title = tags.asList().stream()
                    .limit(3)
                    .map(tag -> stringValue(tag.getAsJsonObject(), "name"))
                    .filter(tag -> !tag.isBlank())
                    .reduce((left, right) -> left + " · " + right)
                    .orElse("Wallhaven wallpaper " + id);

            JsonObject uploader = item.has("uploader") && item.get("uploader").isJsonObject()
                    ? item.getAsJsonObject("uploader")
                    : new JsonObject();
            results.add(new OnlineWallpaper(
                    title,
                    URI.create(thumbnailUrl),
                    URI.create(fullUrl),
                    URI.create(sourceUrl),
                    stringValue(uploader, "username"),
                    "",
                    stringValue(item, "file_type"),
                    integerValue(item, "dimension_x"),
                    integerValue(item, "dimension_y"),
                    trending ? "Wallhaven · monthly toplist" : "Wallhaven"));
        }
        return List.copyOf(results);
    }

    private JsonObject getJson(URI uri, String source) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(source + " returned HTTP " + response.statusCode() + ".");
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException parseFailure) {
            throw new IOException(source + " returned an unexpected search response.", parseFailure);
        }
    }

    private String imageMimeType(String fileType) {
        String type = fileType.toLowerCase(java.util.Locale.ROOT);
        return switch (type) {
            case "jpg", "jpeg", "image/jpeg" -> "image/jpeg";
            case "png", "image/png" -> "image/png";
            case "gif", "image/gif" -> "image/gif";
            case "bmp", "image/bmp" -> "image/bmp";
            default -> type.startsWith("image/") ? type : "";
        };
    }

    public byte[] fetchThumbnail(OnlineWallpaper wallpaper) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(requireHttpsUri(wallpaper.thumbnailUri()))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("The preview image could not be downloaded (HTTP " + response.statusCode() + ").");
        }
        if (response.body().length > 8 * 1024 * 1024) {
            throw new IOException("The preview image is larger than expected.");
        }
        return response.body();
    }

    public void download(OnlineWallpaper wallpaper, Path destination) throws IOException, InterruptedException {
        Path target = destination.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Choose a valid destination for the downloaded image.");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".realityweaver-wallpaper-", ".part");

        try {
            HttpRequest request = HttpRequest.newBuilder(requireHttpsUri(wallpaper.fileUri()))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IOException("The image download failed (HTTP " + response.statusCode() + ").");
            }

            long total = 0;
            try (InputStream input = response.body(); var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_IMAGE_BYTES) {
                        throw new IOException("This image is larger than the 100 MB download limit.");
                    }
                    output.write(buffer, 0, read);
                }
            }

            if (total == 0) {
                throw new IOException("The downloaded image was empty.");
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private URI requireHttpsUri(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IOException("The image source returned an unsupported image address.");
        }
        return uri;
    }

    private String metadataValue(JsonObject metadata, String key) {
        if (!metadata.has(key) || !metadata.get(key).isJsonObject()) {
            return "";
        }
        return plainText(stringValue(metadata.getAsJsonObject(key), "value"));
    }

    private String plainText(String value) {
        return value.replaceAll("(?s)<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private String firstNonBlank(String first, String second) {
        return first.isBlank() ? second : first;
    }

    private String stringValue(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString();
    }

    private int integerValue(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : 0;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
