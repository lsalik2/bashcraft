package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExpanderTest {
    private static Variables vars(String... pairs) {
        Map<String, String> own = new HashMap<>();
        Map<String, String> global = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            String name = pairs[i];
            if (name.startsWith("global.")) {
                global.put(name.substring(7), pairs[i + 1]);
            } else {
                own.put(name, pairs[i + 1]);
            }
        }
        return new VariableTable(own, global);
    }

    private static String expand(String text, Variables variables) throws ExpansionException {
        return Expander.expand(text, variables, 0, chain -> "<" + chain + ">");
    }

    @Test
    void namesAndBraces() throws Exception {
        assertEquals("say 1 1y", expand("say $x ${x}y", vars("x", "1")));
        assertEquals("tp @s 1.y", expand("tp @s $x.y", vars("x", "1")));
    }

    @Test
    void scopesAndLookupOrder() throws Exception {
        Variables v = vars("r", "own", "global.r", "shared", "global.g", "g");
        assertEquals("own shared own g", expand("$r $global.r $my.r $g", v));
        assertEquals("shared", expand("${global.r}", v));
    }

    @Test
    void statusOfThePreviousCommand() throws Exception {
        assertEquals("say 1", Expander.expand("say $?", vars(), 1, chain -> ""));
    }

    @Test
    void literalDollarsStay() throws Exception {
        assertEquals("say costs $5, $ and $-", expand("say costs $5, $ and $-", vars()));
        assertEquals("say $x", expand("say \\$x", vars()));
        assertEquals("say '$x'", expand("say '$x'", vars("x", "1")));
    }

    @Test
    void doubleQuotesExpandSingleQuotesDont() throws Exception {
        assertEquals("tellraw @a {\"text\":\"a \\\" 1\"}", expand("tellraw @a {\"text\":\"a \\\" $x\"}", vars("x", "1")));
        assertEquals("say \"it's 1\"", expand("say \"it's $x\"", vars("x", "1")));
    }

    @Test
    void substitutionsGoToTheCallback() throws Exception {
        assertEquals("tp @s <a && b>", expand("tp @s $(a && b)", vars()));
        assertEquals("tp @s <(x) | y>", expand("tp @s $((x) | y)", vars()));
    }

    @Test
    void unsetAndBadReferencesAreErrors() {
        assertEquals("$y is not set", assertThrows(ExpansionException.class, () -> expand("say $y", vars())).getMessage());
        assertEquals("$global.y is not set", assertThrows(ExpansionException.class, () -> expand("say ${global.y}", vars())).getMessage());
        assertEquals("bad ${…}", assertThrows(ExpansionException.class, () -> expand("say ${1x}", vars())).getMessage());
    }

    @Test
    void maskKeepsLengthAndEscapes() {
        assertEquals("tp @s 00 0000 000000 \\$z 00", Expander.mask("tp @s $x ${y} $(a b) \\$z $?"));
        assertEquals("say '$x'", Expander.mask("say '$x'"));
    }

    @Test
    void tooManyReferencesIsAnError() {
        String text = "say " + "$x".repeat(Expander.MAX_REFERENCES + 1);
        assertEquals("too many $ references (max 256)", assertThrows(ExpansionException.class, () -> expand(text, vars("x", "1"))).getMessage());
    }

    @Test
    void expandedTextIsCapped() {
        String text = "say " + "$x".repeat(40);
        assertEquals("expansion longer than 32767 characters",
                assertThrows(ExpansionException.class, () -> expand(text, vars("x", "a".repeat(1000)))).getMessage());
    }

    @Test
    void backslashBeforeAQuoteOpensTheQuoteLikeTheTokenizer() throws Exception {
        assertEquals("say \\'$x'", expand("say \\'$x'", vars("x", "1")));
    }

    private static String positional(String text, List<String> words) throws ExpansionException {
        return Expander.expandParts(text, vars(), 0, chain -> "", null, words).join(false);
    }

    @Test
    void positionalReferences() throws Exception {
        assertEquals("a b c a b c 2 d a", positional("$1 $2 $@ $# ${3:-d} ${1:-x}", List.of("a", "b c")));
        assertEquals("0", positional("$#", List.of()));
        assertEquals("$3 is not set", assertThrows(ExpansionException.class, () -> positional("say $3", List.of("a"))).getMessage());
    }

    @Test
    void positionalsAreLiteralOutsideAliases() throws Exception {
        assertEquals("say costs $1, $@ and $#", expand("say costs $1, $@ and $#", vars()));
        assertTrue(Expander.hasPositionals("say $1"));
        assertTrue(Expander.hasPositionals("say ${2:-x} && b"));
        assertFalse(Expander.hasPositionals("say $x '$1'"));
    }
}
