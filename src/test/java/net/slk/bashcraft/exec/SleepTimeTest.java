package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SleepTimeTest {
    @Test
    void unitsLikeSchedule() throws Exception {
        assertEquals(20, SleepTime.ticks("20"));
        assertEquals(20, SleepTime.ticks("20t"));
        assertEquals(20, SleepTime.ticks("1s"));
        assertEquals(10, SleepTime.ticks("0.5s"));
        assertEquals(24000, SleepTime.ticks("1d"));
        assertEquals(1, SleepTime.ticks("1.26t"));
        assertEquals(30, SleepTime.ticks(" 1.5s "));
        assertEquals(Integer.MAX_VALUE, SleepTime.ticks("99999999999999999999d"), "the job table reports the limit");
    }

    @Test
    void badTimes() {
        assertEquals("sleep needs at least 1 tick", assertThrows(ExpansionException.class, () -> SleepTime.ticks("0")).getMessage());
        assertEquals("sleep needs at least 1 tick", assertThrows(ExpansionException.class, () -> SleepTime.ticks("0.4t")).getMessage());
        for (String bad : List.of("", "-5", "1m", "s", "20 ticks", "1e3", "NaN")) {
            assertEquals("sleep needs a time like 20, 20t, 1s or 1d",
                    assertThrows(ExpansionException.class, () -> SleepTime.ticks(bad)).getMessage(), bad);
        }
    }
}
