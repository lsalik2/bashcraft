package net.slk.bashcraft.chain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChainFormatterTest {
    private static void roundTrips(String transparentLine) throws Exception {
        Node node = NodeParser.parse(transparentLine, NodeParser.Mode.TRANSPARENT, 32);
        String sh = ChainFormatter.toSh(node);
        assertEquals(node, NodeParser.parse(sh, NodeParser.Mode.SH, 32), sh);
    }

    @Test
    void formatsOperatorsAndPipes() throws Exception {
        assertEquals("a | b && c ; d || e", ChainFormatter.toSh(NodeParser.parse("a | /b && /c ; /d || /e", NodeParser.Mode.TRANSPARENT, 32)));
    }

    @Test
    void operatorsThatTransparentModeIgnoredAreEscaped() throws Exception {
        Node node = NodeParser.parse("say lunch; then && /say a | b", NodeParser.Mode.TRANSPARENT, 32);
        assertEquals("say lunch\\; then && say a \\| b", ChainFormatter.toSh(node));
        roundTrips("say lunch; then && /say a | b");
    }

    @Test
    void formatsGroupsAndEscapesTextParentheses() throws Exception {
        assertEquals("(a || b) && c", ChainFormatter.toSh(NodeParser.parse("(/a || /b) && /c", NodeParser.Mode.TRANSPARENT, 32)));
        assertEquals("say \\(hi\\) && x", ChainFormatter.toSh(NodeParser.parse("say (hi) && /x", NodeParser.Mode.TRANSPARENT, 32)));
    }

    @Test
    void roundTripsQuotesBackslashesAndPlaceholders() throws Exception {
        roundTrips("tellraw @a {\"text\":\"a \\\" | b\"} && /say don't; x");
        roundTrips("give @s stick[custom_name='a;b'] ; /tp @s \\{x} {y} 0");
        roundTrips("say a\\b && /say c\\\\d");
    }

    @Test
    void roundTripsGroups() throws Exception {
        roundTrips("(/say (b) || /c) | /tp @s {x} 64 {z}");
        roundTrips("((/a ; /b) | /c) && /(/d)");
        roundTrips("(/tellraw @a {\"text\":\":)\"} || /say \\) x)");
    }

    @Test
    void roundTripsADoubledSlash() throws Exception {
        roundTrips("say a && //say b");
    }

    @Test
    void roundTripsSubstitutions() throws Exception {
        roundTrips("say $(/a && /b) && /tp @s $(/x | /y) 0");
        roundTrips("say \\$x && /say $( (/a) ; /b )");
        assertEquals("say $(a && b) && c", ChainFormatter.toSh(NodeParser.parse("say $(a && b) && /c", NodeParser.Mode.TRANSPARENT, 32)));
    }
}
