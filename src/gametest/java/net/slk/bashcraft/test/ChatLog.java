package net.slk.bashcraft.test;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** The game messages the test client receives (command feedback and errors), to spot red ones. */
final class ChatLog {
    private static final int RED = TextColor.fromLegacyFormat(ChatFormatting.RED).getValue();
    private static final List<Component> RECEIVED = new CopyOnWriteArrayList<>();
    private static boolean started;

    private ChatLog() {}

    static synchronized void start() {
        if (!started) {
            started = true;
            ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
                if (!overlay) {
                    RECEIVED.add(message);
                }
            });
        }
    }

    static void clear() {
        RECEIVED.clear();
    }

    /** The text of every message received since the last clear that has red in it (how errors are shown). */
    static List<String> red() {
        return RECEIVED.stream().filter(ChatLog::isRed).map(Component::getString).toList();
    }

    static boolean isRed(Component message) {
        boolean[] red = {false};
        message.visit((style, text) -> {
            if (!text.isBlank() && style.getColor() != null && style.getColor().getValue() == RED) {
                red[0] = true;
            }
            return Optional.empty();
        }, Style.EMPTY);
        return red[0];
    }
}
