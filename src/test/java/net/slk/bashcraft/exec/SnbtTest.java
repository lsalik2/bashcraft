package net.slk.bashcraft.exec;

import net.slk.bashcraft.pipe.PipeValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnbtTest {
    @Test
    void numbers() {
        assertEquals("5", Snbt.of(new PipeValue.Numeric("5")));
        assertEquals("-12", Snbt.of(new PipeValue.Numeric("-12")));
        assertEquals("1.5d", Snbt.of(new PipeValue.Numeric("1.5")));
        assertEquals("9.999999999E9d", Snbt.of(new PipeValue.Numeric("9999999999")));
    }

    @Test
    void snbtQuotesText() {
        assertEquals("\"say \\\"hi\\\" \\\\ ok\"", Snbt.of(new PipeValue.Text("say \"hi\" \\ ok")));
        assertEquals("\"312 ~ -1040\"", Snbt.of(new PipeValue.Coordinates("312", "~", "-1040")));
    }

    @Test
    void lists() {
        assertEquals("[1,2]", Snbt.of(new PipeValue.Items(List.of("1", "2"))));
        assertEquals("[\"Alex\",\"Steve\"]", Snbt.of(new PipeValue.Items(List.of("Alex", "Steve"))));
        assertEquals("[]", Snbt.of(new PipeValue.Items(List.of())));
    }

    @Test
    void snbtMixedNumbersBecomeDoubles() {
        assertEquals("[1.0d,2.5d]", Snbt.of(new PipeValue.Items(List.of("1", "2.5"))));
    }

    @Test
    void leadingZeros() {
        assertEquals("7", Snbt.of(new PipeValue.Numeric("007")));
        assertEquals("[\"01\",\"02\"]", Snbt.of(new PipeValue.Items(List.of("01", "02"))));
        assertEquals("[0,10]", Snbt.of(new PipeValue.Items(List.of("0", "10"))));
    }
}
