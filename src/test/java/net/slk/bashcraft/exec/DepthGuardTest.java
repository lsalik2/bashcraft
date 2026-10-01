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
}
