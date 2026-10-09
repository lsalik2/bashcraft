package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HistoryTest {
    @Test
    void resolvesLastAndPrefix() {
        History history = new History();
        history.record("tp @s 0 80 0");
        history.record("locate structure #minecraft:village");
        history.record("say hi");
        assertEquals(Optional.of("say hi"), history.resolve("!!"));
        assertEquals(Optional.of("tp @s 0 80 0"), history.resolve("!tp"));
        assertEquals(Optional.of("locate structure #minecraft:village"), history.resolve("!locate"));
        assertEquals(Optional.empty(), history.resolve("!nope"));
        assertEquals(Optional.empty(), new History().resolve("!!"));
    }

    @Test
    void keepsTheLast50AndIgnoresBlanks() {
        History history = new History();
        for (int i = 0; i < 60; i++) {
            history.record("say " + i);
        }
        history.record("   ");
        assertEquals(History.MAX, history.lines().size());
        assertEquals("say 10", history.lines().getFirst());
        assertEquals("say 59", history.lines().getLast());
        assertEquals(List.of(), new History().lines());
    }
}
