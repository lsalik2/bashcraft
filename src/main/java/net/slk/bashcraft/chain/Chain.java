package net.slk.bashcraft.chain;

import java.util.List;

/** Pipelines joined by operators, evaluated left to right: {@code joins.get(i)} sits between pipeline i and i + 1. */
public record Chain(List<Pipeline> pipelines, List<Operator> joins) {
    public Chain {
        pipelines = List.copyOf(pipelines);
        joins = List.copyOf(joins);
        if (pipelines.isEmpty() || joins.size() != pipelines.size() - 1) {
            throw new IllegalArgumentException("a chain needs one fewer join than pipelines");
        }
    }

    public int segmentCount() {
        return pipelines.stream().mapToInt(p -> p.commands().size()).sum();
    }

    /** Temporary bridge for callers not yet on Node (removed with this class in Task 3). */
    public Node toNode() {
        List<Node> items = pipelines.stream()
                .map(p -> p.commands().size() == 1
                        ? (Node) new Node.Command(p.commands().getFirst())
                        : new Node.Pipeline(p.commands().stream().<Node>map(Node.Command::new).toList()))
                .toList();
        return items.size() == 1 ? items.getFirst() : new Node.Sequence(items, joins);
    }
}
