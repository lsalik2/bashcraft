package net.slk.bashcraft.chain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChainFormatterTest {
    private static void roundTrips(String transparentLine) throws Exception {
        Chain chain = ChainParser.parse(transparentLine, ChainParser.Mode.TRANSPARENT, 32);
        String sh = ChainFormatter.toSh(chain);
        assertEquals(chain, ChainParser.parse(sh, ChainParser.Mode.SH, 32), sh);
    }

    @Test
    void formatsOperatorsAndPipes() throws Exception {
        Chain chain = ChainParser.parse("a | /b && /c ; /d || /e", ChainParser.Mode.TRANSPARENT, 32);
        assertEquals("a | b && c ; d || e", ChainFormatter.toSh(chain));
    }

    @Test
    void operatorsThatTransparentModeIgnoredAreEscaped() throws Exception {
        Chain chain = ChainParser.parse("say lunch; then && /say a | b", ChainParser.Mode.TRANSPARENT, 32);
        assertEquals("say lunch\\; then && say a \\| b", ChainFormatter.toSh(chain));
        roundTrips("say lunch; then && /say a | b");
    }

    @Test
    void roundTripsQuotesBackslashesAndPlaceholders() throws Exception {
        roundTrips("tellraw @a {\"text\":\"a \\\" | b\"} && /say don't; x");
        roundTrips("give @s stick[custom_name='a;b'] ; /tp @s \\{x} {y} 0");
        roundTrips("say a\\b && /say c\\\\d");
        roundTrips("setblock $(x) 120 6 minecraft:stone && /setblock 7 120 6 minecraft:stone");
    }
}
