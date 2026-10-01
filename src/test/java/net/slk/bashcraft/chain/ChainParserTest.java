package net.slk.bashcraft.chain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static net.slk.bashcraft.chain.ChainParser.Mode.SH;
import static net.slk.bashcraft.chain.ChainParser.Mode.TRANSPARENT;
import static org.junit.jupiter.api.Assertions.*;

class ChainParserTest {
    private static List<List<String>> commands(Chain chain) {
        return chain.pipelines().stream().map(Pipeline::commands).toList();
    }

    private static Chain sh(String input) throws ChainSyntaxException {
        return ChainParser.parse(input, SH, 32);
    }

    @Test
    void singleCommand() throws Exception {
        Chain chain = sh("tp @s ~ ~ ~");
        assertEquals(List.of(List.of("tp @s ~ ~ ~")), commands(chain));
        assertEquals(List.of(), chain.joins());
    }

    @Test
    void leadingSlashesAreOptionalAndStripped() throws Exception {
        Chain chain = sh("/tp @s ~ ~ ~ && /summon minecraft:zombie");
        assertEquals(List.of(List.of("tp @s ~ ~ ~"), List.of("summon minecraft:zombie")), commands(chain));
        assertEquals(List.of(Operator.AND), chain.joins());
    }

    @Test
    void pipeBindsTighterThanListOperators() throws Exception {
        Chain chain = sh("/a | /b && /c ; /d || /e");
        assertEquals(List.of(List.of("a", "b"), List.of("c"), List.of("d"), List.of("e")), commands(chain));
        assertEquals(List.of(Operator.AND, Operator.SEQ, Operator.OR), chain.joins());
        assertEquals(5, chain.segmentCount());
    }

    @Test
    void doubleQuotesProtectOperators() throws Exception {
        assertEquals(List.of(List.of("say \"a && b\""), List.of("say c")), commands(sh("say \"a && b\" && say c")));
    }

    @Test
    void escapedQuoteInsideJsonDoesNotCloseIt() throws Exception {
        String json = "tellraw @a {\"text\":\"a \\\" | b\"}";
        assertEquals(List.of(List.of(json), List.of("say done")), commands(sh(json + " ; say done")));
    }

    @Test
    void apostropheInsideWordIsNotAQuote() throws Exception {
        assertEquals(List.of(List.of("say don't"), List.of("say hi")), commands(sh("say don't && say hi")));
    }

    @Test
    void singleQuotesAfterPunctuationProtectOperators() throws Exception {
        assertEquals(List.of(List.of("give @s stick[custom_name='a|b']"), List.of("say ok")),
                commands(sh("give @s stick[custom_name='a|b'] && say ok")));
    }

    @Test
    void backslashEscapesAnOperator() throws Exception {
        assertEquals(List.of(List.of("say use && here")), commands(sh("say use \\&& here")));
    }

    @Test
    void backslashBeforeOtherCharactersIsKept() throws Exception {
        assertEquals(List.of(List.of("tp @s \\{x} 64 0")), commands(sh("tp @s \\{x} 64 0")));
    }

    @Test
    void singleAmpersandIsLiteral() throws Exception {
        assertEquals(List.of(List.of("say a & b")), commands(sh("say a & b")));
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
        ChainSyntaxException e = assertThrows(ChainSyntaxException.class, () -> ChainParser.parse("a ; b | c", SH, 2));
        assertTrue(e.getMessage().startsWith("too many commands (max 2)"), e.getMessage());
    }

    @Test
    void transparentModeSplitsWhenNextSegmentStartsWithSlash() throws Exception {
        Chain chain = ChainParser.parse("tp @s ~ ~ ~ && /summon minecraft:zombie", TRANSPARENT, 32);
        assertEquals(List.of(List.of("tp @s ~ ~ ~"), List.of("summon minecraft:zombie")), commands(chain));
    }

    @Test
    void transparentModeLeavesPlainTextAlone() throws Exception {
        assertEquals(List.of(List.of("say lunch; then work")), commands(ChainParser.parse("say lunch; then work", TRANSPARENT, 32)));
        assertEquals(List.of(List.of("say a && b")), commands(ChainParser.parse("say a && b", TRANSPARENT, 32)));
    }

    @Test
    void transparentModePipeWithoutSpaces() throws Exception {
        Chain chain = ChainParser.parse("locate structure minecraft:village|/tp @s", TRANSPARENT, 32);
        assertEquals(List.of(List.of("locate structure minecraft:village", "tp @s")), commands(chain));
    }
}
