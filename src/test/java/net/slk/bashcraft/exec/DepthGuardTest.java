package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DepthGuardTest {
    @Test
    void refusesBeyondMaxAndRecoversAfterClose() {
        Optional<DepthGuard.Scope> first = DepthGuard.enter(2);
        Optional<DepthGuard.Scope> second = DepthGuard.enter(2);
        assertTrue(first.isPresent() && second.isPresent());
        assertTrue(DepthGuard.enter(2).isEmpty(), "third level is refused");

        second.get().close();
        Optional<DepthGuard.Scope> again = DepthGuard.enter(2);
        assertTrue(again.isPresent(), "closing frees a level");

        again.get().close();
        first.get().close();
    }

    @Test
    void closingTwiceOnlyFreesOneLevel() {
        DepthGuard.Scope scope = DepthGuard.enter(1).orElseThrow();
        scope.close();
        scope.close();
        DepthGuard.Scope next = DepthGuard.enter(1).orElseThrow();
        assertTrue(DepthGuard.enter(1).isEmpty());
        next.close();
    }

    @Test
    void theOutermostChainCanCarryOnABudgetAlreadySpent() {
        try (DepthGuard.Scope outer = DepthGuard.enter(4, 254).orElseThrow()) {
            assertEquals(254, DepthGuard.spent());
            assertTrue(DepthGuard.spend(256));
            try (DepthGuard.Scope inner = DepthGuard.enter(4, 0).orElseThrow()) {
                assertEquals(255, DepthGuard.spent(), "only the outermost chain takes a budget already spent");
                assertTrue(DepthGuard.spend(256));
            }
            assertFalse(DepthGuard.spend(256), "the carried budget runs out");
        }
        try (DepthGuard.Scope fresh = DepthGuard.enter(4).orElseThrow()) {
            assertEquals(0, DepthGuard.spent(), "the count resets when the outermost chain ends");
        }
    }
}
