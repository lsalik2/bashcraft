package net.slk.bashcraft.chain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChainSyntaxExceptionTest {
    @Test
    void contextShowsTextBeforeTheError() {
        assertEquals("say <--[HERE]", new ChainSyntaxException("unclosed \"", 5).context("say \"oops"));
    }

    @Test
    void contextKeepsTenCharactersLikeVanilla() {
        String input = "setblock 0 64 0 stone && && say";
        assertEquals("... stone && <--[HERE]", new ChainSyntaxException("unexpected '&&'", 26).context(input));
    }

    @Test
    void contextAtEndOfInput() {
        assertEquals("/a &&<--[HERE]", new ChainSyntaxException("missing command after '&&'", 6).context("/a &&"));
    }
}
