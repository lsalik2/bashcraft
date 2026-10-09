package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuiltinsTest {
    @Test
    void assignments() {
        assertEquals(new Builtins.Call(Builtins.Kind.ASSIGN, "x", "5"), Builtins.classify("x=5"));
        assertEquals(new Builtins.Call(Builtins.Kind.ASSIGN, "global.home", "\"0 80 0\""), Builtins.classify("global.home=\"0 80 0\""));
        assertEquals(new Builtins.Call(Builtins.Kind.ASSIGN, "x", ""), Builtins.classify("x="));
    }

    @Test
    void unsetAndEcho() {
        assertEquals(new Builtins.Call(Builtins.Kind.UNSET, "my.x", ""), Builtins.classify("unset my.x"));
        assertEquals(new Builtins.Call(Builtins.Kind.ECHO, "", "a  b"), Builtins.classify("echo a  b"));
        assertEquals(new Builtins.Call(Builtins.Kind.ECHO, "", ""), Builtins.classify("echo"));
    }

    @Test
    void builtinsNeedExactWords() {
        assertEquals(Builtins.Kind.NONE, Builtins.classify("echoes hi").kind());
        assertEquals(Builtins.Kind.NONE, Builtins.classify("unsetall").kind());
        assertEquals(Builtins.Kind.NONE, Builtins.classify("x == y").kind());
        assertEquals(Builtins.Kind.NONE, Builtins.classify("1x=2").kind());
        assertEquals(new Builtins.Call(Builtins.Kind.NONE, "", "say hi"), Builtins.classify("say hi"));
    }

    @Test
    void unquoteRemovesOneMatchingPair() {
        assertEquals("0 80 0", Builtins.unquote("\"0 80 0\""));
        assertEquals("a", Builtins.unquote("'a'"));
        assertEquals("\"a'", Builtins.unquote("\"a'"));
        assertEquals("\"", Builtins.unquote("\""));
    }
}
