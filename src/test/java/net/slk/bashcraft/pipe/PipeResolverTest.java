package net.slk.bashcraft.pipe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipeResolverTest {
    /** Mirrors LocateCommand: success(key, name, [chat.coordinates(x, y, z)], distance). */
    static Feedback locate(String key, Object y) {
        Feedback coords = Feedback.translatable("chat.coordinates", "312, " + y + ", -1040", 312, y, -1040);
        Feedback brackets = Feedback.translatable("chat.square_brackets", "[312, " + y + ", -1040]", coords);
        return Feedback.translatable(key, "The nearest minecraft:village is at [312, " + y + ", -1040] (845 blocks away)",
                "minecraft:village", brackets, 845);
    }

    private static PipeValue resolve(Feedback... output) throws PipeException {
        return PipeResolver.resolve(List.of(output));
    }

    @Test
    void locateStructureGivesCoordinatesWithTildeY() throws Exception {
        assertEquals(new PipeValue.Coordinates("312", "~", "-1040"), resolve(locate("commands.locate.structure.success", "~")));
    }

    @Test
    void locateBiomeKeepsY() throws Exception {
        assertEquals(new PipeValue.Coordinates("312", "64", "-1040"), resolve(locate("commands.locate.biome.success", 64)));
    }

    @Test
    void dataEntityNumberDropsNbtSuffix() throws Exception {
        Feedback f = Feedback.translatable("commands.data.entity.query", "Steve has the following entity data: 20.0f", "Steve", Feedback.plain("20.0f"));
        assertEquals(new PipeValue.Numeric("20.0"), resolve(f));
    }

    @Test
    void dataEntityStringDropsQuotes() throws Exception {
        Feedback f = Feedback.translatable("commands.data.entity.query", "x", "Steve", Feedback.plain("\"minecraft:stone\""));
        assertEquals(new PipeValue.Text("minecraft:stone"), resolve(f));
    }

    @Test
    void dataBlockUsesFourthArgument() throws Exception {
        Feedback f = Feedback.translatable("commands.data.block.query", "1, 2, 3 has the following block data: 5b", 1, 2, 3, Feedback.plain("5b"));
        assertEquals(new PipeValue.Numeric("5"), resolve(f));
    }

    @Test
    void timeQueryGametime() throws Exception {
        assertEquals(new PipeValue.Numeric("1234"),
                resolve(Feedback.translatable("commands.time.query.gametime", "Die Spielzeit ist 1234 Ticks und 5", 1234)));
    }

    @Test
    void timeQueryClockAndTimelineUseSecondArgument() throws Exception {
        assertEquals(new PipeValue.Numeric("600"),
                resolve(Feedback.translatable("commands.time.query.absolute", "Uhr minecraft:overworld 600 7", "minecraft:overworld", 600)));
        assertEquals(new PipeValue.Numeric("42"),
                resolve(Feedback.translatable("commands.time.query.timeline", "x 42 y 9", "minecraft:day", 42)));
        assertEquals(new PipeValue.Numeric("3"),
                resolve(Feedback.translatable("commands.time.query.timeline.repetitions", "x 3 y 9", "minecraft:day", 3)));
    }

    @Test
    void scoreboardGet() throws Exception {
        Feedback f = Feedback.translatable("commands.scoreboard.players.get.success", "Steve has 7 [kills]", "Steve", 7, Feedback.plain("[kills]"));
        assertEquals(new PipeValue.Numeric("7"), resolve(f));
    }

    @Test
    void fallbackFindsCoordinateTripleInText() throws Exception {
        assertEquals(new PipeValue.Coordinates("10", "64", "-20"), resolve(Feedback.plain("Spawn is at 10, 64, -20")));
    }

    @Test
    void fallbackTakesLastNumber() throws Exception {
        assertEquals(new PipeValue.Numeric("20"), resolve(Feedback.plain("There are 3 of a max of 20 players online")));
    }

    @Test
    void fallbackKeepsNbtSuffixedDecimals() throws Exception {
        assertEquals(new PipeValue.Numeric("20.0"), resolve(Feedback.plain("Health is 20.0f")));
        assertEquals(new PipeValue.Numeric("-0.5"), resolve(Feedback.plain("Offset -0.5d applied")));
        assertEquals(new PipeValue.Numeric("20"), resolve(Feedback.plain("There are 20 players online.")));
    }

    @Test
    void fallbackIgnoresDigitsInsideIds() throws Exception {
        assertEquals(new PipeValue.Text("Found minecraft:village2"), resolve(Feedback.plain("Found minecraft:village2")));
    }

    @Test
    void usesTheLastMessage() throws Exception {
        assertEquals(new PipeValue.Numeric("9"), resolve(Feedback.plain("first 1"), Feedback.plain("second 9")));
    }

    @Test
    void noOutputIsAnError() {
        assertThrows(PipeException.class, () -> PipeResolver.resolve(List.of()));
        assertThrows(PipeException.class, () -> resolve(Feedback.plain("   ")));
    }

    @Test
    void fallbackReadsSpaceSeparatedCoordinates() throws Exception {
        assertEquals(new PipeValue.Coordinates("100", "~", "-20"), resolve(Feedback.plain("100 ~ -20")));
        assertEquals(new PipeValue.Coordinates("^", "^1.5", "^"), resolve(Feedback.plain(" ^ ^1.5 ^ ")));
        assertEquals(new PipeValue.Numeric("4"), resolve(Feedback.plain("1 2 3 4")), "four numbers are not coordinates");
    }
}
