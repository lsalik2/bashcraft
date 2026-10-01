package net.slk.bashcraft.pipe;

/** A pipe value couldn't be produced or used. */
public class PipeException extends Exception {
    public PipeException(String message) {
        super(message);
    }
}
