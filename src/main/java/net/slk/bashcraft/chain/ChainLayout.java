package net.slk.bashcraft.chain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Where each token of a chain sits in an input line, for highlighting and completion (spec B §3). Unfinished input
 * still yields the spans before the first error, plus that error.
 */
public record ChainLayout(List<Span> spans, Optional<ChainSyntaxException> error) {
    public enum Kind { COMMAND, OPERATOR, GROUP_OPEN, GROUP_CLOSE }

    /** {@code [start, end)} in the line given to {@link #of}. Command spans exclude the leading slash and whitespace. */
    public record Span(Kind kind, int start, int end) {}

    /** The part of the line to parse for the cursor: {@code [start, end)}. Empty when the cursor isn't in a command. */
    public record Window(int start, int end) {}

    public ChainLayout {
        spans = List.copyOf(spans);
    }

    /** Layout of {@code line.substring(from)} in {@code mode}; spans index into {@code line}. */
    public static ChainLayout of(String line, int from, NodeParser.Mode mode) {
        String text = line.substring(from);
        Tokenizer.Result result = Tokenizer.tokenizeLenient(text, mode);
        List<Span> spans = new ArrayList<>();
        for (Tokenizer.Token token : result.tokens()) {
            Kind kind = switch (token.kind()) {
                case TEXT -> Kind.COMMAND;
                case OPEN -> Kind.GROUP_OPEN;
                case CLOSE -> Kind.GROUP_CLOSE;
                case AND, OR, PIPE, SEQ, BACKGROUND -> Kind.OPERATOR;
                case END -> null;
            };
            if (kind == null) {
                continue;
            }
            int start = token.start();
            int end = token.end();
            if (kind == Kind.COMMAND && text.charAt(start) == '/') {
                start++;
                while (start < end && Character.isWhitespace(text.charAt(start))) {
                    start++;
                }
            }
            if (start < end) {
                spans.add(new Span(kind, from + start, from + end));
            }
        }
        ChainSyntaxException error = result.error();
        if (error == null) {
            try {
                NodeParser.parse(text, mode, Integer.MAX_VALUE);
            } catch (ChainSyntaxException e) {
                error = e;
            }
        }
        return new ChainLayout(spans, Optional.ofNullable(error));
    }

    /**
     * The chain in an input-box line ({@code /…} from chat, or a command block's text, where the slash is optional),
     * or empty when it isn't one. {@code sh …} lines always count; other lines only in transparent mode and only when
     * they contain an operator or group (spec B §4).
     */
    public static Optional<ChainLayout> forInput(String value, boolean transparentMode) {
        int start = value.startsWith("/") ? 1 : 0;
        if (value.startsWith("sh ", start)) {
            return Optional.of(of(value, start + 3, NodeParser.Mode.SH));
        }
        if (!transparentMode) {
            return Optional.empty();
        }
        ChainLayout layout = of(value, start, NodeParser.Mode.TRANSPARENT);
        return layout.hasChainSyntax() ? Optional.of(layout) : Optional.empty();
    }

    public boolean hasChainSyntax() {
        return spans.stream().anyMatch(span -> span.kind() != Kind.COMMAND);
    }

    /**
     * Which part of the line to parse for {@code cursor}. In a command or its trailing whitespace (up to the next
     * token): from the command's start to the later of its end and the cursor, since vanilla completes at the cursor.
     * Elsewhere (on an operator, before a command): the empty window at the cursor, i.e. a new command.
     */
    public Window window(int cursor) {
        for (int i = 0; i < spans.size(); i++) {
            Span span = spans.get(i);
            if (span.kind() != Kind.COMMAND) {
                continue;
            }
            int regionEnd = i + 1 < spans.size() ? spans.get(i + 1).start() : Integer.MAX_VALUE;
            if (cursor >= span.start() && cursor <= regionEnd) {
                return new Window(span.start(), Math.max(span.end(), cursor));
            }
        }
        return new Window(cursor, cursor);
    }

    /**
     * Whether a command may start at {@code cursor} (so suggesting command names makes sense): not right after a
     * {@code )}, where only an operator can follow, and not inside an operator.
     */
    public boolean commandCanStartAt(int cursor) {
        Window window = window(cursor);
        if (window.start() != window.end()) {
            return true;
        }
        Span before = null;
        for (Span span : spans) {
            if (span.start() < cursor) {
                before = span;
            }
        }
        if (before == null) {
            return true;
        }
        return switch (before.kind()) {
            case GROUP_CLOSE -> false;
            case OPERATOR -> cursor >= before.end();
            default -> true;
        };
    }
}
