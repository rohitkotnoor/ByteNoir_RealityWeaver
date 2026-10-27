package com.realityweaver.wallpaper;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class WallpaperCyclePlannerTest {
    private final List<Path> wallpapers = List.of(Path.of("morning.png"), Path.of("evening.png"),
            Path.of("night.png"), Path.of("rain.png"));

    @Test
    void cycleAvoidsTheLastThreeImagesWhenAlternativesExist() {
        WallpaperCyclePlanner.Selection first = WallpaperCyclePlanner.next(
                wallpapers, List.of(), Path::toString, new Random(1));
        WallpaperCyclePlanner.Selection second = WallpaperCyclePlanner.next(
                wallpapers, first.recentIds(), Path::toString, new Random(2));

        assertNotEquals(first.path(), second.path());
        assertTrue(first.recentIds().contains(first.path().toString()));
        assertTrue(second.recentIds().contains(second.path().toString()));
    }

    @Test
    void persistedHistorySurvivesAPlannerRestart() {
        WallpaperCyclePlanner.Selection priorRun = WallpaperCyclePlanner.next(
                wallpapers, List.of(), Path::toString, new Random(3));
        List<String> persistedHistory = priorRun.recentIds();

        WallpaperCyclePlanner.Selection nextRun = WallpaperCyclePlanner.next(
                wallpapers, persistedHistory, Path::toString, new Random(4));

        assertNotEquals(priorRun.path(), nextRun.path());
    }
}
