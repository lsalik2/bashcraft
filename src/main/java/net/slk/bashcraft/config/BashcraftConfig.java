package net.slk.bashcraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Settings from {@code config/bashcraft.json}. Missing keys keep their defaults. */
public final class BashcraftConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("bashcraft");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static BashcraftConfig instance = new BashcraftConfig();

    /** Enable the prefix-free hooks (milestone 2). */
    public boolean transparentMode = true;
    /** Max commands in one chain. */
    public int maxSegments = 32;
    /** Max nesting of chains inside chains (e.g. through functions). */
    public int maxDepth = 8;
    /** Max commands run by one chain including every chain nested inside it (stops function fork-bombs). */
    public int maxTotalCommands = 256;
    /** Hide success messages of commands that feed a pipe. */
    public boolean swallowPipedOutput = true;

    public static BashcraftConfig get() {
        return instance;
    }

    /** Reads the file, writing defaults if it doesn't exist. A broken file is logged and left alone; defaults are used. */
    public static BashcraftConfig load(Path file) {
        BashcraftConfig config = new BashcraftConfig();
        try {
            if (Files.exists(file)) {
                BashcraftConfig read = GSON.fromJson(Files.readString(file), BashcraftConfig.class);
                if (read != null) {
                    config = read;
                }
            } else {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(config));
            }
        } catch (IOException | JsonParseException e) {
            LOGGER.warn("BashCraft: could not read {}, using defaults: {}", file, e.getMessage());
            config = new BashcraftConfig();
        }
        config.maxSegments = Math.max(1, config.maxSegments);
        config.maxDepth = Math.max(1, config.maxDepth);
        config.maxTotalCommands = Math.max(1, config.maxTotalCommands);
        instance = config;
        return config;
    }
}
