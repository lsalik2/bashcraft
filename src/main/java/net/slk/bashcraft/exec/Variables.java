package net.slk.bashcraft.exec;

import java.util.Optional;

/** Where chain variables live (spec C1 §5). Unscoped reads look in the source's own set, then the global one. */
public interface Variables {
    enum Scope { OWN, GLOBAL, ANY }

    record Ref(Scope scope, String name) {}

    Optional<String> get(Scope scope, String name);

    void set(Scope scope, String name, String value) throws ExpansionException;

    void unset(Scope scope, String name) throws ExpansionException;

    /** Splits {@code my.x} / {@code global.x} / {@code x}; an unscoped name gets {@code unscoped}. */
    static Ref ref(String qualified, Scope unscoped) {
        if (qualified.startsWith("my.")) {
            return new Ref(Scope.OWN, qualified.substring(3));
        }
        if (qualified.startsWith("global.")) {
            return new Ref(Scope.GLOBAL, qualified.substring(7));
        }
        return new Ref(unscoped, qualified);
    }

    /** No variables at all (tests and callers without storage). */
    Variables NONE = new Variables() {
        @Override
        public Optional<String> get(Scope scope, String name) {
            return Optional.empty();
        }

        @Override
        public void set(Scope scope, String name, String value) throws ExpansionException {
            throw new ExpansionException("variables are not available here");
        }

        @Override
        public void unset(Scope scope, String name) {}
    };
}
