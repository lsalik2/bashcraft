package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.NodeParser;
import net.slk.bashcraft.pipe.Feedback;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChainExecutorTest {
    private static final ExecResult FAIL = ExecResult.failure();

    private static ExecResult run(FakeRunner runner, String chain) throws Exception {
        return new ChainExecutor(runner, true).execute(NodeParser.parse(chain, NodeParser.Mode.SH, 32));
    }

    private static Feedback locateVillage() {
        Feedback coords = Feedback.translatable("chat.coordinates", "312, ~, -1040", 312, "~", -1040);
        return Feedback.translatable("commands.locate.structure.success", "The nearest minecraft:village is at [312, ~, -1040] (845 blocks away)",
                "minecraft:village", Feedback.translatable("chat.square_brackets", "[312, ~, -1040]", coords), 845);
    }

    @Test
    void andRunsNextAfterSuccess() throws Exception {
        FakeRunner r = new FakeRunner();
        assertTrue(run(r, "a && b").success());
        assertEquals(List.of("a", "b"), r.ran);
    }

    @Test
    void andSkipsNextAfterFailure() throws Exception {
        FakeRunner r = new FakeRunner().on("a", FAIL);
        assertFalse(run(r, "a && b").success());
        assertEquals(List.of("a"), r.ran);
    }

    @Test
    void orRunsOnlyAfterFailure() throws Exception {
        FakeRunner failing = new FakeRunner().on("a", FAIL);
        run(failing, "a || b");
        assertEquals(List.of("a", "b"), failing.ran);

        FakeRunner passing = new FakeRunner();
        ExecResult result = run(passing, "a || b");
        assertEquals(List.of("a"), passing.ran);
        assertTrue(result.success(), "status of a skipped segment is the previous one's");
    }

    @Test
    void semicolonAlwaysRuns() throws Exception {
        FakeRunner r = new FakeRunner().on("a", FAIL);
        assertTrue(run(r, "a && x ; b").success());
        assertEquals(List.of("a", "b"), r.ran);
    }

    @Test
    void mixedOperatorsEvaluateLeftToRight() throws Exception {
        FakeRunner r = new FakeRunner().on("b", FAIL);
        run(r, "a && b || c");
        assertEquals(List.of("a", "b", "c"), r.ran);
    }

    @Test
    void pipeAppendsExtractedCoordinates() throws Exception {
        FakeRunner r = new FakeRunner().on("locate structure minecraft:village", new ExecResult(true, 845, List.of(locateVillage())));
        run(r, "locate structure minecraft:village | tp @s");
        assertEquals("tp @s 312 ~ -1040", r.ran.get(1));
    }

    @Test
    void pipePlaceholdersAndResult() throws Exception {
        FakeRunner r = new FakeRunner().on("locate structure minecraft:village", new ExecResult(true, 845, List.of(locateVillage())));
        run(r, "locate structure minecraft:village | tp @s {x} 120 {z} ; say done");
        assertEquals("tp @s 312 120 -1040", r.ran.get(1));

        FakeRunner r2 = new FakeRunner().on("a", new ExecResult(true, 7, List.of(Feedback.plain("ok"))));
        run(r2, "a | say {result}");
        assertEquals("say 7", r2.ran.get(1));
    }

    @Test
    void pipedOutputIsSwallowedUnlessDisabled() throws Exception {
        FakeRunner r = new FakeRunner().on("a", new ExecResult(true, 1, List.of(Feedback.plain("5"))));
        run(r, "a | b");
        assertTrue(r.options.get(0).swallowSuccess());
        assertFalse(r.options.get(1).swallowSuccess());

        FakeRunner shown = new FakeRunner().on("a", new ExecResult(true, 1, List.of(Feedback.plain("5"))));
        new ChainExecutor(shown, false).execute(NodeParser.parse("a | b", NodeParser.Mode.SH, 32));
        assertFalse(shown.options.get(0).swallowSuccess());
    }

    @Test
    void failurePrefixesNumberSegmentsAcrossPipelines() throws Exception {
        FakeRunner single = new FakeRunner();
        run(single, "a");
        assertNull(single.options.get(0).failurePrefix());

        FakeRunner r = new FakeRunner().on("b", new ExecResult(true, 1, List.of(Feedback.plain("5"))));
        run(r, "a ; b | c");
        assertEquals(List.of("[1] ", "[2] ", "[3] "), r.options.stream().map(RunOptions::failurePrefix).toList());
    }

    @Test
    void failedPipeSourceStopsThePipelineSilently() throws Exception {
        FakeRunner r = new FakeRunner().on("a", FAIL);
        assertFalse(run(r, "a | b").success());
        assertEquals(List.of("a"), r.ran);
        assertEquals(List.of(), r.errors, "the command's own error was already shown");
    }

    @Test
    void nothingToPipeReportsAndOrRecovers() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, "say hi | tp @s || say fallback");
        assertEquals(List.of("[2] nothing to pipe from /say hi"), r.errors);
        assertEquals(List.of("say hi", "say fallback"), r.ran);
    }

    @Test
    void pipeCarriesZeroAndNegativeResults() throws Exception {
        Feedback score = Feedback.translatable("commands.scoreboard.players.get.success", "n has -1040 [bc]", "n", -1040, Feedback.plain("[bc]"));
        FakeRunner r = new FakeRunner().on("scoreboard players get n bc", new ExecResult(false, false, -1040, List.of(score)));
        run(r, "scoreboard players get n bc | setblock {value} 120 0 minecraft:stone");
        assertEquals(List.of("scoreboard players get n bc", "setblock -1040 120 0 minecraft:stone"), r.ran);
    }

    @Test
    void zeroResultStillFailsForAnd() throws Exception {
        FakeRunner r = new FakeRunner().on("a", new ExecResult(false, false, 0, List.of(Feedback.plain("0"))));
        assertFalse(run(r, "a && b").success());
        assertEquals(List.of("a"), r.ran);
    }

    @Test
    void nestedChainsShareACommandBudget() throws Exception {
        try (DepthGuard.Scope outer = DepthGuard.enter(8).orElseThrow()) {
            FakeRunner r = new FakeRunner();
            new ChainExecutor(r, true, 3).execute(NodeParser.parse("a ; b", NodeParser.Mode.SH, 32));
            try (DepthGuard.Scope inner = DepthGuard.enter(8).orElseThrow()) {
                new ChainExecutor(r, true, 3).execute(NodeParser.parse("c ; d", NodeParser.Mode.SH, 32));
            }
            assertEquals(List.of("a", "b", "c"), r.ran);
            assertEquals(List.of("[2] too many commands across nested chains (max 3)"), r.errors);
        }
        try (DepthGuard.Scope next = DepthGuard.enter(8).orElseThrow()) {
            FakeRunner r = new FakeRunner();
            new ChainExecutor(r, true, 3).execute(NodeParser.parse("a ; b ; c", NodeParser.Mode.SH, 32));
            assertEquals(List.of("a", "b", "c"), r.ran, "the budget resets when the outermost chain ends");
        }
    }

    @Test
    void missingPlaceholderIsReported() throws Exception {
        FakeRunner r = new FakeRunner().on("time query gametime",
                new ExecResult(true, 50, List.of(Feedback.translatable("commands.time.query", "The time is 50", 50))));
        assertFalse(run(r, "time query gametime | tp @s {x} 0 0").success());
        assertEquals(List.of("[2] {x} is not available from a number"), r.errors);
    }

    @Test
    void groupStatusDrivesTheNextOperator() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, "(a || b) && c");
        assertEquals(List.of("a", "c"), r.ran);

        FakeRunner failing = new FakeRunner().on("b", FAIL);
        run(failing, "(a && b) || c");
        assertEquals(List.of("a", "b", "c"), failing.ran);
    }

    @Test
    void skippedGroupDoesNotRun() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, "a || (b ; c)");
        assertEquals(List.of("a"), r.ran);
    }

    @Test
    void groupPipesItsLastCommandsOutput() throws Exception {
        FakeRunner r = new FakeRunner()
                .on("locate structure #minecraft:nope", FAIL)
                .on("locate structure #minecraft:village", new ExecResult(true, 845, List.of(locateVillage())));
        run(r, "(locate structure #minecraft:nope || locate structure #minecraft:village) | tp @s");
        assertEquals(List.of("locate structure #minecraft:nope", "locate structure #minecraft:village", "tp @s 312 ~ -1040"), r.ran);
    }

    @Test
    void pipingIntoAGroupReachesOnlyItsFirstCommand() throws Exception {
        FakeRunner r = new FakeRunner().on("a", new ExecResult(true, 5, List.of(Feedback.plain("5"))));
        run(r, "a | (b ; c)");
        assertEquals(List.of("a", "b 5", "c"), r.ran);
    }

    @Test
    void numberingAndHiddenOutputAcrossGroups() throws Exception {
        FakeRunner r = new FakeRunner().on("b", new ExecResult(true, 1, List.of(Feedback.plain("5"))));
        run(r, "(a ; b) | c");
        assertEquals(List.of("[1] ", "[2] ", "[3] "), r.options.stream().map(RunOptions::failurePrefix).toList());
        assertEquals(List.of(true, true, false), r.options.stream().map(RunOptions::swallowSuccess).toList());
    }

    @Test
    void nothingToPipeFromAGroupNamesItsLastCommand() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, "(x ; a) | b");
        assertEquals(List.of("[3] nothing to pipe from /a"), r.errors);
    }
}
