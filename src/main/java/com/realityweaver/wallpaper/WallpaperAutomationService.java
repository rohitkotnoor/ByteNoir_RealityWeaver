package com.realityweaver.wallpaper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.realityweaver.util.AppPreferences;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/** Runs saved time, weather, and calendar wallpaper rules while the app is open. */
public final class WallpaperAutomationService {
    private static final String ENABLED_KEY = "automation.enabled";
    private static final String INTERVAL_CYCLE_ENABLED_KEY = "automation.intervalCycle.enabled";
    private static final String INTERVAL_CYCLE_MINUTES_KEY = "automation.intervalCycle.minutes";
    private static final String INTERVAL_CYCLE_COLLECTION_KEY = "automation.intervalCycle.collectionId";
    private static final String TIME_RULES_KEY = "automation.timeRules";
    private static final String WEATHER_RULES_KEY = "automation.weatherRules";
    private static final String CALENDAR_RULES_KEY = "automation.calendarRules";
    private static final String CALENDAR_FILE_KEY = "automation.calendarFile";
    private static final String CALENDAR_URL_KEY = "automation.calendarUrl";
    private static final String OUTLOOK_ENABLED_KEY = "automation.outlookEnabled";
    private static final String AUTO_LOCATION_ENABLED_KEY = "automation.autoLocationEnabled";
    private static final String MANUAL_OVERRIDE_KEY = "automation.manualOverrideUntil";
    private static final int MAX_RULES_PER_TYPE = 30;
    private static final Duration WEATHER_CACHE_AGE = Duration.ofMinutes(15);
    private static final Duration OUTLOOK_CACHE_AGE = Duration.ofMinutes(1);
    private static final Duration REMOTE_CALENDAR_CACHE_AGE = Duration.ofMinutes(5);
    private static final DateTimeFormatter ICAL_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter ICAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter ICAL_SHORT_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm");
    private static final String OUTLOOK_SCRIPT = """
            $ErrorActionPreference = 'Stop'
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
            try {
                $outlook = [Runtime.InteropServices.Marshal]::GetActiveObject('Outlook.Application')
            } catch {
                $outlook = New-Object -ComObject Outlook.Application
            }
            $calendar = $outlook.GetNamespace('MAPI').GetDefaultFolder(9)
            $items = $calendar.Items
            $items.Sort('[Start]')
            $items.IncludeRecurrences = $true
            $now = Get-Date
            $nowText = $now.ToString('g', [System.Globalization.CultureInfo]::CurrentCulture)
            $filter = "[Start] <= '$nowText' AND [End] >= '$nowText'"
            $events = @()
            foreach ($item in $items.Restrict($filter)) {
                if ($item.Class -eq 26 -and $item.Start -le $now -and $item.End -gt $now) {
                    $events += [PSCustomObject]@{
                        id = $item.EntryID
                        title = $item.Subject
                        start = $item.Start.ToString('o')
                        end = $item.End.ToString('o')
                    }
                }
            }
            if ($events.Count -eq 0) {
                [Console]::Write('[]')
            } else {
                [Console]::Write((ConvertTo-Json -InputObject $events -Compress))
            }
            """;

    private final Preferences preferences;
    private final WallpaperService wallpaperService;
    private final WallpaperCollectionStore collectionStore = new WallpaperCollectionStore();
    private final Random random = new Random();
    private volatile HttpClient httpClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "realityweaver-wallpaper-automation");
        thread.setDaemon(true);
        return thread;
    });
    private volatile String lastTimeScheduleKey;
    private final Map<String, String> lastCalendarApplication = new ConcurrentHashMap<>();
    private volatile String activeWeatherRuleId;
    private final Map<String, Coordinates> coordinatesCache = new ConcurrentHashMap<>();
    private final Map<String, CachedWeather> weatherCache = new ConcurrentHashMap<>();
    private final Map<String, Instant> weatherRetryAfter = new ConcurrentHashMap<>();
    private volatile List<CalendarEvent> outlookEventCache = List.of();
    private volatile Instant outlookEventsCheckedAt = Instant.EPOCH;
    private volatile Instant outlookRetryAfter = Instant.EPOCH;
    private volatile String outlookUnavailableReason;
    private volatile List<String> remoteCalendarLinesCache = List.of();
    private volatile Instant remoteCalendarCheckedAt = Instant.EPOCH;
    private volatile boolean schedulerStarted;
    private volatile ScheduledFuture<?> intervalCycleTask;
    private volatile Consumer<String> statusListener = ignored -> { };
    private volatile Instant manualOverrideUntil = Instant.EPOCH;
    private volatile String latestStatus = "Automation is off.";
    private volatile CachedWeather lastWeather;

    public WallpaperAutomationService() {
        this(AppPreferences.forPackage(WallpaperAutomationService.class), new WallpaperService());
    }

    WallpaperAutomationService(Preferences preferences, WallpaperService wallpaperService) {
        this.preferences = preferences != null ? preferences : AppPreferences.forPackage(WallpaperAutomationService.class);
        this.wallpaperService = wallpaperService != null ? wallpaperService : new WallpaperService();
        long savedOverride = this.preferences.getLong(MANUAL_OVERRIDE_KEY, 0L);
        if (savedOverride > 0) {
            manualOverrideUntil = Instant.ofEpochMilli(savedOverride);
        }
    }

    private HttpClient getHttpClient() {
        HttpClient client = httpClient;
        if (client == null) {
            synchronized (this) {
                client = httpClient;
                if (client == null) {
                    client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(8))
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                    httpClient = client;
                }
            }
        }
        return client;
    }

    public enum WeatherCondition {
        CLEAR("Clear"), CLOUDY("Cloudy"), RAIN("Rain"), SNOW("Snow"), FOG("Fog"), STORM("Storm");

        private final String label;

        WeatherCondition(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public record TimeRule(String id, String time, Path wallpaper, boolean enabled, String collectionId) {
        public TimeRule(String id, String time, Path wallpaper) {
            this(id, time, wallpaper, true, "");
        }
    }

    public record WeatherRule(String id, String city, boolean automaticLocation,
            WeatherCondition condition, Path wallpaper, boolean enabled, String collectionId) {
        public WeatherRule(String id, String city, boolean automaticLocation, WeatherCondition condition, Path wallpaper) {
            this(id, city, automaticLocation, condition, wallpaper, true, "");
        }
    }

    public record CalendarRule(String id, String titleContains, Path wallpaper, boolean enabled, String collectionId) {
        public CalendarRule(String id, String titleContains, Path wallpaper) {
            this(id, titleContains, wallpaper, true, "");
        }
    }

    public boolean isEnabled() {
        return preferences.getBoolean(ENABLED_KEY, false);
    }

    public void setEnabled(boolean enabled, Consumer<String> listener) {
        statusListener = listener == null ? ignored -> { } : listener;
        preferences.putBoolean(ENABLED_KEY, enabled);
        if (enabled) {
            startSchedulerIfNeeded();
            if (isIntervalCycleEnabled() && intervalCycleTask == null) {
                int mins = intervalCycleMinutes();
                intervalCycleTask = scheduler.scheduleWithFixedDelay(
                        this::triggerIntervalCycleNow, mins, mins, TimeUnit.MINUTES);
            }
            publish("Automation is on. Checking saved rules.");
            checkNow();
        } else {
            if (intervalCycleTask != null) {
                intervalCycleTask.cancel(false);
                intervalCycleTask = null;
            }
            publish("Automation is off.");
        }
    }

    public void startIfEnabled(Consumer<String> listener) {
        statusListener = listener == null ? ignored -> { } : listener;
        if (isEnabled()) {
            startSchedulerIfNeeded();
            if (isIntervalCycleEnabled() && intervalCycleTask == null) {
                int mins = intervalCycleMinutes();
                intervalCycleTask = scheduler.scheduleWithFixedDelay(
                        this::triggerIntervalCycleNow, mins, mins, TimeUnit.MINUTES);
            }
            publish("Automation is on. Checking saved rules.");
            checkNow();
        }
    }

    public void setStatusListener(Consumer<String> listener) {
        statusListener = listener == null ? ignored -> { } : listener;
    }

    public boolean isIntervalCycleEnabled() {
        return preferences.getBoolean(INTERVAL_CYCLE_ENABLED_KEY, false);
    }

    public int intervalCycleMinutes() {
        return Math.max(1, preferences.getInt(INTERVAL_CYCLE_MINUTES_KEY, 5));
    }

    public String intervalCycleCollectionId() {
        return preferences.get(INTERVAL_CYCLE_COLLECTION_KEY, "");
    }

    public synchronized void setIntervalCycle(boolean enabled, int minutes, String collectionId) {
        int validMinutes = Math.max(1, minutes);
        preferences.putBoolean(INTERVAL_CYCLE_ENABLED_KEY, enabled);
        preferences.putInt(INTERVAL_CYCLE_MINUTES_KEY, validMinutes);
        preferences.put(INTERVAL_CYCLE_COLLECTION_KEY, collectionId != null ? collectionId : "");
        if (intervalCycleTask != null) {
            intervalCycleTask.cancel(false);
            intervalCycleTask = null;
        }
        if (enabled) {
            if (!isEnabled()) {
                preferences.putBoolean(ENABLED_KEY, true);
            }
            resumeAutomation();
            startSchedulerIfNeeded();
            scheduler.execute(this::triggerIntervalCycleNow);
            intervalCycleTask = scheduler.scheduleWithFixedDelay(
                    this::triggerIntervalCycleNow, validMinutes, validMinutes, TimeUnit.MINUTES);
            publish("Interval cycling active: changing wallpaper every " + validMinutes + (validMinutes == 1 ? " minute." : " minutes."));
        } else {
            publish("Interval cycling turned off.");
        }
    }

    public void triggerIntervalCycleNow() {
        if (!isEnabled()) {
            return;
        }
        if (isManualOverrideActive()) {
            resumeAutomation();
        }
        String collectionId = intervalCycleCollectionId();
        if (collectionId != null && !collectionId.isBlank() && !collectionId.equalsIgnoreCase("all")) {
            try {
                WallpaperCollection col = collectionStore.find(collectionId);
                if (col != null) {
                    List<Path> available = col.wallpapers().stream()
                            .filter(WallpaperCollection.WallpaperReference::isAvailable)
                            .map(WallpaperCollection.WallpaperReference::path)
                            .filter(p -> p != null && Files.isRegularFile(p))
                            .toList();
                    if (!available.isEmpty()) {
                        Path chosen = (available.size() == 1) ? available.get(0)
                                : available.get(random.nextInt(available.size()));
                        try {
                            wallpaperService.apply(chosen);
                            publish("Interval cycle: applied “" + chosen.getFileName() + "” from “" + col.name() + "”.");
                            return;
                        } catch (Exception failure) {
                            publish("Interval cycle failed to apply: " + safeMessage(failure));
                            return;
                        }
                    }
                }
            } catch (Exception ignored) { }
        }
        // Fallback to library
        WallpaperLibrary lib = new WallpaperLibrary();
        List<Path> all = lib.load().stream().filter(Files::isRegularFile).toList();
        if (!all.isEmpty()) {
            Path chosen = (all.size() == 1) ? all.get(0) : all.get(random.nextInt(all.size()));
            try {
                wallpaperService.apply(chosen);
                publish("Interval cycle: applied “" + chosen.getFileName() + "” from library.");
            } catch (Exception failure) {
                publish("Interval cycle failed: " + safeMessage(failure));
            }
        }
    }

    public void checkNow() {
        if (!isEnabled()) {
            return;
        }
        manualOverrideUntil = Instant.EPOCH;
        preferences.remove(MANUAL_OVERRIDE_KEY);
        startSchedulerIfNeeded();
        scheduler.execute(this::checkRules);
    }

    /** Records a successful user-initiated apply and pauses automation for one hour. */
    public void recordManualOverride() {
        manualOverrideUntil = Instant.now().plus(Duration.ofHours(1));
        preferences.putLong(MANUAL_OVERRIDE_KEY, manualOverrideUntil.toEpochMilli());
        lastTimeScheduleKey = null;
        activeWeatherRuleId = null;
        clearCalendarApplications();
        publish("Manual wallpaper selected. Automatic changes are paused for one hour.");
    }

    public void resumeAutomation() {
        manualOverrideUntil = Instant.EPOCH;
        preferences.remove(MANUAL_OVERRIDE_KEY);
        lastTimeScheduleKey = null;
        activeWeatherRuleId = null;
        publish("Manual override cleared. Automation resumed.");
        checkNow();
    }

    public boolean isManualOverrideActive() {
        return Instant.now().isBefore(manualOverrideUntil);
    }

    public Duration manualOverrideRemaining() {
        Instant now = Instant.now();
        return now.isBefore(manualOverrideUntil) ? Duration.between(now, manualOverrideUntil) : Duration.ZERO;
    }

    public String latestStatus() {
        return latestStatus;
    }

    public CachedWeather lastWeather() {
        return lastWeather;
    }

    /** Applies a collection-cycle image while automation is enabled, automatically clearing any stale manual override. */
    public boolean applyCollectionWallpaper(Path wallpaper, String collectionName) {
        if (!isEnabled()) {
            return false;
        }
        if (isManualOverrideActive()) {
            resumeAutomation();
        }
        return applyRule(wallpaper, "collection “" + collectionName + "”");
    }

    public void close() {
        if (intervalCycleTask != null) {
            intervalCycleTask.cancel(false);
            intervalCycleTask = null;
        }
        scheduler.shutdownNow();
    }

    public List<TimeRule> timeRules() {
        return readRules(TIME_RULES_KEY, element -> new TimeRule(
                stringValue(element, "id"),
                stringValue(element, "time"),
                pathValue(element, "wallpaper"),
                !element.has("enabled") || element.get("enabled").getAsBoolean(),
                stringValue(element, "collectionId")));
    }

    public List<WeatherRule> weatherRules() {
        return readRules(WEATHER_RULES_KEY, element -> {
            WeatherCondition condition;
            try {
                condition = WeatherCondition.valueOf(stringValue(element, "condition"));
            } catch (IllegalArgumentException invalid) {
                return null;
            }
            return new WeatherRule(
                    stringValue(element, "id"),
                    stringValue(element, "city"),
                    element.has("automaticLocation") && element.get("automaticLocation").getAsBoolean(),
                    condition,
                    pathValue(element, "wallpaper"),
                    !element.has("enabled") || element.get("enabled").getAsBoolean(),
                    stringValue(element, "collectionId"));
        });
    }

    public List<CalendarRule> calendarRules() {
        return readRules(CALENDAR_RULES_KEY, element -> new CalendarRule(
                stringValue(element, "id"),
                stringValue(element, "titleContains"),
                pathValue(element, "wallpaper"),
                !element.has("enabled") || element.get("enabled").getAsBoolean(),
                stringValue(element, "collectionId")));
    }

    public Path calendarFile() {
        String value = preferences.get(CALENDAR_FILE_KEY, "");
        return value.isBlank() ? null : Path.of(value);
    }

    public String calendarUrl() {
        return preferences.get(CALENDAR_URL_KEY, "");
    }

    public void setCalendarUrl(String url) {
        if (url == null || url.isBlank()) {
            preferences.remove(CALENDAR_URL_KEY);
        } else {
            preferences.put(CALENDAR_URL_KEY, url.strip());
        }
        remoteCalendarCheckedAt = Instant.EPOCH;
    }

    public boolean isOutlookCalendarEnabled() {
        return preferences.getBoolean(OUTLOOK_ENABLED_KEY, true);
    }

    public boolean isAutomaticLocationEnabled() {
        return preferences.getBoolean(AUTO_LOCATION_ENABLED_KEY, false);
    }

    /** Saves explicit consent for weather checks that estimate location from the internet connection. */
    public void setAutomaticLocationEnabled(boolean enabled) {
        preferences.putBoolean(AUTO_LOCATION_ENABLED_KEY, enabled);
    }

    public void setOutlookCalendarEnabled(boolean enabled) {
        preferences.putBoolean(OUTLOOK_ENABLED_KEY, enabled);
        outlookEventsCheckedAt = Instant.EPOCH;
        outlookRetryAfter = Instant.EPOCH;
    }

    public void setCalendarFile(Path path) {
        if (path == null) {
            preferences.remove(CALENDAR_FILE_KEY);
        } else {
            preferences.put(CALENDAR_FILE_KEY, path.toAbsolutePath().normalize().toString());
        }
    }

    public void setTimeRuleEnabled(String id, boolean enabled) {
        List<TimeRule> rules = new ArrayList<>(timeRules());
        for (int i = 0; i < rules.size(); i++) {
            TimeRule r = rules.get(i);
            if (r.id().equals(id)) {
                rules.set(i, new TimeRule(r.id(), r.time(), r.wallpaper(), enabled, r.collectionId()));
                break;
            }
        }
        writeTimeRules(rules);
    }

    public void setWeatherRuleEnabled(String id, boolean enabled) {
        List<WeatherRule> rules = new ArrayList<>(weatherRules());
        for (int i = 0; i < rules.size(); i++) {
            WeatherRule r = rules.get(i);
            if (r.id().equals(id)) {
                rules.set(i, new WeatherRule(r.id(), r.city(), r.automaticLocation(), r.condition(), r.wallpaper(), enabled, r.collectionId()));
                break;
            }
        }
        writeWeatherRules(rules);
    }

    public void setCalendarRuleEnabled(String id, boolean enabled) {
        List<CalendarRule> rules = new ArrayList<>(calendarRules());
        for (int i = 0; i < rules.size(); i++) {
            CalendarRule r = rules.get(i);
            if (r.id().equals(id)) {
                rules.set(i, new CalendarRule(r.id(), r.titleContains(), r.wallpaper(), enabled, r.collectionId()));
                break;
            }
        }
        writeCalendarRules(rules);
    }

    public static LocalTime parseTimeFlexibly(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Enter a valid time (e.g. 14:30 or 09:00).");
        }
        String s = text.strip();
        try {
            return LocalTime.parse(s);
        } catch (DateTimeParseException ignored) { }

        try {
            return LocalTime.parse(s, DateTimeFormatter.ofPattern("H:mm"));
        } catch (DateTimeParseException ignored) { }

        try {
            return LocalTime.parse(s.toUpperCase(Locale.ENGLISH), DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH));
        } catch (DateTimeParseException ignored) { }

        try {
            return LocalTime.parse(s.toUpperCase(Locale.ENGLISH), DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH));
        } catch (DateTimeParseException ignored) { }

        if (s.matches("^\\d{1,2}$")) {
            int hour = Integer.parseInt(s);
            if (hour >= 0 && hour <= 23) {
                return LocalTime.of(hour, 0);
            }
        }

        throw new IllegalArgumentException("Time “" + text + "” could not be parsed. Use 24h format like 14:30 or 09:00.");
    }

    public void addTimeRule(String time, Path wallpaper) {
        addTimeRule(time, wallpaper, "");
    }

    public void addTimeRule(String time, Path wallpaper, String collectionId) {
        LocalTime parsedTime = parseTimeFlexibly(time);
        String normalizedTime = parsedTime.format(DateTimeFormatter.ofPattern("HH:mm"));
        List<TimeRule> rules = new ArrayList<>(timeRules());
        if (rules.stream().anyMatch(rule -> {
            try {
                return LocalTime.parse(rule.time()).equals(parsedTime);
            } catch (Exception e) {
                return false;
            }
        })) {
            throw new IllegalArgumentException("A daily rule already exists for that time (" + normalizedTime + ").");
        }
        ensureRoom(rules.size());
        rules.add(new TimeRule(UUID.randomUUID().toString(), normalizedTime, wallpaper != null ? normalize(wallpaper) : null, true, collectionId));
        writeTimeRules(rules);
    }

    private void writeTimeRules(List<TimeRule> rules) {
        writeRules(TIME_RULES_KEY, rules, rule -> {
            JsonObject json = baseRule(rule.id(), rule.wallpaper());
            json.addProperty("time", rule.time());
            json.addProperty("enabled", rule.enabled());
            json.addProperty("collectionId", rule.collectionId() != null ? rule.collectionId() : "");
            return json;
        });
    }

    public void removeTimeRule(String id) {
        List<TimeRule> rules = new ArrayList<>(timeRules());
        if (rules.removeIf(rule -> rule.id().equals(id))) {
            writeTimeRules(rules);
        }
    }

    public void addWeatherRule(String city, WeatherCondition condition, Path wallpaper) {
        addWeatherRule(city, condition, wallpaper, false);
    }

    public void addWeatherRule(String city, WeatherCondition condition, Path wallpaper, boolean automaticLocation) {
        addWeatherRule(city, condition, wallpaper, automaticLocation, "");
    }

    public void addWeatherRule(String city, WeatherCondition condition, Path wallpaper, boolean automaticLocation, String collectionId) {
        String normalizedCity = city == null ? "" : city.strip();
        if ((!automaticLocation && normalizedCity.isBlank()) || condition == null) {
            throw new IllegalArgumentException("Choose a weather location and condition.");
        }
        List<WeatherRule> rules = new ArrayList<>(weatherRules());
        if (rules.stream().anyMatch(rule -> rule.automaticLocation() == automaticLocation
                && (automaticLocation || rule.city().equalsIgnoreCase(normalizedCity))
                && rule.condition() == condition)) {
            throw new IllegalArgumentException("A rule already exists for that location and weather condition.");
        }
        ensureRoom(rules.size());
        rules.add(new WeatherRule(UUID.randomUUID().toString(), normalizedCity, automaticLocation, condition,
                wallpaper != null ? normalize(wallpaper) : null, true, collectionId));
        writeWeatherRules(rules);
    }

    private void writeWeatherRules(List<WeatherRule> rules) {
        writeRules(WEATHER_RULES_KEY, rules, rule -> {
            JsonObject json = baseRule(rule.id(), rule.wallpaper());
            json.addProperty("city", rule.city());
            json.addProperty("automaticLocation", rule.automaticLocation());
            json.addProperty("condition", rule.condition().name());
            json.addProperty("enabled", rule.enabled());
            json.addProperty("collectionId", rule.collectionId() != null ? rule.collectionId() : "");
            return json;
        });
    }

    public void removeWeatherRule(String id) {
        List<WeatherRule> rules = new ArrayList<>(weatherRules());
        if (rules.removeIf(rule -> rule.id().equals(id))) {
            writeWeatherRules(rules);
        }
        if (id.equals(activeWeatherRuleId)) {
            activeWeatherRuleId = null;
            lastTimeScheduleKey = null;
        }
    }

    public void addCalendarRule(String titleContains, Path wallpaper) {
        addCalendarRule(titleContains, wallpaper, "");
    }

    public void addCalendarRule(String titleContains, Path wallpaper, String collectionId) {
        String normalizedTitle = titleContains == null ? "" : titleContains.strip();
        if (normalizedTitle.isBlank()) {
            throw new IllegalArgumentException("Enter a title or keyword from the calendar event.");
        }
        List<CalendarRule> rules = new ArrayList<>(calendarRules());
        if (rules.stream().anyMatch(rule -> rule.titleContains().equalsIgnoreCase(normalizedTitle))) {
            throw new IllegalArgumentException("A calendar rule already exists for that title keyword.");
        }
        ensureRoom(rules.size());
        rules.add(new CalendarRule(UUID.randomUUID().toString(), normalizedTitle,
                wallpaper != null ? normalize(wallpaper) : null, true, collectionId));
        writeCalendarRules(rules);
    }

    private void writeCalendarRules(List<CalendarRule> rules) {
        writeRules(CALENDAR_RULES_KEY, rules, rule -> {
            JsonObject json = baseRule(rule.id(), rule.wallpaper());
            json.addProperty("titleContains", rule.titleContains());
            json.addProperty("enabled", rule.enabled());
            json.addProperty("collectionId", rule.collectionId() != null ? rule.collectionId() : "");
            return json;
        });
    }

    public void removeCalendarRule(String id) {
        List<CalendarRule> rules = new ArrayList<>(calendarRules());
        if (rules.removeIf(rule -> rule.id().equals(id))) {
            writeCalendarRules(rules);
        }
        preferences.remove("automation.lastCalendarEvent." + id);
        lastCalendarApplication.remove(id);
    }

    public void removeWallpaper(Path wallpaper) {
        Path normalized = normalize(wallpaper);
        List<TimeRule> time = new ArrayList<>(timeRules());
        if (time.removeIf(rule -> normalized.equals(rule.wallpaper()))) {
            writeTimeRules(time);
        }

        List<WeatherRule> weather = new ArrayList<>(weatherRules());
        if (weather.removeIf(rule -> normalized.equals(rule.wallpaper()))) {
            writeWeatherRules(weather);
        }

        List<CalendarRule> calendar = new ArrayList<>(calendarRules());
        if (calendar.removeIf(rule -> normalized.equals(rule.wallpaper()))) {
            writeCalendarRules(calendar);
        }
    }

    private synchronized void startSchedulerIfNeeded() {
        if (schedulerStarted) {
            return;
        }
        schedulerStarted = true;
        scheduler.scheduleWithFixedDelay(this::checkRulesSafely, 3, 20, TimeUnit.SECONDS);
    }

    private void checkRulesSafely() {
        try {
            checkRules();
        } catch (RuntimeException failure) {
            publish("Automation check failed: " + safeMessage(failure));
        }
    }

    private void checkRules() {
        if (!isEnabled()) {
            return;
        }
        if (isManualOverrideActive()) {
            publish("Manual wallpaper override is active. Automatic changes resume in one hour.");
            return;
        }
        List<TimeRule> time = timeRules().stream().filter(TimeRule::enabled).toList();
        List<WeatherRule> weather = weatherRules().stream().filter(WeatherRule::enabled).toList();
        List<CalendarRule> calendar = calendarRules().stream().filter(CalendarRule::enabled).toList();
        if (time.isEmpty() && weather.isEmpty() && calendar.isEmpty()) {
            publish("Automation is on. Add an active time, weather, or calendar rule.");
            return;
        }

        String latestIssue = null;
        ZonedDateTime now = ZonedDateTime.now();

        // 1. Calendar Rules (Priority 1)
        if (!calendar.isEmpty()) {
            List<CalendarEvent> outlookEvents = List.of();
            if (isOutlookCalendarEnabled()) {
                try {
                    outlookEvents = readOutlookEvents();
                    outlookUnavailableReason = null;
                } catch (IOException | InterruptedException | RuntimeException failure) {
                    if (failure instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        publish("Local Outlook calendar check was interrupted.");
                        return;
                    }
                    outlookUnavailableReason = safeMessage(failure);
                }
            }

            for (CalendarRule rule : calendar) {
                CalendarEvent event = outlookEvents.stream()
                        .filter(candidate -> candidate.title().toLowerCase(Locale.ROOT)
                                .contains(rule.titleContains().toLowerCase(Locale.ROOT)))
                        .findFirst().orElse(null);

                // Check local .ics file
                Path file = calendarFile();
                if (event == null && file != null && Files.isRegularFile(file)) {
                    try {
                        event = activeEvent(file, rule.titleContains(), now);
                    } catch (IOException | RuntimeException failure) {
                        latestIssue = "Local .ics calendar could not be checked: " + safeMessage(failure);
                    }
                }

                // Check remote calendar URL
                if (event == null && !calendarUrl().isBlank()) {
                    try {
                        event = activeRemoteEvent(calendarUrl(), rule.titleContains(), now);
                    } catch (Exception failure) {
                        latestIssue = "Remote calendar feed could not be checked: " + safeMessage(failure);
                    }
                }

                if (event != null) {
                    lastTimeScheduleKey = null;
                    activeWeatherRuleId = null;
                    String previousEvent = lastCalendarApplication.computeIfAbsent(rule.id(), id ->
                            preferences.get("automation.lastCalendarEvent." + id, ""));
                    if (isNewCalendarEvent(event.key(), previousEvent)) {
                        if (applyRuleOrCollection(rule.wallpaper(), rule.collectionId(), "calendar event “" + event.title() + "”")) {
                            lastCalendarApplication.put(rule.id(), event.key());
                            preferences.put("automation.lastCalendarEvent." + rule.id(), event.key());
                        }
                    }
                    return;
                }
            }
        }

        // 2. Weather Rules (Priority 2)
        for (WeatherRule rule : weather) {
            try {
                CachedWeather current = currentWeather(rule.city(), rule.automaticLocation());
                if (current.condition() == rule.condition()) {
                    if (!rule.id().equals(activeWeatherRuleId)) {
                        if (applyRuleOrCollection(rule.wallpaper(), rule.collectionId(), current.locationLabel() + " weather: " + current.condition())) {
                            activeWeatherRuleId = rule.id();
                            lastTimeScheduleKey = null;
                            return;
                        }
                    } else {
                        return;
                    }
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                publish("Weather check was interrupted.");
                return;
            } catch (IOException | RuntimeException failure) {
                String location = rule.automaticLocation() ? "this PC's approximate location" : rule.city();
                latestIssue = "Weather rule for " + location + " could not be checked: " + safeMessage(failure);
            }
        }

        if (activeWeatherRuleId != null) {
            activeWeatherRuleId = null;
            lastTimeScheduleKey = null;
        }

        // 3. Time Rules (Priority 3)
        TimeRule activeTimeRule = activeTimeRule(time, now);
        if (activeTimeRule != null) {
            String scheduleKey = timeScheduleKey(activeTimeRule, now);
            if (!scheduleKey.equals(lastTimeScheduleKey)) {
                LocalTime trigger = LocalTime.parse(activeTimeRule.time());
                if (applyRuleOrCollection(activeTimeRule.wallpaper(), activeTimeRule.collectionId(), "local time "
                        + trigger.format(DateTimeFormatter.ofPattern("HH:mm")))) {
                    lastTimeScheduleKey = scheduleKey;
                    return;
                }
            }
        }

        publish(latestIssue == null ? "Automation is on. Waiting for the next matching rule." : latestIssue);
    }

    static TimeRule activeTimeRule(List<TimeRule> rules, ZonedDateTime now) {
        TimeRule latestToday = null;
        TimeRule lastDailyRule = null;
        LocalTime localNow = now.toLocalTime();
        for (TimeRule rule : rules) {
            try {
                LocalTime trigger = LocalTime.parse(rule.time());
                lastDailyRule = rule;
                if (!trigger.isAfter(localNow)
                        && (latestToday == null || trigger.isAfter(LocalTime.parse(latestToday.time())))) {
                    latestToday = rule;
                }
            } catch (DateTimeParseException ignored) {
                // Ignore a malformed saved time rule.
            }
        }
        if (latestToday != null) {
            return latestToday;
        }
        long validRuleCount = rules.stream().filter(rule -> {
            try {
                LocalTime.parse(rule.time());
                return true;
            } catch (DateTimeParseException ignored) {
                return false;
            }
        }).count();
        if (validRuleCount < 2) {
            return null;
        }
        return rules.stream().filter(rule -> {
            try {
                LocalTime.parse(rule.time());
                return true;
            } catch (DateTimeParseException ignored) {
                return false;
            }
        }).max((first, second) -> LocalTime.parse(first.time()).compareTo(LocalTime.parse(second.time())))
                .orElse(lastDailyRule);
    }

    static String timeScheduleKey(TimeRule rule, ZonedDateTime now) {
        LocalDate activeDate = now.toLocalDate();
        if (LocalTime.parse(rule.time()).isAfter(now.toLocalTime())) {
            activeDate = activeDate.minusDays(1);
        }
        return rule.id() + "@" + activeDate;
    }

    private void clearCalendarApplications() {
        for (CalendarRule rule : calendarRules()) {
            preferences.remove("automation.lastCalendarEvent." + rule.id());
        }
        lastCalendarApplication.clear();
    }

    static boolean isNewCalendarEvent(String eventKey, String lastAppliedEventKey) {
        return eventKey != null && !eventKey.isBlank() && !eventKey.equals(lastAppliedEventKey);
    }

    private boolean applyRuleOrCollection(Path wallpaper, String collectionId, String reason) {
        if (!isEnabled()) {
            return false;
        }
        if (collectionId != null && !collectionId.isBlank()) {
            try {
                WallpaperCollection collection = collectionStore.find(collectionId);
                if (collection != null && !collection.wallpapers().isEmpty()) {
                    List<Path> available = collection.wallpapers().stream()
                            .filter(WallpaperCollection.WallpaperReference::isAvailable)
                            .map(WallpaperCollection.WallpaperReference::path)
                            .filter(p -> p != null && Files.isRegularFile(p))
                            .toList();
                    if (!available.isEmpty()) {
                        Path chosen = available.get(random.nextInt(available.size()));
                        return applyRule(chosen, reason + " (from collection “" + collection.name() + "”)");
                    }
                }
            } catch (Exception failure) {
                // Fall back to direct wallpaper if available
            }
        }
        if (wallpaper != null && Files.isRegularFile(wallpaper)) {
            return applyRule(wallpaper, reason);
        }
        return false;
    }

    private boolean applyRule(Path wallpaper, String reason) {
        if (!isEnabled()) {
            return false;
        }
        try {
            wallpaperService.apply(wallpaper);
            publish("Changed wallpaper for " + reason + ": " + wallpaper.getFileName());
            return true;
        } catch (IOException | UnsupportedOperationException failure) {
            publish("Could not apply automatic wallpaper: " + safeMessage(failure));
            return false;
        }
    }

    public CachedWeather currentWeather(String city, boolean automaticLocation)
            throws IOException, InterruptedException {
        String normalized = automaticLocation ? "pc-location" : city.strip().toLowerCase(Locale.ROOT);
        CachedWeather cached = weatherCache.get(normalized);
        Instant now = Instant.now();
        if (cached != null && Duration.between(cached.checkedAt(), now).compareTo(WEATHER_CACHE_AGE) < 0) {
            return cached;
        }
        Instant retryAfter = weatherRetryAfter.get(normalized);
        if (retryAfter != null && now.isBefore(retryAfter)) {
            throw new IOException("Weather lookup is temporarily paused after a recent error.");
        }

        try {
            Coordinates coordinates = coordinatesCache.get(normalized);
            if (coordinates == null) {
                if (automaticLocation) {
                    JsonObject location = getJson("https://ipapi.co/json/");
                    if (!location.has("latitude") || !location.has("longitude")
                            || location.get("latitude").isJsonNull() || location.get("longitude").isJsonNull()) {
                        throw new IOException("Could not estimate this PC's location from its internet connection.");
                    }
                    String cityName = jsonString(location, "city");
                    String region = jsonString(location, "region");
                    String country = jsonString(location, "country_name");
                    String place = List.of(cityName, region, country).stream()
                            .filter(part -> !part.isBlank()).distinct().reduce((a, b) -> a + ", " + b)
                            .orElse("PC location");
                    coordinates = new Coordinates(location.get("latitude").getAsDouble(),
                            location.get("longitude").getAsDouble(), place);
                } else {
                    String query = URLEncoder.encode(city.strip(), StandardCharsets.UTF_8);
                    JsonObject response = getJson("https://geocoding-api.open-meteo.com/v1/search?name="
                            + query + "&count=1&language=en&format=json");
                    JsonArray results = response.has("results") ? response.getAsJsonArray("results") : new JsonArray();
                    if (results.isEmpty()) {
                        throw new IOException("No location matched “" + city + "”.");
                    }
                    JsonObject location = results.get(0).getAsJsonObject();
                    String cityName = jsonString(location, "name");
                    String region = jsonString(location, "admin1");
                    String country = jsonString(location, "country");
                    String place = List.of(cityName, region, country).stream()
                            .filter(part -> !part.isBlank()).distinct().reduce((a, b) -> a + ", " + b)
                            .orElse(city.strip());
                    coordinates = new Coordinates(location.get("latitude").getAsDouble(),
                            location.get("longitude").getAsDouble(), place);
                }
                coordinatesCache.put(normalized, coordinates);
            }

            String latitude = Double.toString(coordinates.latitude());
            String longitude = Double.toString(coordinates.longitude());
            JsonObject response = getJson("https://api.open-meteo.com/v1/forecast?latitude=" + latitude
                    + "&longitude=" + longitude + "&current=weather_code,temperature_2m&timezone=auto");
            JsonObject current = response.getAsJsonObject("current");
            if (current == null || !current.has("weather_code")) {
                throw new IOException("The weather service did not return current conditions.");
            }
            WeatherCondition condition = classifyWeather(current.get("weather_code").getAsInt());
            if (condition == null) {
                throw new IOException("The current weather condition is not recognized.");
            }
            weatherRetryAfter.remove(normalized);
            double temp = current.has("temperature_2m") && !current.get("temperature_2m").isJsonNull()
                    ? current.get("temperature_2m").getAsDouble() : Double.NaN;
            CachedWeather result = new CachedWeather(condition, coordinates.locationLabel(), temp, now);
            lastWeather = result;
            weatherCache.put(normalized, result);
            return result;
        } catch (IOException failure) {
            weatherRetryAfter.put(normalized, now.plus(Duration.ofMinutes(5)));
            throw failure;
        }
    }

    private List<CalendarEvent> readOutlookEvents() throws IOException, InterruptedException {
        Instant now = Instant.now();
        if (Duration.between(outlookEventsCheckedAt, now).compareTo(OUTLOOK_CACHE_AGE) < 0) {
            return outlookEventCache;
        }
        if (now.isBefore(outlookRetryAfter)) {
            throw new IOException(outlookUnavailableReason == null
                    ? "Outlook calendar access is temporarily paused after a recent error."
                    : outlookUnavailableReason);
        }

        String encodedScript = Base64.getEncoder().encodeToString(OUTLOOK_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-EncodedCommand", encodedScript).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                outlookRetryAfter = now.plus(OUTLOOK_CACHE_AGE);
                throw new IOException("Outlook did not respond in time.");
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            throw interrupted;
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0) {
            outlookRetryAfter = now.plus(OUTLOOK_CACHE_AGE);
            throw new IOException(output.isBlank() ? "Classic Outlook is not available on this PC." : output);
        }

        try {
            JsonElement root = JsonParser.parseString(output);
            JsonArray rows;
            if (root.isJsonArray()) {
                rows = root.getAsJsonArray();
            } else {
                rows = new JsonArray();
                if (root.isJsonObject()) {
                    rows.add(root);
                }
            }
            List<CalendarEvent> events = new ArrayList<>();
            for (JsonElement row : rows) {
                if (!row.isJsonObject()) {
                    continue;
                }
                JsonObject item = row.getAsJsonObject();
                String title = jsonString(item, "title");
                ZonedDateTime startsAt = parseOutlookDateTime(jsonString(item, "start"));
                ZonedDateTime endsAt = parseOutlookDateTime(jsonString(item, "end"));
                if (title.isBlank() || !endsAt.isAfter(startsAt)) {
                    continue;
                }
                events.add(new CalendarEvent(title, startsAt, endsAt,
                        jsonString(item, "id") + "@" + startsAt.toInstant()));
            }
            outlookEventCache = List.copyOf(events);
            outlookEventsCheckedAt = now;
            outlookRetryAfter = Instant.EPOCH;
            return outlookEventCache;
        } catch (RuntimeException invalidResponse) {
            outlookRetryAfter = now.plus(OUTLOOK_CACHE_AGE);
            throw new IOException("Could not read the local Outlook calendar response.", invalidResponse);
        }
    }

    private ZonedDateTime parseOutlookDateTime(String value) {
        try {
            return ZonedDateTime.parse(value);
        } catch (DateTimeParseException noZone) {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault());
        }
    }

    private String jsonString(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString().strip() : "";
    }

    private JsonObject getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .header("User-Agent", "RealityWeaver/1.0")
                .GET()
                .build();
        HttpResponse<String> response = getHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Location or weather service returned HTTP " + response.statusCode() + ".");
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException invalidJson) {
            throw new IOException("Weather service returned an invalid response.", invalidJson);
        }
    }

    private WeatherCondition classifyWeather(int code) {
        if (code == 0) {
            return WeatherCondition.CLEAR;
        }
        if (code >= 1 && code <= 3) {
            return WeatherCondition.CLOUDY;
        }
        if (code == 45 || code == 48) {
            return WeatherCondition.FOG;
        }
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) {
            return WeatherCondition.RAIN;
        }
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            return WeatherCondition.SNOW;
        }
        if (code == 95 || code == 96 || code == 99) {
            return WeatherCondition.STORM;
        }
        return null;
    }

    private CalendarEvent activeEvent(Path calendar, String titleContains, ZonedDateTime now) throws IOException {
        return parseActiveEvent(Files.readAllLines(calendar, StandardCharsets.UTF_8), titleContains, now);
    }

    private CalendarEvent activeRemoteEvent(String url, String titleContains, ZonedDateTime now) throws Exception {
        Instant currentInstant = Instant.now();
        if (Duration.between(remoteCalendarCheckedAt, currentInstant).compareTo(REMOTE_CALENDAR_CACHE_AGE) >= 0 || remoteCalendarLinesCache.isEmpty()) {
            String fetchUrl = url.trim();
            if (fetchUrl.startsWith("webcal://")) {
                fetchUrl = "https://" + fetchUrl.substring("webcal://".length());
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(fetchUrl))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "RealityWeaver/1.0")
                    .GET()
                    .build();
            HttpResponse<String> res = getHttpClient().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                remoteCalendarLinesCache = res.body().lines().toList();
                remoteCalendarCheckedAt = currentInstant;
            }
        }
        return parseActiveEvent(remoteCalendarLinesCache, titleContains, now);
    }

    private CalendarEvent parseActiveEvent(List<String> rawLines, String titleContains, ZonedDateTime now) {
        List<String> unfoldedLines = new ArrayList<>();
        for (String line : rawLines) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && !unfoldedLines.isEmpty()) {
                int last = unfoldedLines.size() - 1;
                unfoldedLines.set(last, unfoldedLines.get(last) + line.substring(1));
            } else {
                unfoldedLines.add(line);
            }
        }

        Map<String, String> properties = null;
        Map<String, String> parameters = null;
        String normalizedKeyword = titleContains.toLowerCase(Locale.ROOT);
        for (String line : unfoldedLines) {
            if (line.equalsIgnoreCase("BEGIN:VEVENT")) {
                properties = new HashMap<>();
                parameters = new HashMap<>();
                continue;
            }
            if (line.equalsIgnoreCase("END:VEVENT")) {
                if (properties != null) {
                    CalendarEvent event = parseCalendarEvent(properties, parameters);
                    if (event != null && event.title().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                            && !now.isBefore(event.startsAt()) && now.isBefore(event.endsAt())) {
                        return event;
                    }
                }
                properties = null;
                parameters = null;
                continue;
            }
            if (properties == null) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String[] descriptor = line.substring(0, colon).split(";");
            String name = descriptor[0].toUpperCase(Locale.ROOT);
            properties.put(name, line.substring(colon + 1));
            for (int index = 1; index < descriptor.length; index++) {
                int equals = descriptor[index].indexOf('=');
                if (equals > 0) {
                    parameters.put(name + "." + descriptor[index].substring(0, equals).toUpperCase(Locale.ROOT),
                            descriptor[index].substring(equals + 1).replace("\"", ""));
                }
            }
        }
        return null;
    }

    private CalendarEvent parseCalendarEvent(Map<String, String> properties, Map<String, String> parameters) {
        String rawTitle = properties.get("SUMMARY");
        String rawStart = properties.get("DTSTART");
        if (rawTitle == null || rawStart == null) {
            return null;
        }
        try {
            ParsedDate start = parseIcalDate(rawStart, parameters.get("DTSTART.TZID"),
                    "DATE".equalsIgnoreCase(parameters.get("DTSTART.VALUE")));
            ParsedDate end = null;
            String rawEnd = properties.get("DTEND");
            if (rawEnd != null) {
                end = parseIcalDate(rawEnd, parameters.get("DTEND.TZID"),
                        "DATE".equalsIgnoreCase(parameters.get("DTEND.VALUE")));
            }
            ZonedDateTime startsAt = start.value();
            ZonedDateTime endsAt;
            if (end != null) {
                endsAt = end.value();
            } else if (properties.containsKey("DURATION")) {
                endsAt = startsAt.plus(Duration.parse(properties.get("DURATION")));
            } else {
                endsAt = start.allDay() ? startsAt.plusDays(1) : startsAt.plusHours(1);
            }
            if (!endsAt.isAfter(startsAt)) {
                return null;
            }
            String title = unescapeIcalText(rawTitle);
            String key = properties.getOrDefault("UID", title) + "@" + startsAt.toInstant();
            return new CalendarEvent(title, startsAt, endsAt, key);
        } catch (RuntimeException malformedEvent) {
            return null;
        }
    }

    private ParsedDate parseIcalDate(String value, String timeZone, boolean allDay) {
        if (allDay || value.length() == 8) {
            LocalDate date = LocalDate.parse(value, ICAL_DATE);
            return new ParsedDate(date.atStartOfDay(ZoneId.systemDefault()), true);
        }
        if (value.endsWith("Z")) {
            String withoutZulu = value.substring(0, value.length() - 1);
            LocalDateTime utc;
            try {
                utc = LocalDateTime.parse(withoutZulu, ICAL_DATE_TIME);
            } catch (DateTimeParseException shortFormat) {
                utc = LocalDateTime.parse(withoutZulu, ICAL_SHORT_DATE_TIME);
            }
            return new ParsedDate(utc.atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.systemDefault()), false);
        }
        ZoneId zone = timeZone == null || timeZone.isBlank()
                ? ZoneId.systemDefault()
                : ZoneId.of(timeZone);
        LocalDateTime local;
        try {
            local = LocalDateTime.parse(value, ICAL_DATE_TIME);
        } catch (DateTimeParseException shortFormat) {
            local = LocalDateTime.parse(value, ICAL_SHORT_DATE_TIME);
        }
        return new ParsedDate(local.atZone(zone).withZoneSameInstant(ZoneId.systemDefault()), false);
    }

    private String unescapeIcalText(String value) {
        return value.replace("\\n", " ").replace("\\N", " ")
                .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }

    private void publish(String message) {
        latestStatus = message;
        try {
            statusListener.accept(message);
        } catch (RuntimeException ignored) {
            // Status reporting must not stop the background scheduler.
        }
    }

    private String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private void ensureRoom(int size) {
        if (size >= MAX_RULES_PER_TYPE) {
            throw new IllegalStateException("You can save up to " + MAX_RULES_PER_TYPE + " rules of each type.");
        }
    }

    private Path normalize(Path path) {
        if (path == null) {
            return null;
        }
        return path.toAbsolutePath().normalize();
    }

    private JsonObject baseRule(String id, Path wallpaper) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        if (wallpaper != null) {
            json.addProperty("wallpaper", wallpaper.toString());
        }
        return json;
    }

    private <T> List<T> readRules(String key, RuleReader<T> reader) {
        String stored = preferences.get(key, "[]");
        try {
            JsonArray array = JsonParser.parseString(stored).getAsJsonArray();
            List<T> rules = new ArrayList<>();
            for (JsonElement element : array) {
                try {
                    T rule = reader.read(element.getAsJsonObject());
                    if (rule != null) {
                        rules.add(rule);
                    }
                } catch (RuntimeException ignored) {
                    // Skip malformed saved rule entries.
                }
            }
            return List.copyOf(rules);
        } catch (RuntimeException invalid) {
            return List.of();
        }
    }

    private <T> void writeRules(String key, List<T> rules, RuleWriter<T> writer) {
        JsonArray array = new JsonArray();
        for (T rule : rules) {
            array.add(writer.write(rule));
        }
        preferences.put(key, array.toString());
    }

    private String stringValue(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    private Path pathValue(JsonObject json, String key) {
        String value = stringValue(json, key);
        return value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }

    public record Coordinates(double latitude, double longitude, String locationLabel) { }

    public record CachedWeather(WeatherCondition condition, String locationLabel, double temperature, Instant checkedAt) {
        public CachedWeather(WeatherCondition condition, String locationLabel, Instant checkedAt) {
            this(condition, locationLabel, Double.NaN, checkedAt);
        }
    }

    private record ParsedDate(ZonedDateTime value, boolean allDay) { }

    private record CalendarEvent(String title, ZonedDateTime startsAt, ZonedDateTime endsAt, String key) { }

    @FunctionalInterface
    private interface RuleReader<T> {
        T read(JsonObject json);
    }

    @FunctionalInterface
    private interface RuleWriter<T> {
        JsonObject write(T rule);
    }
}
