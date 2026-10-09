package net.slk.bashcraft.exec;

/** A command's {@code $} references couldn't be expanded, or a variable couldn't be written; the command doesn't run. */
public class ExpansionException extends Exception {
    public ExpansionException(String message) {
        super(message);
    }
}
