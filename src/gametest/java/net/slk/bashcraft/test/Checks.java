package net.slk.bashcraft.test;

/** Assertion helper for client game tests: a failed check crashes the test client and fails the Gradle task. */
final class Checks {
    private Checks() {}

    static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError("Check failed: " + what);
        }
    }
}
