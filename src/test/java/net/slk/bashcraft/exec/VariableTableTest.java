package net.slk.bashcraft.exec;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VariableTableTest {
    @Test
    void unscopedReadsOwnThenGlobalAndWritesOwn() throws Exception {
        Map<String, String> own = new HashMap<>();
        Map<String, String> global = new HashMap<>(Map.of("x", "g"));
        VariableTable table = new VariableTable(own, global);
        assertEquals(Optional.of("g"), table.get(Variables.Scope.ANY, "x"));
        table.set(Variables.Scope.ANY, "x", "o");
        assertEquals(Optional.of("o"), table.get(Variables.Scope.ANY, "x"));
        assertEquals(Optional.of("g"), table.get(Variables.Scope.GLOBAL, "x"));
        table.unset(Variables.Scope.OWN, "x");
        assertEquals(Optional.empty(), table.get(Variables.Scope.OWN, "x"));
    }

    @Test
    void sourcesWithoutAPlayerShareTheGlobalSet() throws Exception {
        Map<String, String> global = new HashMap<>();
        VariableTable table = new VariableTable(global, global);
        table.set(Variables.Scope.OWN, "x", "1");
        assertEquals(Optional.of("1"), table.get(Variables.Scope.GLOBAL, "x"));
    }

    @Test
    void limits() throws Exception {
        Map<String, String> own = new HashMap<>();
        VariableTable table = new VariableTable(own, new HashMap<>());
        assertEquals("value longer than 1024 characters",
                assertThrows(ExpansionException.class, () -> table.set(Variables.Scope.OWN, "x", "a".repeat(1025))).getMessage());
        for (int i = 0; i < 256; i++) {
            table.set(Variables.Scope.OWN, "v" + i, "1");
        }
        assertEquals("too many variables (max 256)",
                assertThrows(ExpansionException.class, () -> table.set(Variables.Scope.OWN, "extra", "1")).getMessage());
        table.set(Variables.Scope.OWN, "v0", "2");
        assertEquals(Optional.of("2"), table.get(Variables.Scope.OWN, "v0"));
    }

    @Test
    void refSplitsScopes() {
        assertEquals(new Variables.Ref(Variables.Scope.OWN, "x"), Variables.ref("my.x", Variables.Scope.ANY));
        assertEquals(new Variables.Ref(Variables.Scope.GLOBAL, "x"), Variables.ref("global.x", Variables.Scope.ANY));
        assertEquals(new Variables.Ref(Variables.Scope.ANY, "x"), Variables.ref("x", Variables.Scope.ANY));
    }

    @Test
    void elevatedSourcesReadTheGlobalSetUnlessTheyAskForMine() throws Exception {
        Map<String, String> mine = new HashMap<>(Map.of("x", "evil"));
        Map<String, String> global = new HashMap<>(Map.of("x", "1"));
        VariableTable table = new VariableTable(mine, global, false, true);
        assertEquals(Optional.of("1"), table.get(Variables.Scope.ANY, "x"), "a player's own x must not shadow the global x");
        assertEquals(Optional.of("evil"), table.get(Variables.Scope.OWN, "x"));
        table.set(Variables.Scope.ANY, "y", "2");
        assertEquals("2", global.get("y"));
    }

    @Test
    void globalWritesNeedPermission() throws Exception {
        Map<String, String> mine = new HashMap<>();
        Map<String, String> global = new HashMap<>(Map.of("g", "1"));
        VariableTable table = new VariableTable(mine, global, true, false);
        assertEquals("global variables need operator permission",
                assertThrows(ExpansionException.class, () -> table.set(Variables.Scope.GLOBAL, "g", "2")).getMessage());
        assertThrows(ExpansionException.class, () -> table.unset(Variables.Scope.GLOBAL, "g"));
        assertEquals("1", global.get("g"));
        table.set(Variables.Scope.ANY, "own", "3");
        assertEquals("3", mine.get("own"));
    }

    @Test
    void sourcesWithoutAPlayerNeedPermissionToWriteAtAll() {
        Map<String, String> global = new HashMap<>();
        VariableTable table = new VariableTable(global, global, false, false);
        assertThrows(ExpansionException.class, () -> table.set(Variables.Scope.ANY, "x", "1"));
    }

    @Test
    void longNamesAreRejected() {
        VariableTable table = new VariableTable(new HashMap<>(), new HashMap<>());
        assertEquals("variable name longer than 64 characters",
                assertThrows(ExpansionException.class, () -> table.set(Variables.Scope.OWN, "n".repeat(65), "1")).getMessage());
    }
}
