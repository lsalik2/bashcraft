package net.slk.bashcraft.mc;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.slk.bashcraft.pipe.Feedback;

import java.util.ArrayList;
import java.util.List;

/** Converts chat components into the Minecraft-free {@link Feedback} the pipe code reads. */
public final class ComponentFeedback {
    private ComponentFeedback() {}

    public static Feedback toFeedback(Component component) {
        String text = component.getString();
        if (component.getContents() instanceof TranslatableContents translatable) {
            List<Object> args = new ArrayList<>();
            for (Object arg : translatable.getArgs()) {
                args.add(arg instanceof Component c ? toFeedback(c) : String.valueOf(arg));
            }
            return new Feedback(translatable.getKey(), args, text);
        }
        // Component.empty().append(message) wraps the real message in a single sibling.
        if (component.getContents() == PlainTextContents.EMPTY && component.getSiblings().size() == 1) {
            Feedback inner = toFeedback(component.getSiblings().getFirst());
            return new Feedback(inner.key(), inner.args(), text);
        }
        return Feedback.plain(text);
    }
}
