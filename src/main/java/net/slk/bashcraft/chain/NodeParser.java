package net.slk.bashcraft.chain;

import net.slk.bashcraft.chain.Tokenizer.Kind;
import net.slk.bashcraft.chain.Tokenizer.Token;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for chains (spec §2). {@code &&}, {@code ||} and {@code ;} are kept in one left-to-right
 * {@link Node.Sequence}: evaluating them in order with bash's status rules gives the same result as nesting
 * {@code &&}/{@code ||} inside {@code ;}.
 */
public final class NodeParser {
    public enum Mode {
        /** {@code /sh}: every unquoted operator counts. */
        SH,
        /** Typed directly: an operator only counts when the next non-space character is {@code /} or {@code (}. */
        TRANSPARENT
    }

    static final int MAX_GROUP_DEPTH = 16;

    private final List<Token> tokens;
    private final List<Integer> commandColumns = new ArrayList<>();
    private int pos;
    private int depth;
    private Token lastOperator;

    private NodeParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Node parse(String input, Mode mode, int maxSegments) throws ChainSyntaxException {
        NodeParser parser = new NodeParser(Tokenizer.tokenize(input, mode));
        Node node = parser.sequence();
        Token end = parser.peek();
        if (end.kind() != Kind.END) {
            throw new ChainSyntaxException("unexpected '" + end.text() + "'", end.column());
        }
        if (node.commandCount() > maxSegments) {
            throw new ChainSyntaxException("too many commands (max " + maxSegments + ")", parser.commandColumns.get(maxSegments));
        }
        return node;
    }

    private Node sequence() throws ChainSyntaxException {
        List<Node> items = new ArrayList<>(List.of(pipeline()));
        List<Operator> joins = new ArrayList<>();
        while (true) {
            Operator op = switch (peek().kind()) {
                case AND -> Operator.AND;
                case OR -> Operator.OR;
                case SEQ -> Operator.SEQ;
                default -> null;
            };
            if (op == null) {
                break;
            }
            lastOperator = next();
            items.add(pipeline());
            joins.add(op);
        }
        return items.size() == 1 ? items.getFirst() : new Node.Sequence(items, joins);
    }

    private Node pipeline() throws ChainSyntaxException {
        List<Node> stages = new ArrayList<>(List.of(unit()));
        while (peek().kind() == Kind.PIPE) {
            lastOperator = next();
            stages.add(unit());
        }
        return stages.size() == 1 ? stages.getFirst() : new Node.Pipeline(stages);
    }

    private Node unit() throws ChainSyntaxException {
        Token token = peek();
        switch (token.kind()) {
            case TEXT -> {
                next();
                String command = clean(token.text());
                if (command.isEmpty()) {
                    throw new ChainSyntaxException("missing command", token.column());
                }
                commandColumns.add(token.column());
                return new Node.Command(command);
            }
            case OPEN -> {
                next();
                if (++depth > MAX_GROUP_DEPTH) {
                    throw new ChainSyntaxException("groups nested deeper than " + MAX_GROUP_DEPTH, token.column());
                }
                if (peek().kind() == Kind.CLOSE) {
                    throw new ChainSyntaxException("empty group", token.column());
                }
                Node body = sequence();
                if (peek().kind() != Kind.CLOSE) {
                    throw new ChainSyntaxException("unclosed '('", token.column());
                }
                next();
                depth--;
                if (peek().kind() == Kind.TEXT) {
                    throw new ChainSyntaxException("unexpected text after ')'", peek().column());
                }
                return new Node.Group(body);
            }
            case END -> throw lastOperator == null
                    ? new ChainSyntaxException("empty command", 1)
                    : new ChainSyntaxException("missing command after '" + lastOperator.text() + "'", token.column());
            default -> throw new ChainSyntaxException("unexpected '" + token.text() + "'", token.column());
        }
    }

    /** Trimmed, with one leading slash removed. */
    private static String clean(String text) {
        String stripped = text.strip();
        return stripped.startsWith("/") ? stripped.substring(1).strip() : stripped;
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token next() {
        return tokens.get(pos++);
    }
}
