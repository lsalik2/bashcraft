package net.slk.bashcraft.pipe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ItemsTest {
    private static PipeValue resolve(Feedback... output) throws PipeException {
        return PipeResolver.resolve(List.of(output));
    }

    @Test
    void listPlayers() throws Exception {
        assertEquals(new PipeValue.Items(List.of("Alex", "Steve")),
                resolve(Feedback.translatable("commands.list.players", "There are 2 of a max of 20 players online: Alex, Steve", 2, 20, Feedback.plain("Alex, Steve"))));
        assertEquals(new PipeValue.Items(List.of()),
                resolve(Feedback.translatable("commands.list.players", "There are 0 of a max of 20 players online: ", 0, 20, Feedback.plain(""))));
    }

    @Test
    void tagsTeamsAndScoreHolders() throws Exception {
        assertEquals(List.of("a", "boss"), resolve(Feedback.translatable("commands.tag.list.single.success", "x", "Steve", 2, Feedback.plain("a, boss"))).items());
        assertEquals(List.of("a"), resolve(Feedback.translatable("commands.tag.list.multiple.success", "x", 3, 1, Feedback.plain("a"))).items());
        assertEquals(List.of("Alex"), resolve(Feedback.translatable("commands.team.list.members.success", "x", "red", 1, Feedback.plain("Alex"))).items());
        assertEquals(List.of("blue", "red"), resolve(Feedback.translatable("commands.team.list.teams.success", "x", 2, Feedback.plain("blue, red"))).items());
        assertEquals(List.of("#fake", "Steve"), resolve(Feedback.translatable("commands.scoreboard.players.list.success", "x", 2, Feedback.plain("#fake, Steve"))).items());
    }

    @Test
    void dataGetLists() throws Exception {
        assertEquals(List.of("boss", "a"), resolve(Feedback.translatable("commands.data.entity.query", "x", "Steve", Feedback.plain("[\"boss\", \"a\"]"))).items());
        assertEquals(List.of("1", "2"), resolve(Feedback.translatable("commands.data.entity.query", "x", "Steve", Feedback.plain("[1b, 2b]"))).items());
    }

    @Test
    void nbtListKeepsCommasInsideStrings() {
        assertEquals(Optional.of(List.of("a, b", "c")), NbtList.elements("[\"a, b\", 'c']"));
        assertEquals(Optional.of(List.of("say \"hi\"")), NbtList.elements("[\"say \\\"hi\\\"\"]"));
    }

    @Test
    void nbtTypedAndNestedLists() {
        assertEquals(Optional.of(List.of("1", "2")), NbtList.elements("[I; 1, 2]"));
        assertEquals(Optional.of(List.of("[1, 2]", "{a: 1b, b: [3]}")), NbtList.elements("[[1, 2], {a: 1b, b: [3]}]"));
        assertEquals(Optional.of(List.of()), NbtList.elements("[]"));
        assertEquals(Optional.empty(), NbtList.elements("\"not a list\""));
    }

    @Test
    void itemsOfOtherValuesAndMessages() throws Exception {
        assertEquals(List.of("5"), new PipeValue.Numeric("5").items());
        assertEquals("Alex Steve", new PipeValue.Items(List.of("Alex", "Steve")).asArgument());
        assertEquals(Optional.of("Alex Steve"), new PipeValue.Items(List.of("Alex", "Steve")).placeholder("value"));
        assertEquals(Optional.empty(), new PipeValue.Items(List.of("a")).placeholder("x"));
        assertEquals(List.of("1", "9"), PipeResolver.items(List.of(Feedback.plain("first 1"), Feedback.plain("second 9"))));
        assertEquals(List.of("Alex", "Steve", "7"), PipeResolver.items(List.of(
                Feedback.translatable("commands.list.players", "x", 2, 20, Feedback.plain("Alex, Steve")), Feedback.plain("lucky 7"))));
    }

    @Test
    void itemsAreTyped() {
        assertEquals(new PipeValue.Numeric("12"), PipeValue.of("12"));
        assertEquals(new PipeValue.Numeric("-1.5"), PipeValue.of("-1.5"));
        assertEquals(new PipeValue.Text("Steve"), PipeValue.of("Steve"));
    }
}
