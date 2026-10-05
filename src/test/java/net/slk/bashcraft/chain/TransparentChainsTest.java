package net.slk.bashcraft.chain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TransparentChainsTest {
    private static Optional<Node> detect(String line) {
        return TransparentChains.detect(line, 32);
    }

    @Test
    void slashFollowedOperatorsMakeAChain() {
        assertEquals(List.of(List.of("tp @s ~ ~ ~"), List.of("summon minecraft:zombie")),
                Flat.commands(detect("tp @s ~ ~ ~ && /summon minecraft:zombie").orElseThrow()));
    }

    @Test
    void plainTextIsNotAChain() {
        assertTrue(detect("say lunch; then work").isEmpty());
        assertTrue(detect("say a && b").isEmpty());
        assertTrue(detect("tp @s ~ ~ ~").isEmpty());
        assertTrue(detect("say (hi) && b").isEmpty());
    }

    @Test
    void groupsMakeAChainEvenWithOneCommand() {
        assertEquals(new Node.Group(new Node.Command("say hi")), detect("(/say hi)").orElseThrow());
        assertTrue(detect("/(/a || /b) && /c").isPresent());
    }

    @Test
    void shLinesAreLeftToTheShCommand() {
        assertTrue(detect("sh a && /b").isEmpty());
        assertTrue(detect("/sh a && /b").isEmpty());
    }

    @Test
    void unparseableLinesGoToVanilla() {
        assertTrue(detect("say \"oops && /say b").isEmpty());
        assertTrue(detect("say a && /").isEmpty());
        assertTrue(detect("(/a && /b").isEmpty());
        assertTrue(TransparentChains.detect("a ; /b ; /c", 2).isEmpty());
    }
}
