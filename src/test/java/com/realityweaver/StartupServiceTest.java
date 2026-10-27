package com.realityweaver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class StartupServiceTest {
    @Test
    void reportsLocalDataFolderUnderTheCurrentUserHome() {
        StartupService service = new StartupService();

        assertEquals(Path.of(System.getProperty("user.home"), ".realityweaver").toAbsolutePath().normalize(),
                service.dataFolder());
    }

    @Test
    void doesNotChangeStartupFromAnUnpackagedDeveloperProcess() {
        StartupService service = new StartupService();

        assertFalse(service.isSupported());
        assertThrows(UnsupportedOperationException.class, () -> service.setEnabled(true));
    }
}
