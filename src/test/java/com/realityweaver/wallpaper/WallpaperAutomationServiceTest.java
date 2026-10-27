package com.realityweaver.wallpaper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WallpaperAutomationServiceTest {
    @TempDir
    Path temporaryDirectory;

    private Preferences preferences;
    private WallpaperAutomationService service;

    @BeforeEach
    void setUp() throws Exception {
        preferences = Preferences.userRoot().node("com/realityweaver/tests/" + UUID.randomUUID());
        service = new WallpaperAutomationService(preferences, new WallpaperService());
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
        preferences.removeNode();
    }

    @Test
    void timeWeatherAndCalendarRulesPersistAndRejectDuplicates() {
        Path image = temporaryDirectory.resolve("wallpaper.png");
        service.addTimeRule("06:00", image);
        assertEquals(1, service.timeRules().size());
        assertThrows(IllegalArgumentException.class, () -> service.addTimeRule("06:00", image));

        service.addWeatherRule("", WallpaperAutomationService.WeatherCondition.CLEAR, image, true);
        assertTrue(service.weatherRules().getFirst().automaticLocation());
        assertThrows(IllegalArgumentException.class, () -> service.addWeatherRule(
                "", WallpaperAutomationService.WeatherCondition.CLEAR, image, true));

        service.addCalendarRule("meeting", image);
        assertEquals("meeting", service.calendarRules().getFirst().titleContains());
        assertThrows(IllegalArgumentException.class, () -> service.addCalendarRule("MEETING", image));
        WallpaperAutomationService reopened = new WallpaperAutomationService(preferences, new WallpaperService());
        try {
            assertEquals(1, reopened.timeRules().size());
            assertEquals(1, reopened.weatherRules().size());
            assertEquals(1, reopened.calendarRules().size());
        } finally {
            reopened.close();
        }
    }

    @Test
    void currentLocalTimeSelectsLatestRuleAndWrapsToPriorDay() {
        List<WallpaperAutomationService.TimeRule> rules = List.of(
                new WallpaperAutomationService.TimeRule("morning", "06:00", Path.of("morning.png")),
                new WallpaperAutomationService.TimeRule("evening", "18:00", Path.of("evening.png")),
                new WallpaperAutomationService.TimeRule("night", "21:00", Path.of("night.png")));
        ZoneId zone = ZoneId.of("Asia/Kolkata");

        assertEquals("morning", WallpaperAutomationService.activeTimeRule(rules,
                ZonedDateTime.of(LocalDate.of(2026, 10, 27), LocalTime.of(10, 0), zone)).id());
        ZonedDateTime earlyMorning = ZonedDateTime.of(LocalDate.of(2026, 10, 27), LocalTime.of(2, 0), zone);
        WallpaperAutomationService.TimeRule active = WallpaperAutomationService.activeTimeRule(rules, earlyMorning);
        assertEquals("night", active.id());
        assertEquals("night@2026-10-26", WallpaperAutomationService.timeScheduleKey(active, earlyMorning));
    }

    @Test
    void aSingleFutureTimeRuleDoesNotApplyBeforeItsFirstTrigger() {
        List<WallpaperAutomationService.TimeRule> rules = List.of(
                new WallpaperAutomationService.TimeRule("evening", "18:00", Path.of("evening.png")));
        ZonedDateTime morning = ZonedDateTime.of(LocalDate.of(2026, 10, 27), LocalTime.of(10, 0),
                ZoneId.of("Asia/Kolkata"));
        assertNull(WallpaperAutomationService.activeTimeRule(rules, morning));
    }

    @Test
    void manualOverridePersistsAcrossServiceInstances() {
        service.recordManualOverride();
        WallpaperAutomationService reopened = new WallpaperAutomationService(preferences, new WallpaperService());
        try {
            assertTrue(reopened.isManualOverrideActive());
        } finally {
            reopened.close();
        }
    }

    @Test
    void anAlreadyAppliedCalendarEventIsNotAppliedAgain() {
        assertTrue(WallpaperAutomationService.isNewCalendarEvent("event-42", "event-41"));
        assertFalse(WallpaperAutomationService.isNewCalendarEvent("event-42", "event-42"));
    }
}
