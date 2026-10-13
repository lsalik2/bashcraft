package net.slk.bashcraft.exec;

import java.util.List;
import java.util.Optional;

/** Alias definitions as a chain sees them (spec C4 §2–3). */
public interface Aliases {
    /** The body for {@code name}: the player's own aliases (only in a chain they typed), then global ones. */
    Optional<String> body(String name);

    void define(boolean global, String name, String body) throws ExpansionException;

    /** Throws {@code no alias NAME} when there is none to remove. */
    void remove(boolean global, String name) throws ExpansionException;

    /** {@code NAME = BODY} lines: own aliases (when visible), then {@code global.NAME = BODY}. */
    List<String> listing();

    Aliases NONE = new Aliases() {
        @Override
        public Optional<String> body(String name) {
            return Optional.empty();
        }

        @Override
        public void define(boolean global, String name, String body) throws ExpansionException {
            throw new ExpansionException("aliases are not available here");
        }

        @Override
        public void remove(boolean global, String name) throws ExpansionException {
            throw new ExpansionException("aliases are not available here");
        }

        @Override
        public List<String> listing() {
            return List.of();
        }
    };
}
