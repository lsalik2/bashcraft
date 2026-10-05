package net.slk.bashcraft.chain;

/** A chain that can't be parsed; nothing in it runs. */
public class ChainSyntaxException extends Exception {
    private final int column;

    public ChainSyntaxException(String message, int column) {
        super(message + " at col " + column);
        this.column = column;
    }

    /** 1-based column of the problem. */
    public int column() {
        return column;
    }

    /** Vanilla-style context line: up to 10 characters of input before the error, then {@code <--[HERE]}. */
    public String context(String input) {
        int cursor = Math.min(input.length(), Math.max(0, column - 1));
        return (cursor > 10 ? "..." : "") + input.substring(Math.max(0, cursor - 10), cursor) + "<--[HERE]";
    }
}
