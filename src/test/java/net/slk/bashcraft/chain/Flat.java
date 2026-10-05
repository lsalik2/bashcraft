package net.slk.bashcraft.chain;

import java.util.List;

/** Views a group-free tree in the old flat shape: pipelines (lists of command texts) and the joins between them. */
final class Flat {
    private Flat() {}

    static List<List<String>> commands(Node node) {
        List<Node> items = node instanceof Node.Sequence sequence ? sequence.items() : List.of(node);
        return items.stream().map(Flat::stage).toList();
    }

    static List<Operator> joins(Node node) {
        return node instanceof Node.Sequence sequence ? sequence.joins() : List.of();
    }

    private static List<String> stage(Node item) {
        if (item instanceof Node.Pipeline pipeline) {
            return pipeline.stages().stream().map(stage -> ((Node.Command) stage).text()).toList();
        }
        return List.of(((Node.Command) item).text());
    }
}
