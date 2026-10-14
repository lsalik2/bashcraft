package net.slk.bashcraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** The published metadata (spec D §3): fabric.mod.json, the version, the license and its Mojang notice. */
class ModMetadataTest {
    static final String SUMMARY = "A bash shell for the command line: set timers, chain commands and much more.";
    static final String NOTICE = "The BashCraft icon and the images in docs/images/ (brand art and screenshots) contain Minecraft textures and font © Mojang AB; they are not covered by the MIT license.";

    static JsonObject modJson() throws IOException {
        return JsonParser.parseString(Files.readString(Path.of("src/main/resources/fabric.mod.json"))).getAsJsonObject();
    }

    static String version() throws IOException {
        Properties p = new Properties();
        try (var in = Files.newInputStream(Path.of("gradle.properties"))) {
            p.load(in);
        }
        return p.getProperty("mod_version");
    }

    @Test
    void describesTheModForTheListings() throws IOException {
        JsonObject mod = modJson();
        assertEquals(SUMMARY, mod.get("description").getAsString());
        assertEquals("lsalik2", mod.getAsJsonArray("authors").get(0).getAsString());
        assertEquals(1, mod.getAsJsonArray("authors").size());
        assertEquals("MIT", mod.get("license").getAsString());
        JsonObject contact = mod.getAsJsonObject("contact");
        assertEquals("https://github.com/lsalik2/bashcraft/tree/main/docs/guide", contact.get("homepage").getAsString());
        assertEquals("https://github.com/lsalik2/bashcraft", contact.get("sources").getAsString());
        assertEquals("https://github.com/lsalik2/bashcraft/issues", contact.get("issues").getAsString());
    }

    @Test
    void iconExistsAtTheDeclaredPath() throws IOException {
        String icon = modJson().get("icon").getAsString();
        assertEquals("assets/bashcraft/icon.png", icon);
        byte[] png = Files.readAllBytes(Path.of("src/main/resources", icon));
        ByteBuffer header = ByteBuffer.wrap(png, 16, 8);
        assertEquals(192, header.getInt(), "width");
        assertEquals(192, header.getInt(), "height");
    }

    @Test
    void versionLooksLikeMajorMinorPatch() throws IOException {
        assertTrue(version().matches("\\d+\\.\\d+\\.\\d+"), "mod_version is N.N.N: " + version());
    }

    @Test
    void changelogCollectsChangesUnderUnreleasedFirst() throws IOException {
        String first = Files.readAllLines(Path.of("CHANGELOG.md")).stream()
                .filter(line -> line.startsWith("## ")).findFirst().orElse("");
        assertEquals("## Unreleased", first.strip(), "the release workflow renames this section");
    }

    @Test
    void licenseIsMitWithTheMojangNotice() throws IOException {
        String license = Files.readString(Path.of("LICENSE"));
        assertTrue(license.startsWith("MIT License"), "MIT License heading");
        assertTrue(license.contains("Copyright (c) 2026 lsalik2"));
        assertTrue(license.contains(NOTICE), "the Mojang notice");
    }

    @Test
    void versionMatchesChangelogAndListing() throws IOException {
        String version = version();
        assertTrue(Files.readString(Path.of("CHANGELOG.md")).contains("## " + version), "CHANGELOG has a " + version + " entry");
        // The listing lives only on the release machine (git-ignored): check it when present. Its version line isn't
        // checked, since the Release workflow sets the version and the listing is only for the project pages.
        Path listingFile = Path.of("docs/publishing/listing.md");
        String listing = Files.exists(listingFile) ? Files.readString(listingFile) : null;
        if (listing != null) {
            assertTrue(listing.contains(SUMMARY), "the listing has the summary");
        }
        assertTrue(Files.readString(Path.of("README.md")).contains(SUMMARY), "the README has the summary");
        assertTrue(Files.readString(Path.of("README.md")).contains("Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft."));
        assertTrue(Files.readString(Path.of("README.md")).contains(NOTICE), "the README has the Mojang notice");
        if (listing != null) {
            assertTrue(listing.replaceAll("\\s+", " ").contains(NOTICE), "the listing has the Mojang notice");
        }
    }
}
