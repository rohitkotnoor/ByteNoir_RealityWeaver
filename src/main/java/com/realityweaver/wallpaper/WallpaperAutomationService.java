package com.realityweaver.wallpaper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/** Runs saved time, weather, and calendar wallpaper rules while the app is open. */
public final class WallpaperAutomationService {
    private static final String ENABLED_KEY = "automation.enabled";
    private static final String TIME_RULES_KEY = "automation.timeRules";
    private static final String WEATHER_RULES_KEY = "automation.weatherRules";
    private static final String CALENDAR_RULES_KEY = "automation.calendarRules";
    private static final String CALENDAR_FILE_KEY = "automation.calendarFile";
    private static final String OUTLOOK_ENABLED_KEY = "automation.outlookEnabled";
    private static final String AUTO_LOCATION_ENABLED_KEY = "automation.autoLocationEnabled";
    private static final String MANUAL_OVERRIDE_KEY = "automation.manualOverrideUntil";
    private static final int MAX_RULES_PER_TYPE = 30;
    private static final Duration WEATHER_CACHE_AGE = Duration.ofMinutes(15);
    private static final Duration OUTLOOK_CACHE_AGE = Duration.ofMinutes(1);
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
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
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
    private volatile boolean schedulerStarted;
    private volatile Consumer<String> statusListener = ignored -> { };
    private volatile Instant manualOverrideUntil = Instant.EPOCH;

    public WallpaperAutomationService() {
        this(Preferences.userNodeForPackage(WallpaperAutomationService.class), new WallpaperService());
    }

    WallpaperAutomationService(Preferences preferences, WallpaperService wallpaperService) {
        this.preferences = preferences;
        this.wallpaperService = wallpaperService;
        long savedOverride = preferences.getLong(MANUAL_OVERRIDE_KEY, 0L);
        if (savedOverride > 0) {
            manualOverrideUntil = Instant.ofEpochMilli(savedOverride);
        }
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

    public record TimeRule(String id, String time, Path wallpaper) { }

    public record WeatherRule(String id, String city, boolean automaticLocation,
            WeatherCondition condition, Path wallpaper) { }

    public record CalendarRule(String id, String titleContains, Path wallpaper) { }

    public boolean isEnabled() {
        return preferences.getBoolean(ENABLED_KEY, false);
    }

    public void setEnabled(boolean enabled, Consumer<String> listener) {
        statusListener = listener == null ? ignored -> { } : listener;
        preferences.putBoolean(ENABLED_KEY, enabled);
        if (enabled) {
            startSchedulerIfNeeded();
            publish("Automation is on. Checking saved rules.");
            checkNow();
        } else {
            publish("Automation is off.");
        }
    }

    public void startIfEnabled(Consumer<String> listener) {
        statusListener = listener == null ? ignored -> { } : listener;
        if (isEnabled()) {
            startSchedulerIfNeeded();
            publish("Automation is on. Checking saved rules.");
            checkNow();
        }
    }

    public void setStatusListener(Consumer<String> listener) {
        statusListener = listener == null ? ignored -> { } : listener;
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

    public boolean isManualOverrideActive() {
        return Instant.now().isBefore(manualOverrideUntil);
    }

    /** Applies a collection-cycle image only while automation is enabled and no manual override is active. */
    public boolean applyCollectionWallpaper(Path wallpaper, String collectionName) {
        if (!isEnabled() || isManualOverrideActive()) {
            return false;
        }
        return applyRule(wallpaper, "collection “" + collectionName + "”");
    }

    public void close() {
        scheduler.shutdownNow();
    }

    public List<TimeRule> timeRules() {
        return readRules(TIME_RULES_KEY, element -> new TimeRule(
                stringValue(element, "id"), stringValue(element, "time"), pathValue(element, "wallpaper")));
    }

    public List<WeatherRule> weatherRules() {
        return readRules(WEATHER_RULES_KEY, element -> {
            WeatherCondition condition;
            try {
                condition = WeatherCondition.valueOf(stringValue(element, "condition"));
            } catch (IllegalArgumentException invalid) {
                return null;
            }
            return new WeatherRule(stringValue(element, "id"), stringValue(element, "city"),
                    element.has("automaticLocation") && element.get("automaticLocation").getAsBoolean(),
                    condition, pathValue(element, "wallpaper"));
        });
    }

    public List<CalendarRule> calendarRules() {
        return readRules(CALENDAR_RULES_KEY, element -> new CalendarRule(
                stringValue(element, "id"), stringValue(element, "titleContains"),
                pathValue(element, "wallpaper")));
    }

    public Path calendarFile() {
        String value = preferences.get(CALENDAR_FILE_KEY, "");
        return value.isBlank() ? null : Path.of(value);
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

    public void addTimeRule(String time, Path wallpaper) {
        LocalTime parsedTime = LocalTime.parse(time);
        List<TimeRule> rules = new ArrayList<>(timeRules());
        if (rules.stream().anyMatch(rule -> LocalTime.parse(rule.time()).equals(parsedTime))) {
            throw new IllegalArgumentException("A daily rule already exists for that time.");
        }
        ensureRoom(rules.size());
        rules.add(new TimeRule(UUID.randomUUID().toString(), time, normalize(wallpaper)));
        writeRules(TIME_RULES_KEY, rules, rule -> {
            JsonObject json = baseRule(rule.id(), rule.wallpaper());
            json.addProperty("time", rule.time());
            return json;
        });
    }

    public void removeTimeRule(String id) {
        List<TimeRule> rules = new ArrayList<>(timeRules());
        if (rules.removeIf(rule -> rule.id().equals(id))) {
            writeRules(TIME_RULES_KEY, rules, rule -> {
                JsonObject json = baseRule(rule.id(), rule.wallpaper());
                json.addProperty("time", rule.time());
                return json;
            });
        }
    }

    public void addWeatherRule(String city, WeatherCondition condition, Path wallpaper) {
        addWeatherRule(city, condition, wallpaper, false);
    }

    public void addWeatherRule(String city, WeatherCondition condition, Path wallpaper, boolean automaticLocation) {
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
                normalize(wallpaper)));
        writeRules(WEATHER_RULES_KEY, rules, rule -> {
            JsonObject json = baseRule(rule.id(), rule.wallpaper());
            json.addProperty("city", rule.city());
            json.addProperty("automaticLocation", rule.automaticLocation());
            json.addProperty("condition", rule.condition().name());
            return json;
        });
    }

    public void removeWeatherRule(String id) {
        List<WeatherRule> rules = new ArrayList<>(weatherRules());
        if (rules.removeIf(rule -> rule.id().equals(id))) {
            writeRules(WEATHER_RULES_KEY, rules, rule -> {
                JsonObject json = baseRule(rule.id(), rule.wallpaper());
                json.addProperty("city", rule.city());
                json.addProperty("automaticLocation", rule.automaticLocation());
                json.addProperty("condition", rule.condition().name());
                return json;
            });
        }
        if (id.equals(activeWeatherRuleId)) {
            activeWeatherRuleId = null;
            lastTimeScheduleKey = null;
        }
    }

    public void addCalendarRule(String titleContains, Path wallpaper) {
        String normalizedTitle = titleContains == null ? "" : titleContains.strip();
        if (normalizedTitle.isBlank()) {
            throw new IllegalArgumentException("Enter a title or keyword from the calendar event.");
        }
        List<CalendarRule> rules = new ArrayList<>(calendarRules());
        if (rules.stream().anyMatch(rule -> rule.titleContains().equalsIgnoreCase(normalizedTitle))) {
            throw new IllegalArgumentException("A calendar rule already exists for that title keyword.");
        }
        ensureRoom(rules.size());
        rules.add(new CalendarRule(UUID.randomUUID().toString(), normalizedTitle, normalize(wallpaper)));
        writeRules(CALENDAR_RULES_KEY, rules, rule -> {
            JsonObject json = baseRule(rule.id(), rule.wallpaper());
            json.addProperty("titleContains", rule.titleContains());
            return json;
        });
    }

    public void removeCalendarRule(String id) {
        List<CalendarRule> rules = new ArrayList<>(calendarRules());
        if (rules.removeIf(rule -> rule.id().equals(id))) {
            writeRules(CALENDAR_RULES_KEY, rules, rule -> {
                JsonObject json = baseRule(rule.id(), rule.wallpaper());
                json.addProperty("titleContains", rule.titleContains());
                return json;
            });
        }
        preferences.remove("automation.lastCalendarEvent." + id);
        lastCalendarApplication.remove(id);
    }

    public void removeWallpaper(Path wallpaper) {
        Path normalized = normalize(wallpaper);
        List<TimeRule> time = new ArrayList<>(timeRules());
        if (time.removeIf(rule -> rule.wallpaper().equals(normalized))) {
            writeRules(TIME_RULES_KEY, time, rule -> {
                JsonObject json = baseRule(rule.id(), rule.wallpaper());
                json.addProperty("time", rule.time());
                return json;
            });
        }

        List<WeatherRule> weather = new ArrayList<>(weatherRules());
        if (weather.removeIf(rule -> rule.wallpaper().equals(normalized))) {
            writeRules(WEATHER_RULES_KEY, weather, rule -> {
                JsonObject json = baseRule(rule.id(), rule.wallpaper());
                json.addProperty("city", rule.city());
                json.addProperty("automaticLocation", rule.automaticLocation());
                json.addProperty("condition", rule.condition().name());
                return json;
            });
        }

        List<CalendarRule> calendar = new ArrayList<>(calendarRules());
        if (calendar.removeIf(rule -> rule.wallpaper().equals(normalized))) {
            writeRules(CALENDAR_RULES_KEY, calendar, rule -> {
                JsonObject json = baseRule(rule.id(), rule.wallpaper());
                json.addProperty("titleContains", rule.titleContains());
                return json;
            });
        }
    }

    private synchronized void startSchedulerIfNeeded() {
        if (schedulerStarted) {
            return;
        }
        schedulerStarted = true;
        scheduler.scheduleWithFixedDelay(this::checkRulesSafely, 1, 1, TimeUnit.MINUTES);
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
        List<TimeRule> time = timeRules();
        List<WeatherRule> weather = weatherRules();
        List<CalendarRule> calendar = calendarRules();
        if (time.isEmpty() && weather.isEmpty() && calendar.isEmpty()) {
            publish("Automation is on. Add a time, weather, or calendar rule.");
            return;
        }

        String latestIssue = null;
        ZonedDateTime now = ZonedDateTime.now();

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
                    latestIssue = "Local Outlook calendar is unavailable. Choose a local .ics file to use calendar rules.";
                }
            } else if (calendarFile() == null) {
                latestIssue = "Enable the local Outlook calendar or choose a local .ics file to use calendar rules.";
            }

            for (CalendarRule rule : calendar) {
                CalendarEvent event = outlookEvents.stream()
                        .filter(candidate -> candidate.title().toLowerCase(Locale.ROOT)
                                .contains(rule.titleContains().toLowerCase(Locale.ROOT)))
                        .findFirst().orElse(null);
                Path file = calendarFile();
                if (event == null && file != null && Files.isRegularFile(file)) {
                    try {
                        event = activeEvent(file, rule.titleContains(), now);
                    } catch (IOException | RuntimeException failure) {
                        latestIssue = "Local .ics calendar could not be checked: " + safeMessage(failure);
                    }
                }
                if (event != null) {
                    lastTimeScheduleKey = null;
                    activeWeatherRuleId = null;
                    String previousEvent = lastCalendarApplication.computeIfAbsent(rule.id(), id ->
                            preferences.get("automation.lastCalendarEvent." + id, ""));
                    if (isNewCalendarEvent(event.key(), previousEvent)) {
                        if (applyRule(rule.wallpaper(), "calendar event “" + event.title() + "”")) {
                            lastCalendarApplication.put(rule.id(), event.key());
                            preferences.put("automation.lastCalendarEvent." + rule.id(), event.key());
                        }
                    }
                    return;
                }
            }
        }
        for (WeatherRule rule : weather) {
            try {
                CachedWeather current = currentWeather(rule.city(), rule.automaticLocation());
                if (current.condition() == rule.condition()) {
                    if (!rule.id().equals(activeWeatherRuleId)) {
                        if (applyRule(rule.wallpaper(), current.locationLabel() + " weather: " + current.condition())) {
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

        TimeRule activeTimeRule = activeTimeRule(time, now);
        if (activeTimeRule != null) {
            String scheduleKey = timeScheduleKey(activeTimeRule, now);
            if (!scheduleKey.equals(lastTimeScheduleKey)) {
                LocalTime trigger = LocalTime.parse(activeTimeRule.time());
                if (applyRule(activeTimeRule.wallpaper(), "local time "
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

    private CachedWeather currentWeather(String city, boolean automaticLocation)
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
                    + "&longitude=" + longitude + "&current=weather_code&timezone=auto");
            JsonObject current = response.getAsJsonObject("current");
            if (current == null || !current.has("weather_code")) {
                throw new IOException("The weather service did not return current conditions.");
            }
            WeatherCondition condition = classifyWeather(current.get("weather_code").getAsInt());
            if (condition == null) {
                throw new IOException("The current weather condition is not recognized.");
            }
            weatherRetryAfter.remove(normalized);
            CachedWeather result = new CachedWeather(condition, coordinates.locationLabel(), now);
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
        HttpResponse<String> response = httpClient.send(request,
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
        List<String> unfoldedLines = new ArrayList<>();
        for (String line : Files.readAllLines(calendar, StandardCharsets.UTF_8)) {
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
            throw new IllegalArgumentException("Choose a wallpaper image first.");
        }
        return path.toAbsolutePath().normalize();
    }

    private JsonObject baseRule(String id, Path wallpaper) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("wallpaper", wallpaper.toString());
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

    private record Coordinates(double latitude, double longitude, String locationLabel) { }

    private record CachedWeather(WeatherCondition condition, String locationLabel, Instant checkedAt) { }

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
