package net.slk.bashcraft.chain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TransparentChainsTest {
    private static Optional<Chain> detect(String line) {
        return TransparentChains.detect(line, 32);
    }

    @Test
    void slashFollowedOperatorsMakeAChain() {
        Chain chain = detect("tp @s ~ ~ ~ && /summon minecraft:zombie").orElseThrow();
        assertEquals(List.of(new Pipeline(List.of("tp @s ~ ~ ~")), new Pipeline(List.of("summon minecraft:zombie"))), chain.pipelines());
    }

    @Test
    void plainTextIsNotAChain() {
        assertTrue(detect("say lunch; then work").isEmpty());
        assertTrue(detect("say a && b").isEmpty());
        assertTrue(detect("tp @s ~ ~ ~").isEmpty());
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
        assertTrue(TransparentChains.detect("a ; /b ; /c", 2).isEmpty());
    }
}
