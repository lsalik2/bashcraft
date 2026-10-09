package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.NodeParser;
import net.slk.bashcraft.pipe.Feedback;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;

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

    private static VariableTable table() {
        Map<String, String> global = new HashMap<>();
        return new VariableTable(new HashMap<>(), global);
    }

    private static ExecResult run(FakeRunner runner, Variables variables, String chain) throws Exception {
        return new ChainExecutor(runner, variables, true, Integer.MAX_VALUE, 8).execute(NodeParser.parse(chain, NodeParser.Mode.SH, 32));
    }

    @Test
    void assignmentIsVisibleToTheNextCommand() throws Exception {
        FakeRunner r = new FakeRunner();
        VariableTable v = table();
        run(r, v, "x=11 && setblock $x 64 0 stone");
        assertEquals(List.of("setblock 11 64 0 stone"), r.ran);
        assertEquals(Optional.of("11"), v.get(Variables.Scope.OWN, "x"));
    }

    @Test
    void quotesAreStrippedAndScopesHonoured() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "home=\"0 80 0\" ; global.k=3 ; my.k=4 ; tp @s $home ; say $k $global.k");
        assertEquals(List.of("tp @s 0 80 0", "say 4 3"), r.ran);
    }

    @Test
    void unsetVariableFailsOnlyThatCommand() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "say $nope || say fallback");
        assertEquals(List.of("[1] $nope is not set"), r.errors);
        assertEquals(List.of("say fallback"), r.ran);
    }

    @Test
    void statusOfThePreviousCommand() throws Exception {
        FakeRunner r = new FakeRunner().on("a", FAIL);
        run(r, table(), "say $? ; a ; say $?");
        assertEquals(List.of("say 0", "a", "say 1"), r.ran);
    }

    @Test
    void substitutionInsertsTheExtractedValueAndHidesItsOutput() throws Exception {
        FakeRunner r = new FakeRunner().on("locate structure #minecraft:village", new ExecResult(true, 845, List.of(locateVillage())));
        run(r, table(), "tp @s $(locate structure #minecraft:village)");
        assertEquals(List.of("locate structure #minecraft:village", "tp @s 312 ~ -1040"), r.ran);
        assertTrue(r.options.get(0).swallowSuccess());
        assertFalse(r.options.get(1).swallowSuccess());
    }

    @Test
    void substitutionWithoutAValueFails() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "say $(say hi) || say x");
        assertEquals(List.of("[1] $(…) produced no value"), r.errors);
        assertEquals(List.of("say hi", "say x"), r.ran);
    }

    @Test
    void unsetBuiltin() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "x=1 ; unset x ; say $x");
        assertEquals(List.of("[3] $x is not set"), r.errors);
    }

    @Test
    void echoIsPipeable() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "x=7 ; echo $x | scoreboard players set n obj");
        assertEquals(List.of("7"), r.echoed);
        assertEquals(List.of("scoreboard players set n obj 7"), r.ran);
    }

    @Test
    void assignmentFromAPipe() throws Exception {
        FakeRunner r = new FakeRunner().on("locate structure #minecraft:village", new ExecResult(true, 845, List.of(locateVillage())));
        VariableTable v = table();
        run(r, v, "locate structure #minecraft:village | home=");
        assertEquals(Optional.of("312 ~ -1040"), v.get(Variables.Scope.OWN, "home"));
    }

    @Test
    void expandedValuesAreNotReparsed() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "x='a && /b' ; say $x");
        assertEquals(List.of("say a && /b"), r.ran);
    }

    @Test
    void substitutionDepthIsLimited() throws Exception {
        FakeRunner r = new FakeRunner();
        new ChainExecutor(r, table(), true, Integer.MAX_VALUE, 2).execute(NodeParser.parse("say $(echo $(echo $(echo 1)))", NodeParser.Mode.SH, 32));
        // The innermost failure is reported first; each enclosing $( … ) then reports that it got no value.
        assertEquals("$(…) nested deeper than 2", r.errors.getFirst());
        assertTrue(r.echoed.isEmpty());
        assertTrue(r.ran.isEmpty());
    }

    @Test
    void substitutionCommandsCountTowardTheBudget() throws Exception {
        try (DepthGuard.Scope outer = DepthGuard.enter(8).orElseThrow()) {
            FakeRunner r = new FakeRunner();
            new ChainExecutor(r, table(), true, 2, 8).execute(NodeParser.parse("say $(echo 1 ; echo 2)", NodeParser.Mode.SH, 32));
            assertEquals(List.of("1", "2"), r.echoed);
            assertEquals(List.of("too many commands across nested chains (max 2)"), r.errors);
        }
    }

    @Test
    void withoutVariablesAssignmentIsAnError() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, "x=1");
        assertEquals(List.of("variables are not available here"), r.errors);
    }

    @Test
    void variableTextIsNotAPlaceholder() throws Exception {
        FakeRunner r = new FakeRunner().on("get", new ExecResult(true, 5, List.of(Feedback.plain("5"))));
        run(r, table(), "q={value} ; get | say $q");
        assertEquals(List.of("get", "say {value} 5"), r.ran);
    }

    @Test
    void pipedTextIsNeverExpanded() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "echo \\$(danger) | say");
        assertEquals(List.of("say $(danger)"), r.ran);
    }

    @Test
    void valuesInsertedIntoANestedShAreEscaped() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "x='1 && /kill @e $(y)' ; sh say $x");
        assertEquals(List.of("sh say 1 \\&\\& /kill @e \\$\\(y\\)"), r.ran);
    }

    @Test
    void pipeValuesIntoANestedShAreEscaped() throws Exception {
        FakeRunner r = new FakeRunner().on("a", new ExecResult(true, 1, List.of(Feedback.plain("x;y"))));
        run(r, table(), "a | execute as @a run sh say");
        assertEquals("execute as @a run sh say x\\;y", r.ran.get(1));
    }

    @Test
    void aValueCantBecomeTheCommandThatRunsCommands() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "c=sh ; x='1 && /kill @e' ; $c say $x");
        assertEquals(List.of("[3] a value can't decide which commands run"), r.errors);
        assertTrue(r.ran.isEmpty());
    }

    @Test
    void aValueCantSupplyRun() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "r=run ; x='1 && /kill @e' ; execute as @a $r sh say $x ; execute as @a $(echo run) say hi");
        assertEquals(List.of("[3] a value can't decide which commands run", "[4] a value can't decide which commands run"), r.errors);
        assertTrue(r.ran.isEmpty());
    }

    @Test
    void aCommandNamedByAVariableStillGetsEscapedValues() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "c=say ; x='a && b' ; $c $x");
        assertEquals(List.of("say a \\&\\& b"), r.ran);
    }

    @Test
    void valuesInFunctionMacroArgumentsAreEscaped() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "x='1 && b' ; function ns:f {v:$x}");
        assertEquals(List.of("function ns:f {v:1 \\&\\& b}"), r.ran);
    }

    @Test
    void quotedValuesCantGoIntoCommandsThatRunCommands() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "x='a\" && /kill \"b' ; execute as @a run sh say $x");
        assertEquals(List.of("[2] a value with quotes can't go into a command that runs other commands"), r.errors);
        assertTrue(r.ran.isEmpty());
    }

    @Test
    void plainCommandsStillGetValuesAsIs() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "x='say \"hi\" && b' ; tellraw @a $x");
        assertEquals(List.of("tellraw @a say \"hi\" && b"), r.ran);
    }

    /** A chain run by a function as a player: unscoped names use the global set; the player's set is untrusted. */
    private static VariableTable elevated(Map<String, String> mine, Map<String, String> global) {
        return new VariableTable(new HashMap<>(mine), new HashMap<>(global), false, true);
    }

    private static final String JSON_ATTACK = "\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op Steve\"},\"text\":\"";

    @Test
    void elevatedChainsRefuseStructuredPlayerText() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, elevated(Map.of("nick", JSON_ATTACK), Map.of()), "tellraw @a {\"text\":\"$my.nick\"}");
        assertEquals(List.of("a value from a player can only be plain text here (letters, digits, spaces and _ . : ~ ^ + -)"), r.errors);
        assertTrue(r.ran.isEmpty());
    }

    @Test
    void elevatedChainsAcceptPlainPlayerValues() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, elevated(Map.of("home", "0 80.5 -3", "block", "minecraft:stone"), Map.of()), "setblock $my.home $my.block");
        assertEquals(List.of("setblock 0 80.5 -3 minecraft:stone"), r.ran);
    }

    @Test
    void elevatedPipeAndSubstitutionTextMustBePlain() throws Exception {
        FakeRunner r = new FakeRunner().on("get", new ExecResult(true, 1, List.of(Feedback.plain("a\"b"))));
        run(r, elevated(Map.of(), Map.of()), "get | say {value} ; say $(echo x{y) ; echo 7 | say {value}");
        assertEquals(2, r.errors.size());
        assertTrue(r.errors.stream().allMatch(e -> e.contains("a value from a player can only be plain text here")));
        assertEquals(List.of("get", "say 7"), r.ran);
    }

    @Test
    void elevatedChainsCantLaunderPlayerTextIntoTheGlobalSet() throws Exception {
        FakeRunner r = new FakeRunner();
        VariableTable v = elevated(Map.of("nick", JSON_ATTACK), Map.of());
        run(r, v, "x=$my.nick");
        assertEquals(Optional.empty(), v.get(Variables.Scope.GLOBAL, "x"));
        assertEquals(1, r.errors.size());
    }

    @Test
    void globalValuesAndPlayersOwnChainsAreUnrestricted() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, elevated(Map.of(), Map.of("j", "{\"text\":\"hi\"}")), "tellraw @a $j");
        assertEquals(List.of("tellraw @a {\"text\":\"hi\"}"), r.ran);
        FakeRunner own = new FakeRunner();
        run(own, table(), "x='a\"b' ; say $x");
        assertEquals(List.of("say a\"b"), own.ran);
    }
}
