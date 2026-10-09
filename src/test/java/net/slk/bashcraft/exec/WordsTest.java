package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WordsTest {
    @Test
    void wordsKeepQuotedSpans() {
        assertEquals(List.of("a", "b c", "d"), Words.split("a 'b c'  \"d\""));
        assertEquals(List.of("{\"text\":\"x y\"}"), Words.split("{\"text\":\"x y\"}"));
        assertEquals(List.of(), Words.split("   "));
    }
}
