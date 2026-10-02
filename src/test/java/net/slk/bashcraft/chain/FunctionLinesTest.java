package net.slk.bashcraft.chain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FunctionLinesTest {
    private static List<String> rewrite(String... lines) {
        return FunctionLines.rewrite(List.of(lines), 32);
    }

    @Test
    void chainLinesBecomeShLines() {
        assertEquals(List.of("sh say hi && say there"), rewrite("say hi && /say there"));
    }

    @Test
    void otherLinesAreUntouched() {
        List<String> lines = List.of("# a && /comment", "", "say lunch; then work", "sh say a && say b", "/say a && /say b", "  tp @s ~ ~ ~  ");
        assertEquals(lines, FunctionLines.rewrite(lines, 32));
    }

    @Test
    void macroLinesKeepTheirDollar() {
        assertEquals(List.of("$sh setblock $(x) 120 6 minecraft:stone && setblock 7 120 6 minecraft:stone"),
                rewrite("$setblock $(x) 120 6 minecraft:stone && /setblock 7 120 6 minecraft:stone"));
    }

    @Test
    void continuedChainsAreJoinedAndLineNumbersKept() {
        assertEquals(List.of("sh say a && say b", "", "say c"), rewrite("say a && \\", "/say b", "say c"));
    }

    @Test
    void continuedNonChainsAreUntouched() {
        List<String> lines = List.of("say a \\", "b", "say c");
        assertEquals(lines, FunctionLines.rewrite(lines, 32));
    }

    @Test
    void danglingContinuationIsLeftForVanillaToReport() {
        List<String> lines = List.of("say a && \\", "/say b \\");
        assertEquals(lines, FunctionLines.rewrite(lines, 32));
    }
}
