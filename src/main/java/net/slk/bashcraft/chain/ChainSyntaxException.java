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
}
