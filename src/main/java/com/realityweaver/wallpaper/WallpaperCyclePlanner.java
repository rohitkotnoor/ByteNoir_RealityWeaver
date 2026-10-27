package com.realityweaver.wallpaper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

/** Chooses a collection image without repeating a recent item when alternatives exist. */
public final class WallpaperCyclePlanner {
    private WallpaperCyclePlanner() {
    }

    public static Selection next(List<Path> available, List<String> recentIds,
            Function<Path, String> idForPath, Random random) {
        if (available == null || available.isEmpty()) {
            throw new IllegalArgumentException("A collection needs an available wallpaper to cycle.");
        }
        if (idForPath == null || random == null) {
            throw new IllegalArgumentException("A path identifier and random generator are required.");
        }

        List<String> availableIds = available.stream().map(idForPath).toList();
        int historyLimit = Math.min(3, Math.max(0, available.size() - 1));
        Set<String> recent = new HashSet<>();
        if (recentIds != null) {
            recentIds.stream().filter(availableIds::contains).limit(historyLimit).forEach(recent::add);
        }
        List<Integer> candidates = new ArrayList<>();
        for (int index = 0; index < available.size(); index++) {
            if (!recent.contains(availableIds.get(index))) {
                candidates.add(index);
            }
        }
        if (candidates.isEmpty()) {
            candidates.addAll(java.util.stream.IntStream.range(0, available.size()).boxed().toList());
        }

        Path selected = available.get(candidates.get(random.nextInt(candidates.size())));
        String selectedId = idForPath.apply(selected);
        List<String> updatedHistory = new ArrayList<>();
        updatedHistory.add(selectedId);
        if (recentIds != null) {
            for (String id : recentIds) {
                if (updatedHistory.size() >= 3) {
                    break;
                }
                if (availableIds.contains(id) && !updatedHistory.contains(id)) {
                    updatedHistory.add(id);
                }
            }
        }
        return new Selection(selected, List.copyOf(updatedHistory));
    }

    public record Selection(Path path, List<String> recentIds) {
        public Selection {
            recentIds = List.copyOf(recentIds);
        }
    }
}
