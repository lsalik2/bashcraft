package net.slk.bashcraft.chain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static net.slk.bashcraft.chain.NodeParser.Mode.SH;
import static net.slk.bashcraft.chain.NodeParser.Mode.TRANSPARENT;
import static org.junit.jupiter.api.Assertions.*;

class NodeParserTest {
    private static Node sh(String input) throws ChainSyntaxException {
        return NodeParser.parse(input, SH, 32);
    }

    @Test
    void singleCommand() throws Exception {
        Node chain = sh("tp @s ~ ~ ~");
        assertEquals(List.of(List.of("tp @s ~ ~ ~")), Flat.commands(chain));
        assertEquals(List.of(), Flat.joins(chain));
    }

    @Test
    void leadingSlashesAreOptionalAndStripped() throws Exception {
        Node chain = sh("/tp @s ~ ~ ~ && /summon minecraft:zombie");
        assertEquals(List.of(List.of("tp @s ~ ~ ~"), List.of("summon minecraft:zombie")), Flat.commands(chain));
        assertEquals(List.of(Operator.AND), Flat.joins(chain));
    }

    @Test
    void pipeBindsTighterThanListOperators() throws Exception {
        Node chain = sh("/a | /b && /c ; /d || /e");
        assertEquals(List.of(List.of("a", "b"), List.of("c"), List.of("d"), List.of("e")), Flat.commands(chain));
        assertEquals(List.of(Operator.AND, Operator.SEQ, Operator.OR), Flat.joins(chain));
        assertEquals(5, chain.commandCount());
    }

    @Test
    void doubleQuotesProtectOperators() throws Exception {
        assertEquals(List.of(List.of("say \"a && b\""), List.of("say c")), Flat.commands(sh("say \"a && b\" && say c")));
    }

    @Test
    void escapedQuoteInsideJsonDoesNotCloseIt() throws Exception {
        String json = "tellraw @a {\"text\":\"a \\\" | b\"}";
        assertEquals(List.of(List.of(json), List.of("say done")), Flat.commands(sh(json + " ; say done")));
    }

    @Test
    void apostropheInsideWordIsNotAQuote() throws Exception {
        assertEquals(List.of(List.of("say don't"), List.of("say hi")), Flat.commands(sh("say don't && say hi")));
    }

    @Test
    void singleQuotesAfterPunctuationProtectOperators() throws Exception {
        assertEquals(List.of(List.of("give @s stick[custom_name='a|b']"), List.of("say ok")),
                Flat.commands(sh("give @s stick[custom_name='a|b'] && say ok")));
    }

    @Test
    void backslashEscapesAnOperator() throws Exception {
        assertEquals(List.of(List.of("say use && here")), Flat.commands(sh("say use \\&& here")));
    }

    @Test
    void backslashBeforeOtherCharactersIsKept() throws Exception {
        assertEquals(List.of(List.of("tp @s \\{x} 64 0")), Flat.commands(sh("tp @s \\{x} 64 0")));
    }

    @Test
    void singleAmpersandIsLiteral() throws Exception {
        assertEquals(List.of(List.of("say a & b")), Flat.commands(sh("say a & b")));
    }

    @Test
    void emptyInputIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("   "));
        assertTrue(e.getMessage().startsWith("empty command"), e.getMessage());
        assertEquals(1, e.column());
    }

    @Test
    void leadingOperatorIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("&& /a"));
        assertEquals("unexpected '&&' at col 1", e.getMessage());
    }

    @Test
    void doubledOperatorIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("/a && && /b"));
        assertEquals("unexpected '&&' at col 7", e.getMessage());
    }

    @Test
    void danglingOperatorIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("/a &&"));
        assertEquals("missing command after '&&' at col 6", e.getMessage());
    }

    @Test
    void unclosedQuoteIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("say \"oops"));
        assertEquals("unclosed \" at col 5", e.getMessage());
    }

    @Test
    void tooManySegmentsIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> NodeParser.parse("a ; b | c", SH, 2));
        assertTrue(e.getMessage().startsWith("too many commands (max 2)"), e.getMessage());
    }

    @Test
    void transparentModeSplitsWhenNextSegmentStartsWithSlash() throws Exception {
        Node chain = NodeParser.parse("tp @s ~ ~ ~ && /summon minecraft:zombie", TRANSPARENT, 32);
        assertEquals(List.of(List.of("tp @s ~ ~ ~"), List.of("summon minecraft:zombie")), Flat.commands(chain));
    }

    @Test
    void transparentModeLeavesPlainTextAlone() throws Exception {
        assertEquals(List.of(List.of("say lunch; then work")), Flat.commands(NodeParser.parse("say lunch; then work", TRANSPARENT, 32)));
        assertEquals(List.of(List.of("say a && b")), Flat.commands(NodeParser.parse("say a && b", TRANSPARENT, 32)));
    }

    @Test
    void transparentModePipeWithoutSpaces() throws Exception {
        Node chain = NodeParser.parse("locate structure minecraft:village|/tp @s", TRANSPARENT, 32);
        assertEquals(List.of(List.of("locate structure minecraft:village", "tp @s")), Flat.commands(chain));
    }

    private static Node.Command cmd(String text) {
        return new Node.Command(text);
    }

    @Test
    void groupBindsBeforeOperators() throws Exception {
        Node expected = new Node.Sequence(List.of(
                new Node.Group(new Node.Sequence(List.of(cmd("a"), cmd("b")), List.of(Operator.OR))),
                cmd("c")), List.of(Operator.AND));
        assertEquals(expected, sh("(a || b) && c"));
    }

    @Test
    void groupsNestAndPipe() throws Exception {
        Node expected = new Node.Group(new Node.Pipeline(List.of(
                new Node.Group(new Node.Sequence(List.of(cmd("a"), cmd("b")), List.of(Operator.SEQ))),
                cmd("c"))));
        assertEquals(expected, sh("((a ; b) | c)"));
        assertEquals(3, expected.commandCount());
        assertTrue(expected.containsGroup());
        assertFalse(sh("a | b && c").containsGroup());
    }

    @Test
    void slashBeforeAGroupIsAllowed() throws Exception {
        Node expected = new Node.Sequence(List.of(cmd("a"),
                new Node.Group(new Node.Sequence(List.of(cmd("b"), cmd("c")), List.of(Operator.SEQ)))), List.of(Operator.AND));
        assertEquals(expected, sh("a && /(b ; c)"));
    }

    @Test
    void textParenthesesStayText() throws Exception {
        assertEquals(new Node.Group(new Node.Sequence(List.of(cmd("say (b)"), cmd("c")), List.of(Operator.OR))), sh("(say (b) || c)"));
        assertEquals(new Node.Sequence(List.of(cmd("say (hi)"), cmd("c")), List.of(Operator.AND)), sh("say (hi) && c"));
        assertEquals(cmd("say :)"), sh("say :)"));
    }

    @Test
    void quotedParenthesesStayText() throws Exception {
        assertEquals(new Node.Group(new Node.Sequence(List.of(cmd("tellraw @a {\"text\":\":)\"}"), cmd("c")), List.of(Operator.OR))),
                sh("(tellraw @a {\"text\":\":)\"} || c)"));
    }

    @Test
    void backslashParenthesesAreLiteral() throws Exception {
        assertEquals(new Node.Group(new Node.Sequence(List.of(cmd("say )"), cmd("c")), List.of(Operator.OR))), sh("(say \\) || c)"));
        assertEquals(cmd("(not a group)"), sh("\\(not a group\\)"));
    }

    @Test
    void emptyGroupIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("a && ()"));
        assertEquals("empty group at col 6", e.getMessage());
    }

    @Test
    void unclosedGroupIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("(a && b"));
        assertEquals("unclosed '(' at col 1", e.getMessage());
    }

    @Test
    void textAfterAGroupIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("(a) b"));
        assertEquals("unexpected text after ')' at col 5", e.getMessage());
    }

    @Test
    void nestingDeeperThan16IsAnError() throws Exception {
        assertEquals(1, sh("(".repeat(16) + "a" + ")".repeat(16)).commandCount());
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("(".repeat(17) + "a" + ")".repeat(17)));
        assertEquals("groups nested deeper than 16 at col 17", e.getMessage());
    }

    @Test
    void deepNestingDoesNotOverflow() {
        assertThrows(ChainSyntaxException.class, () -> sh("(".repeat(100_000) + "a"));
    }

    @Test
    void transparentGroupsAtTheStartAndAfterOperators() throws Exception {
        Node expected = new Node.Sequence(List.of(
                new Node.Group(new Node.Sequence(List.of(cmd("a"), cmd("b")), List.of(Operator.OR))), cmd("c")), List.of(Operator.AND));
        assertEquals(expected, NodeParser.parse("(/a || /b) && /c", TRANSPARENT, 32));
        assertEquals(expected, NodeParser.parse("/(/a || /b) && /c", TRANSPARENT, 32));
        assertEquals(new Node.Sequence(List.of(cmd("say a"), new Node.Group(cmd("b"))), List.of(Operator.AND)),
                NodeParser.parse("say a && (/b)", TRANSPARENT, 32));
        assertEquals(new Node.Sequence(List.of(cmd("say a"), new Node.Group(new Node.Group(cmd("b")))), List.of(Operator.AND)),
                NodeParser.parse("say a && ( (/b))", TRANSPARENT, 32));
    }

    @Test
    void transparentTextParenthesesAreNotGroups() throws Exception {
        assertEquals(cmd("say (hi) && b"), NodeParser.parse("say (hi) && b", TRANSPARENT, 32));
    }

    @Test
    void transparentParenthesesAfterAnOperatorNeedACommandInside() throws Exception {
        assertEquals(cmd("say I love you && (hug)"), NodeParser.parse("say I love you && (hug)", TRANSPARENT, 32));
        assertEquals(new Node.Sequence(List.of(cmd("tp @s 0 64 0"), cmd("say home ; (finally)")), List.of(Operator.AND)),
                NodeParser.parse("tp @s 0 64 0 && /say home ; (finally)", TRANSPARENT, 32));
    }

    @Test
    void strayCloseAfterAGroupIsAnError() {
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> sh("(say :) || b)"));
        assertEquals("unmatched ')', write \\) for a literal one at col 13", e.getMessage());
        assertThrows(ChainSyntaxException.class, () -> NodeParser.parse("(/say :) || /b)", TRANSPARENT, 32));
    }

    @Test
    void smileysWithoutGroupsAreStillText() throws Exception {
        assertEquals(new Node.Sequence(List.of(cmd("say :)"), cmd("say ;)")), List.of(Operator.AND)), sh("say :) && say \\;)"));
        assertEquals(new Node.Group(new Node.Sequence(List.of(cmd("say :)"), cmd("b")), List.of(Operator.OR))), sh("(say :\\) || b)"));
    }
}
