package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.NodeParser;
import net.slk.bashcraft.pipe.Feedback;
import net.slk.bashcraft.pipe.PipeValue;
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
        assertEquals(List.of("a value from a player can only be one plain word or a list of numbers here"), r.errors);
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
        assertTrue(r.errors.stream().allMatch(e -> e.contains("a value from a player can only be one plain word or a list of numbers here")));
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

    @Test
    void elevatedPlayerValuesCantAddArguments() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, elevated(Map.of("n", "1 store result storage bashcraft:vars global.x int 1"), Map.of()),
                "execute if score @p s matches $my.n run say hi");
        assertEquals(List.of("[1] a value from a player can only be one plain word or a list of numbers here").getFirst().substring(4), r.errors.getFirst());
        assertTrue(r.ran.isEmpty());
    }

    @Test
    void elevatedPlayerCoordinatesStillWork() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, elevated(Map.of("a", "312 ~ -1040", "b", "^ ^1.5 ^", "c", "~-2 +3 .5"), Map.of()), "tp @s $my.a ; tp @s $my.b ; tp @s $my.c");
        assertEquals(List.of("tp @s 312 ~ -1040", "tp @s ^ ^1.5 ^", "tp @s ~-2 +3 .5"), r.ran);
    }

    private static Feedback players(String... names) {
        return Feedback.translatable("commands.list.players", "x", names.length, 20, Feedback.plain(String.join(", ", names)));
    }

    @Test
    void grepKeepsMatchingItems() throws Exception {
        FakeRunner r = new FakeRunner().on("list", new ExecResult(true, 2, List.of(players("Alex", "Steve"))));
        run(r, table(), "list | grep steve | say ; list | grep -v alex | say ; list | grep -x ALEX | say");
        assertEquals(List.of("list", "say Steve", "list", "say Steve", "list", "say Alex"), r.ran);
    }

    @Test
    void grepStatusDrivesOperators() throws Exception {
        FakeRunner r = new FakeRunner().on("list", new ExecResult(true, 2, List.of(players("Alex", "Steve"))));
        run(r, table(), "list | grep zed || say none ; list | grep -x ale || say exact only");
        assertEquals(List.of("list", "say none", "list", "say exact only"), r.ran);
    }

    @Test
    void pipeBuiltinsNeedInputAndArguments() throws Exception {
        FakeRunner r = new FakeRunner().on("list", new ExecResult(true, 2, List.of(players("Alex"))));
        run(r, table(), "grep x ; list | grep ; list | store nowhere");
        assertEquals(List.of("[1] grep needs piped input", "[3] grep needs a pattern", "[5] store needs score HOLDER OBJECTIVE or storage ID PATH"), r.errors);
    }

    @Test
    void storeScoreRoundsDown() throws Exception {
        FakeRunner r = new FakeRunner()
                .on("a", new ExecResult(true, 1, List.of(Feedback.plain("12.7"))))
                .on("b", new ExecResult(true, 1, List.of(Feedback.plain("-3.5"))))
                .on("c", new ExecResult(true, 1, List.of(Feedback.plain("hello"))));
        run(r, table(), "a | store score @s best ; b | store score @e[type=cow, limit=1] best ; c | store score @s best");
        assertEquals(List.of("a", "scoreboard players set @s best 12", "b", "scoreboard players set @e[type=cow, limit=1] best -4", "c"), r.ran);
        assertEquals(List.of("[6] store score needs a number"), r.errors);
    }

    @Test
    void storeStorageWritesSnbtAndPassesTheValueOn() throws Exception {
        FakeRunner r = new FakeRunner().on("list", new ExecResult(true, 2, List.of(players("Alex", "Steve"))));
        run(r, table(), "list | store storage bashcraft:test names | say");
        assertEquals(List.of("list", "data modify storage bashcraft:test names set value [\"Alex\",\"Steve\"]", "say Alex Steve"), r.ran);
    }

    @Test
    void storeArgumentsDontGetThePipedValue() throws Exception {
        FakeRunner r = new FakeRunner().on("a", new ExecResult(true, 1, List.of(Feedback.plain("7"))));
        run(r, table(), "o=best ; a | store score @s $o");
        assertEquals("scoreboard players set @s best 7", r.ran.get(1));
    }

    @Test
    void eachRunsOncePerItem() throws Exception {
        FakeRunner r = new FakeRunner().on("list", new ExecResult(true, 2, List.of(players("Alex", "Steve"))));
        ExecResult result = run(r, table(), "list | each say hi {value} ; list | each tp");
        assertEquals(List.of("list", "say hi Alex", "say hi Steve", "list", "tp Alex", "tp Steve"), r.ran);
        assertTrue(result.success());
        assertEquals(2, result.result());
    }

    @Test
    void eachCollectsTheRunsValues() throws Exception {
        FakeRunner r = new FakeRunner()
                .on("list", new ExecResult(true, 2, List.of(players("Alex", "Steve"))))
                .on("xp Alex", new ExecResult(true, 5, List.of(Feedback.plain("5"))))
                .on("xp Steve", new ExecResult(true, 7, List.of(Feedback.plain("7"))));
        run(r, table(), "list | each xp {value} | store storage bc:s levels");
        assertEquals("data modify storage bc:s levels set value [5,7]", r.ran.getLast());
        assertEquals(List.of(true, true, false), r.options.stream().skip(1).limit(3).map(RunOptions::swallowSuccess).toList());
    }

    @Test
    void eachFailsWhenARunFailsOrThereAreNoItems() throws Exception {
        FakeRunner r = new FakeRunner()
                .on("list", new ExecResult(true, 2, List.of(players("Alex", "Steve"))))
                .on("empty", new ExecResult(true, 0, List.of(players())))
                .on("say Alex", FAIL);
        run(r, table(), "list | each say || say partial ; empty | each say || say none ; list | each");
        assertEquals(List.of("list", "say Alex", "say Steve", "say partial", "empty", "say none", "list"), r.ran);
        assertEquals(List.of("[8] each needs a command"), r.errors);
    }

    @Test
    void eachRunsCountTowardTheBudget() throws Exception {
        try (DepthGuard.Scope outer = DepthGuard.enter(8).orElseThrow()) {
            FakeRunner r = new FakeRunner().on("list", new ExecResult(true, 3, List.of(players("A", "B", "C"))));
            new ChainExecutor(r, table(), true, 3, 8).execute(NodeParser.parse("list | each say {value}", NodeParser.Mode.SH, 32));
            assertEquals(List.of("list", "say A", "say B"), r.ran);
            assertEquals(List.of("[2] too many commands across nested chains (max 3)"), r.errors);
        }
    }

    @Test
    void eachInAnElevatedChainChecksItems() throws Exception {
        FakeRunner r = new FakeRunner().on("names", new ExecResult(true, 2, List.of(players("Alex", "a\"b"))));
        run(r, elevated(Map.of(), Map.of()), "names | each say {value}");
        assertEquals(List.of("names", "say Alex"), r.ran);
        assertEquals(List.of("[2] a value from a player can only be one plain word or a list of numbers here"), r.errors);
    }

    @Test
    void eachStopsAtTheFirstRefusedItem() throws Exception {
        FakeRunner r = new FakeRunner().on("names", new ExecResult(true, 3, List.of(players("a\"b", "Alex", "c\"d"))));
        run(r, elevated(Map.of(), Map.of()), "names | each say {value}");
        assertEquals(1, r.errors.size());
        assertEquals(List.of("names"), r.ran);
    }

    @Test
    void surfaceReplacesY() throws Exception {
        FakeRunner r = new FakeRunner().on("locate structure #minecraft:village", new ExecResult(true, 845, List.of(locateVillage())));
        run(r, table(), "locate structure #minecraft:village | surface | tp @s");
        assertEquals("tp @s 312 70 -1040", r.ran.getLast());
        assertEquals(List.of("312,-1040"), r.surfaced);
    }

    @Test
    void surfaceAcceptsThreeNumberLists() throws Exception {
        FakeRunner r = new FakeRunner().on("pos", new ExecResult(true, false, 1, List.of(), new PipeValue.Items(List.of("10.5", "64.0", "-3.2"))));
        run(r, table(), "pos | surface | tp @s");
        assertEquals("tp @s 10.5 70 -3.2", r.ran.getLast());
        assertEquals(List.of("10,-4"), r.surfaced);
    }

    @Test
    void surfaceNeedsAbsoluteCoordinatesAndAPipe() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "echo ~ 64 ~ | surface ; echo hello | surface ; surface ; echo 1 2 3 | surface x");
        assertEquals(List.of("[2] surface needs absolute x and z", "[4] surface needs coordinates", "[5] surface needs piped input",
                "[7] surface takes no arguments"), r.errors);
        assertTrue(r.surfaced.isEmpty());
    }

    @Test
    void surfaceRefusedByTheRunnerFails() throws Exception {
        FakeRunner r = new FakeRunner();
        r.refuseSurface = true;
        run(r, table(), "echo 1 2 3 | surface || say refused");
        assertEquals(List.of("[2] surface needs operator permission"), r.errors);
        assertEquals(List.of("say refused"), r.ran);
    }

    @Test
    void surfaceRefusesCoordinatesOutsideTheWorldBeforeConvertingThem() throws Exception {
        FakeRunner r = new FakeRunner();
        run(r, table(), "echo -10000000000 ~ 0 | surface ; echo 0 ~ 99999999999999999999 | surface ; echo 30000000 ~ 0 | surface");
        assertEquals(List.of("[2] surface: outside the world", "[4] surface: outside the world", "[6] surface: outside the world"), r.errors);
        assertTrue(r.surfaced.isEmpty(), "the runner must never be asked for an out-of-world column");
    }
}
