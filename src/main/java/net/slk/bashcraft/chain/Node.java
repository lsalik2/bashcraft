package net.slk.bashcraft.chain;

import java.util.List;

/** A parsed chain (spec §4). The parser returns the smallest node: a lone command is a bare {@link Command}. */
public sealed interface Node {
    int commandCount();

    boolean containsGroup();

    /** One command: leading slash stripped, escapes resolved, quotes kept verbatim. */
    record Command(String text) implements Node {
        @Override
        public int commandCount() {
            return 1;
        }

        @Override
        public boolean containsGroup() {
            return false;
        }
    }

    /** Two or more stages joined by {@code |}; each stage is a Command or Group. */
    record Pipeline(List<Node> stages) implements Node {
        public Pipeline {
            stages = List.copyOf(stages);
        }

        @Override
        public int commandCount() {
            return stages.stream().mapToInt(Node::commandCount).sum();
        }

        @Override
        public boolean containsGroup() {
            return stages.stream().anyMatch(Node::containsGroup);
        }
    }

    /** Two or more items joined by {@code && || ;}, evaluated left to right; {@code joins.get(i)} sits between items i and i + 1. */
    record Sequence(List<Node> items, List<Operator> joins) implements Node {
        public Sequence {
            items = List.copyOf(items);
            joins = List.copyOf(joins);
            if (joins.size() != items.size() - 1) {
                throw new IllegalArgumentException("a sequence needs one fewer join than items");
            }
        }

        @Override
        public int commandCount() {
            return items.stream().mapToInt(Node::commandCount).sum();
        }

        @Override
        public boolean containsGroup() {
            return items.stream().anyMatch(Node::containsGroup);
        }
    }

    /** {@code ( … )}. */
    record Group(Node body) implements Node {
        @Override
        public int commandCount() {
            return body.commandCount();
        }

        @Override
        public boolean containsGroup() {
            return true;
        }
    }
}
