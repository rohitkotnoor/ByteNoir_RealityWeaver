package com.realityweaver.wallpaper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import org.junit.jupiter.api.Test;

final class WallpaperSearchServiceTest {
    @Test
    void reportsAnExternalProviderHttpFailureClearly() {
        IOException failure = assertThrows(IOException.class,
                () -> WallpaperSearchService.parseJsonResponse(503, "service unavailable", "Openverse"));

        assertEquals("Openverse returned HTTP 503.", failure.getMessage());
    }

    @Test
    void rejectsMalformedProviderResponses() {
        IOException failure = assertThrows(IOException.class,
                () -> WallpaperSearchService.parseJsonResponse(200, "not-json", "Wallhaven"));

        assertEquals("Wallhaven returned an unexpected search response.", failure.getMessage());
    }
}
