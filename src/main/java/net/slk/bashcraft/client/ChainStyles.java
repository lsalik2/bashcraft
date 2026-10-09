package net.slk.bashcraft.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.context.ParsedArgument;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.slk.bashcraft.chain.ChainLayout;
import net.slk.bashcraft.pipe.Placeholders;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Per-character styles for a chain in an input box (spec B §5): each command coloured as vanilla colours it, red only
 * where that command fails to parse; operators and group parentheses dark aqua; placeholders light purple.
 * Computed once per change of the text; {@link #format} runs every frame.
 */
public final class ChainStyles {
    private static final Style LITERAL = Style.EMPTY.withColor(ChatFormatting.GRAY);
    private static final Style UNPARSED = Style.EMPTY.withColor(ChatFormatting.RED);
    private static final Style SYNTAX = Style.EMPTY.withColor(ChatFormatting.DARK_AQUA);
    private static final Style PLACEHOLDER = Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE);
    private static final List<Style> ARGUMENTS = Stream.of(
            ChatFormatting.AQUA, ChatFormatting.YELLOW, ChatFormatting.GREEN, ChatFormatting.LIGHT_PURPLE, ChatFormatting.GOLD)
            .map(Style.EMPTY::withColor).toList();

    private final String value;
    private final Style[] styles;
    private final boolean hasMessages;

    private ChainStyles(String value, Style[] styles, boolean hasMessages) {
        this.value = value;
        this.styles = styles;
        this.hasMessages = hasMessages;
    }

    /** @param isMessage vanilla's test for chat-message arguments, applied to every command in the chain */
    public static <S> ChainStyles compute(String value, ChainLayout layout, CommandDispatcher<S> dispatcher, S source,
                                          Predicate<ParseResults<S>> isMessage) {
        boolean hasMessages = false;
        Style[] styles = new Style[value.length()];
        Arrays.fill(styles, LITERAL);
        String masked = Placeholders.mask(value);
        for (ChainLayout.Span span : layout.spans()) {
            if (span.kind() != ChainLayout.Kind.COMMAND) {
                fill(styles, span.start(), span.end(), SYNTAX);
                continue;
            }
            StringReader reader = new StringReader(masked.substring(0, span.end()));
            reader.setCursor(span.start());
            ParseResults<S> parse = dispatcher.parse(reader, source);
            hasMessages |= isMessage.test(parse);
            int color = -1;
            for (ParsedArgument<S, ?> argument : parse.getContext().getLastChild().getArguments().values()) {
                if (++color >= ARGUMENTS.size()) {
                    color = 0;
                }
                fill(styles, argument.getRange().getStart(), argument.getRange().getEnd(), ARGUMENTS.get(color));
            }
            if (parse.getReader().canRead()) {
                fill(styles, parse.getReader().getCursor(), span.end(), UNPARSED);
            }
        }
        for (int[] range : Placeholders.ranges(value)) {
            fill(styles, range[0], range[1], PLACEHOLDER);
        }
        return new ChainStyles(value, styles, hasMessages);
    }

    /** Whether any command in the chain takes a chat message (vanilla blocks those for chat-restricted accounts). */
    public boolean hasMessages() {
        return hasMessages;
    }

    public boolean matches(String text) {
        return value.equals(text);
    }

    /** The visible part {@code text} of the line, which starts at {@code offset} in the whole line. */
    public FormattedCharSequence format(String text, int offset) {
        List<FormattedCharSequence> parts = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            Style style = styleAt(offset + i);
            int j = i + 1;
            while (j < text.length() && styleAt(offset + j) == style) {
                j++;
            }
            parts.add(FormattedCharSequence.forward(text.substring(i, j), style));
            i = j;
        }
        return FormattedCharSequence.composite(parts);
    }

    private Style styleAt(int index) {
        return index >= 0 && index < styles.length ? styles[index] : LITERAL;
    }

    private static void fill(Style[] styles, int start, int end, Style style) {
        Arrays.fill(styles, Math.max(0, start), Math.min(styles.length, Math.max(start, end)), style);
    }
}
