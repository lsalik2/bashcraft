package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AliasTableTest {
    private static AliasTable typed(Map<String, String> mine, Map<String, String> global) {
        return new AliasTable(new HashMap<>(mine), new HashMap<>(global), true, true, name -> name.equals("tp"));
    }

    @Test
    void personalFirstThenGlobal() throws Exception {
        AliasTable table = typed(Map.of("home", "say mine"), Map.of("home", "say global", "spawn", "tp @s 0 100 0"));
        assertEquals(Optional.of("say mine"), table.body("home"));
        assertEquals(Optional.of("tp @s 0 100 0"), table.body("spawn"));
        assertEquals(Optional.empty(), table.body("nope"));
        assertEquals(List.of("home = say mine", "global.home = say global", "global.spawn = tp @s 0 100 0"), table.listing());
    }

    @Test
    void elevatedSourcesSeeOnlyGlobal() throws Exception {
        AliasTable table = new AliasTable(new HashMap<>(Map.of("home", "say mine")), new HashMap<>(Map.of("home", "say global")), false, true, name -> false);
        assertEquals(Optional.of("say global"), table.body("home"));
        assertEquals("personal aliases can only be defined in chains you type",
                assertThrows(ExpansionException.class, () -> table.define(false, "x", "say")).getMessage());
        assertEquals(List.of("global.home = say global"), table.listing());
    }

    @Test
    void aliasNamesCantShadowCommandsOrBuiltins() {
        AliasTable table = typed(Map.of(), Map.of());
        assertEquals("alias name tp is already a command", assertThrows(ExpansionException.class, () -> table.define(false, "tp", "say")).getMessage());
        assertEquals("alias name echo is already a command", assertThrows(ExpansionException.class, () -> table.define(false, "echo", "say")).getMessage());
        assertEquals("alias name sh is already a command", assertThrows(ExpansionException.class, () -> table.define(true, "sh", "say")).getMessage());
    }

    @Test
    void permissionsAndLimits() throws Exception {
        AliasTable noOp = new AliasTable(new HashMap<>(), new HashMap<>(), true, false, name -> false);
        assertEquals("global aliases need operator permission", assertThrows(ExpansionException.class, () -> noOp.define(true, "g", "say")).getMessage());
        assertThrows(ExpansionException.class, () -> noOp.remove(true, "g"));
        AliasTable table = typed(Map.of(), Map.of());
        assertEquals("alias body longer than 1024 characters",
                assertThrows(ExpansionException.class, () -> table.define(false, "long", "a".repeat(1025))).getMessage());
        for (int i = 0; i < 64; i++) {
            table.define(false, "a" + i, "say");
        }
        assertEquals("too many aliases (max 64)", assertThrows(ExpansionException.class, () -> table.define(false, "extra", "say")).getMessage());
        table.define(false, "a0", "say again");
        table.remove(false, "a1");
        assertEquals(Optional.empty(), table.body("a1"));
    }

    @Test
    void aliasesNeverShadowCommandsRegisteredLater() {
        AliasTable table = typed(Map.of("tp", "say mine", "echo", "say x"), Map.of("tp", "say global"));
        assertEquals(Optional.empty(), table.body("tp"));
        assertEquals(Optional.empty(), table.body("echo"));
    }

    @Test
    void callableNamesAreValidAndCapped() {
        List<String> many = new java.util.ArrayList<>(List.of("ok", "Bad", "x".repeat(40), "a b"));
        for (int i = 0; i < 300; i++) {
            many.add("n" + i);
        }
        List<String> names = AliasTable.callable(many);
        assertTrue(names.stream().allMatch(name -> name.matches("[a-z_][a-z0-9_-]{0,31}")), names.toString());
        assertEquals(List.of("ok"), AliasTable.callable(List.of("ok", "Bad", "x".repeat(40), "a b")));
        assertEquals(AliasTable.MAX_CALLABLE, names.size());
    }
}
