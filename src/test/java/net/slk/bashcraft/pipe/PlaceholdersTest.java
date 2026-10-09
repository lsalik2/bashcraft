package net.slk.bashcraft.pipe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholdersTest {
    private static final PipeValue VILLAGE = new PipeValue.Coordinates("312", "~", "-1040");

    @Test
    void appendsWhenThereAreNoPlaceholders() throws Exception {
        assertEquals("tp @s 312 ~ -1040", Placeholders.inject("tp @s", VILLAGE, 845));
    }

    @Test
    void appendIgnoresTrailingWhitespace() throws Exception {
        assertEquals("tp @s 312 ~ -1040", Placeholders.inject("tp @s   ", VILLAGE, 845));
    }

    @Test
    void substitutesCoordinatePlaceholdersInsteadOfAppending() throws Exception {
        assertEquals("tp @s 312 100 -1040", Placeholders.inject("tp @s {x} 100 {z}", VILLAGE, 845));
    }

    @Test
    void negativeAndDecimalCoordinatesSurvive() throws Exception {
        PipeValue v = new PipeValue.Coordinates("-12.5", "64", "-0.5");
        assertEquals("tp @s -12.5 64 -0.5", Placeholders.inject("tp @s {x} {y} {z}", v, 1));
    }

    @Test
    void valueAndResultPlaceholders() throws Exception {
        assertEquals("scoreboard players set n obj 12.5", Placeholders.inject("scoreboard players set n obj {value}", new PipeValue.Numeric("12.5"), 3));
        assertEquals("say 845", Placeholders.inject("say {result}", new PipeValue.Text("hi"), 845));
    }

    @Test
    void nbtBracesAreNotPlaceholders() throws Exception {
        assertEquals("summon zombie 312 64 -1040 {NoAI:1b}", Placeholders.inject("summon zombie {x} 64 {z} {NoAI:1b}", VILLAGE, 1));
    }

    @Test
    void escapedPlaceholderIsLiteral() throws Exception {
        assertEquals("say {x} hi", Placeholders.inject("say \\{x} {value}", new PipeValue.Text("hi"), 1));
        assertEquals("say {x} hi", Placeholders.inject("say \\{x}", new PipeValue.Text("hi"), 1));
    }

    @Test
    void placeholderTheValueLacksIsAnError() {
        PipeException e = assertThrows(PipeException.class, () -> Placeholders.inject("tp @s {x} 0 0", new PipeValue.Numeric("5"), 5));
        assertEquals("{x} is not available from a number", e.getMessage());
    }

    @Test
    void maskKeepsLength() {
        assertEquals("tp @s 000 150 000", Placeholders.mask("tp @s {x} 150 {z}"));
        assertEquals("say 0000000 00000000", Placeholders.mask("say {value} {result}"));
        assertEquals("summon zombie {NoAI:1b}", Placeholders.mask("summon zombie {NoAI:1b}"));
    }

    @Test
    void escapedPlaceholdersAreNotMasked() {
        assertEquals("say \\{x} 000", Placeholders.mask("say \\{x} {y}"));
        assertEquals(List.of(9, 12), Placeholders.ranges("say \\{x} {y}").stream().map(r -> List.of(r[0], r[1])).toList().getFirst());
    }

    @Test
    void rangesFindEveryPlaceholder() {
        assertEquals(List.of(List.of(6, 9), List.of(14, 17)),
                Placeholders.ranges("tp @s {x} 150 {z}").stream().map(r -> List.of(r[0], r[1])).toList());
    }
}
