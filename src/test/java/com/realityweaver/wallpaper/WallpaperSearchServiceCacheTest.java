package com.realityweaver.wallpaper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class WallpaperSearchServiceCacheTest {

    @Test
    void wallhavenApiKeySaveAndRetrieve() {
        String original = WallpaperSearchService.getWallhavenApiKey();
        try {
            WallpaperSearchService.setWallhavenApiKey("test-api-key-12345");
            assertEquals("test-api-key-12345", WallpaperSearchService.getWallhavenApiKey());

            WallpaperSearchService.setWallhavenApiKey("");
            assertEquals("", WallpaperSearchService.getWallhavenApiKey());
        } finally {
            WallpaperSearchService.setWallhavenApiKey(original);
        }
    }

    @Test
    void pexelsAndPixabayApiKeysSaveAndRetrieve() {
        String origPexels = WallpaperSearchService.getPexelsApiKey();
        String origPixabay = WallpaperSearchService.getPixabayApiKey();
        try {
            WallpaperSearchService.setPexelsApiKey("test-pexels-key");
            assertEquals("test-pexels-key", WallpaperSearchService.getPexelsApiKey());

            WallpaperSearchService.setPixabayApiKey("test-pixabay-key");
            assertEquals("test-pixabay-key", WallpaperSearchService.getPixabayApiKey());

            WallpaperSearchService.setPexelsApiKey("");
            assertEquals("", WallpaperSearchService.getPexelsApiKey());

            WallpaperSearchService.setPixabayApiKey("");
            assertEquals("", WallpaperSearchService.getPixabayApiKey());
        } finally {
            WallpaperSearchService.setPexelsApiKey(origPexels);
            WallpaperSearchService.setPixabayApiKey(origPixabay);
        }
    }

    @Test
    void thumbnailCacheMetrics() {
        long bytes = WallpaperSearchService.getThumbnailCacheSizeBytes();
        int count = WallpaperSearchService.getThumbnailCacheFileCount();
        assertTrue(bytes >= 0);
        assertTrue(count >= 0);

        int cleared = WallpaperSearchService.clearThumbnailCache();
        assertTrue(cleared >= 0);
    }
}
