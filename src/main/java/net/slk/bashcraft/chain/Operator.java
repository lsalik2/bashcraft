package net.slk.bashcraft.chain;

/** How a pipeline is joined to the one before it. */
public enum Operator {
    /** {@code &&}: run if the previous pipeline succeeded. */
    AND,
    /** {@code ||}: run if the previous pipeline failed. */
    OR,
    /** {@code ;}: always run. */
    SEQ
}
