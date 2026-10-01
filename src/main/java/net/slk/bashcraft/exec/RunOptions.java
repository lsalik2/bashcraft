package net.slk.bashcraft.exec;

/**
 * How to run one segment.
 *
 * @param swallowSuccess hide success messages (the segment feeds a pipe)
 * @param failurePrefix  prepended to error messages, e.g. "[2] "; null when the chain has one command
 */
public record RunOptions(boolean swallowSuccess, String failurePrefix) {}
