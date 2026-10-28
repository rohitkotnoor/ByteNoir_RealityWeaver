package com.realityweaver.wallpaper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
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

    @Test
    void parsesNasaImagesWithAStableLargeDownloadAddressAndAttribution() {
        var root = JsonParser.parseString("""
                {"collection":{"items":[{
                  "data":[{"media_type":"image","nasa_id":"PIA 123","title":"A nebula",
                    "photographer":"NASA / ESA"}],
                  "links":[{"href":"https://images-assets.nasa.gov/image/PIA123/PIA123~thumb.jpg"}]
                }]}}
                """).getAsJsonObject();

        OnlineWallpaper result = WallpaperSearchService.parseNasaResponse(root).getFirst();

        assertEquals("A nebula", result.title());
        assertEquals("NASA / ESA", result.creator());
        assertEquals("NASA Images", result.sourceName());
        assertEquals("https://images-assets.nasa.gov/image/PIA%20123/PIA%20123~large.jpg",
                result.fileUri().toString());
        assertTrue(result.descriptionUri().toString().startsWith("https://images.nasa.gov/details/"));
    }

    @Test
    void keepsOnlyPublicDomainArtInstituteResultsWithIiifImages() {
        var root = JsonParser.parseString("""
                {"config":{"iiif_url":"https://www.artic.edu/iiif/2"},"data":[
                  {"id":27992,"title":"A Sunday","image_id":"image-123","artist_title":"Georges Seurat",
                    "is_public_domain":true},
                  {"id":10,"title":"Restricted","image_id":"not-used","is_public_domain":false}
                ]}
                """).getAsJsonObject();

        OnlineWallpaper result = WallpaperSearchService.parseArtInstituteResponse(root).getFirst();

        assertEquals("A Sunday", result.title());
        assertEquals("Georges Seurat", result.creator());
        assertEquals("Public domain", result.license());
        assertEquals("https://www.artic.edu/iiif/2/image-123/full/843,/0/default.jpg",
                result.fileUri().toString());
        assertEquals("https://www.artic.edu/artworks/27992", result.descriptionUri().toString());
    }
}
