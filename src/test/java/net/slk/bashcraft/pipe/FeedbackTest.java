package net.slk.bashcraft.pipe;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackTest {
    @Test
    void argTextRendersNestedAndPlainArguments() {
        Feedback inner = Feedback.plain("[kills]");
        Feedback f = Feedback.translatable("commands.scoreboard.players.get.success", "Steve has 7 [kills]", "Steve", 7, inner);
        assertEquals(Optional.of("7"), f.argText(1));
        assertEquals(Optional.of("[kills]"), f.argText(2));
        assertEquals(Optional.empty(), f.argText(3));
    }

    @Test
    void findSearchesNestedArguments() {
        Feedback coords = Feedback.translatable("chat.coordinates", "1, ~, 2", 1, "~", 2);
        Feedback f = Feedback.translatable("outer", "x", Feedback.translatable("chat.square_brackets", "[1, ~, 2]", coords));
        assertEquals(Optional.of(coords), f.find("chat.coordinates"));
        assertEquals(Optional.empty(), f.find("missing"));
    }
}
