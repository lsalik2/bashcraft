package net.slk.bashcraft.test;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Looks inside the open chat screen's suggestion helper. Test-only. */
final class ChatProbe {
    static final int RED = TextColor.fromLegacyFormat(ChatFormatting.RED).getValue();

    private ChatProbe() {}

    static void open(Minecraft client, String text) {
        client.gui.setScreen(new ChatScreen(text, false));
    }

    static CommandSuggestions suggestions(Minecraft client) {
        return (CommandSuggestions) read(ChatScreen.class, client.gui.screen(), "commandSuggestions");
    }

    static String value(Minecraft client) {
        return ((EditBox) read(ChatScreen.class, client.gui.screen(), "input")).getValue();
    }

    /** Every colour used to draw the whole input line. */
    static Set<Integer> colors(Minecraft client) {
        try {
            Method format = CommandSuggestions.class.getDeclaredMethod("formatChat", String.class, int.class);
            format.setAccessible(true);
            FormattedCharSequence text = (FormattedCharSequence) format.invoke(suggestions(client), value(client), 0);
            Set<Integer> colors = new HashSet<>();
            if (text != null) {
                text.accept((index, style, codePoint) -> {
                    if (style.getColor() != null) {
                        colors.add(style.getColor().getValue());
                    }
                    return true;
                });
            }
            return colors;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The current completion list, empty while it is still pending. */
    @SuppressWarnings("unchecked")
    static List<String> completions(Minecraft client) {
        CompletableFuture<Suggestions> pending = (CompletableFuture<Suggestions>) read(CommandSuggestions.class, suggestions(client), "pendingSuggestions");
        if (pending == null || !pending.isDone() || pending.isCompletedExceptionally()) {
            return List.of();
        }
        return pending.join().getList().stream().map(Suggestion::getText).toList();
    }

    /** The usage/error lines above the input box, as plain text. */
    @SuppressWarnings("unchecked")
    static List<String> usage(Minecraft client) {
        List<FormattedCharSequence> lines = (List<FormattedCharSequence>) read(CommandSuggestions.class, suggestions(client), "commandUsage");
        return lines.stream().map(line -> {
            StringBuilder out = new StringBuilder();
            line.accept((index, style, codePoint) -> {
                out.appendCodePoint(codePoint);
                return true;
            });
            return out.toString();
        }).toList();
    }

    /** Whether the helper treats the line as containing a chat message (vanilla blocks those for chat-restricted accounts). */
    static boolean isMessage(Minecraft client) {
        return (Boolean) read(CommandSuggestions.class, suggestions(client), "currentParseIsMessage");
    }

    static void moveCursor(Minecraft client, int position) {
        ((EditBox) read(ChatScreen.class, client.gui.screen(), "input")).setCursorPosition(position);
        suggestions(client).updateCommandInfo();
    }

    private static Object read(Class<?> owner, Object target, String field) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
