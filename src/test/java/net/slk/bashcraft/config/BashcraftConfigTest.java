package net.slk.bashcraft.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BashcraftConfigTest {
    @TempDir
    Path dir;

    @Test
    void missingFileGivesDefaultsAndIsWritten() throws Exception {
        Path file = dir.resolve("config/bashcraft.json");
        BashcraftConfig config = BashcraftConfig.load(file);
        assertTrue(config.transparentMode);
        assertEquals(32, config.maxSegments);
        assertEquals(8, config.maxDepth);
        assertEquals(256, config.maxTotalCommands);
        assertTrue(config.swallowPipedOutput);
        assertTrue(Files.readString(file).contains("\"maxSegments\": 32"));
        assertSame(config, BashcraftConfig.get());
    }

    @Test
    void partialFileKeepsOtherDefaults() throws Exception {
        Path file = dir.resolve("bashcraft.json");
        Files.writeString(file, "{\"maxDepth\": 3}");
        BashcraftConfig config = BashcraftConfig.load(file);
        assertEquals(3, config.maxDepth);
        assertEquals(32, config.maxSegments);
    }

    @Test
    void brokenFileFallsBackToDefaultsAndIsLeftAlone() throws Exception {
        Path file = dir.resolve("bashcraft.json");
        Files.writeString(file, "{not json");
        BashcraftConfig config = BashcraftConfig.load(file);
        assertEquals(32, config.maxSegments);
        assertEquals("{not json", Files.readString(file));
    }

    @Test
    void limitsAreClampedToAtLeastOne() throws Exception {
        Path file = dir.resolve("bashcraft.json");
        Files.writeString(file, "{\"maxSegments\": 0, \"maxDepth\": -4}");
        BashcraftConfig config = BashcraftConfig.load(file);
        assertEquals(1, config.maxSegments);
        assertEquals(1, config.maxDepth);
    }
}
