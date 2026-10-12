package net.slk.bashcraft.chain;

import net.slk.bashcraft.chain.ChainLayout.Kind;
import net.slk.bashcraft.chain.ChainLayout.Span;
import net.slk.bashcraft.chain.ChainLayout.Window;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChainLayoutTest {
    private static ChainLayout input(String value) {
        return ChainLayout.forInput(value, true).orElseThrow();
    }

    @Test
    void commandsAndOperatorsWithAbsolutePositions() {
        assertEquals(List.of(new Span(Kind.COMMAND, 1, 12), new Span(Kind.OPERATOR, 13, 15), new Span(Kind.COMMAND, 17, 30)),
                input("/tp @s ~ ~ ~ && /summon zombie").spans());
    }

    @Test
    void shLinesStartAfterSh() {
        assertEquals(List.of(new Span(Kind.COMMAND, 4, 5), new Span(Kind.OPERATOR, 6, 7), new Span(Kind.COMMAND, 8, 9)),
                ChainLayout.forInput("/sh a | b", false).orElseThrow().spans());
    }

    @Test
    void groupsAndSlashes() {
        assertEquals(List.of(new Span(Kind.GROUP_OPEN, 1, 2), new Span(Kind.COMMAND, 3, 8), new Span(Kind.OPERATOR, 9, 11),
                        new Span(Kind.COMMAND, 13, 18), new Span(Kind.GROUP_CLOSE, 18, 19)),
                input("/(/say a || /say b)").spans());
    }

    @Test
    void quotesAndEscapesStayInsideTheCommand() {
        assertEquals(List.of(new Span(Kind.COMMAND, 4, 16), new Span(Kind.OPERATOR, 17, 19), new Span(Kind.COMMAND, 20, 21)),
                input("/sh say \"a && b\" && c").spans());
        assertEquals(List.of(new Span(Kind.COMMAND, 4, 15)), input("/sh say a \\&& b").spans());
    }

    @Test
    void partialLayoutOnErrors() {
        ChainLayout quote = input("/sh say a && say \"oops");
        assertEquals(List.of(new Span(Kind.COMMAND, 4, 9), new Span(Kind.OPERATOR, 10, 12), new Span(Kind.COMMAND, 13, 16)), quote.spans());
        assertTrue(quote.error().orElseThrow().getMessage().startsWith("unclosed \""));

        ChainLayout group = input("/(/say a || /say b");
        assertEquals(4, group.spans().size());
        assertEquals("unclosed '(' at col 1", group.error().orElseThrow().getMessage());
    }

    @Test
    void plainLinesAreNotChains() {
        assertTrue(ChainLayout.forInput("/tp @s ~ ~ ~", true).isEmpty());
        assertTrue(ChainLayout.forInput("/say a && b", true).isEmpty());
        assertTrue(ChainLayout.forInput("/say I love you && (hug)", true).isEmpty());
        assertTrue(ChainLayout.forInput("/tp @s ~ ~ ~ && /summon zombie", false).isEmpty());
    }

    @Test
    void shLinesCountWithoutTransparentModeAndCommandBlocksNeedNoSlash() {
        assertTrue(ChainLayout.forInput("/sh tp @s ~ ~ ~", false).isPresent());
        assertEquals(new Span(Kind.COMMAND, 0, 11), ChainLayout.forInput("tp @s ~ ~ ~ && /summon zombie", true).orElseThrow().spans().getFirst());
    }

    @Test
    void windowInsideACommand() {
        assertEquals(new Window(1, 12), input("/tp @s ~ ~ ~ && /summon zombie").window(5));
    }

    @Test
    void windowCoversTrailingWhitespace() {
        assertEquals(new Window(17, 24), input("/tp @s ~ ~ ~ && /summon ").window(24));
        assertEquals(new Window(1, 13), input("/tp @s ~ ~ ~ && /summon ").window(13));
    }

    @Test
    void windowOnAnOperatorIsEmpty() {
        ChainLayout layout = input("/tp @s ~ ~ ~ && /summon ");
        assertEquals(new Window(14, 14), layout.window(14));
        assertEquals(new Window(16, 16), layout.window(16));
    }

    @Test
    void noCommandStartsRightAfterAGroupOrInsideAnOperator() {
        ChainLayout layout = input("/(/say a) && /say b");
        assertFalse(layout.commandCanStartAt(9), "after ')' only an operator can follow");
        assertFalse(layout.commandCanStartAt(11), "inside '&&'");
        assertTrue(layout.commandCanStartAt(13), "after '&& '");
        assertTrue(layout.commandCanStartAt(16), "inside a command");
    }

    @Test
    void backgroundIsAnOperator() {
        assertEquals(List.of(new Span(Kind.COMMAND, 4, 5), new Span(Kind.OPERATOR, 6, 7), new Span(Kind.COMMAND, 8, 9)),
                ChainLayout.forInput("/sh a & b", false).orElseThrow().spans());
    }
}
