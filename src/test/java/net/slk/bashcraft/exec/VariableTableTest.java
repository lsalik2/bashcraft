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
}
