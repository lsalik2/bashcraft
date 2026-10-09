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
                args.add(arg instanceof Component c ? argument(c) : String.valueOf(arg));
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

    /**
     * An argument as pipes should see it: real names rather than decorated display text. Player display names (team
     * prefix + name + suffix) and team display names ({@code [red]}) carry the real name as their insertion.
     */
    private static Feedback argument(Component component) {
        Feedback feedback = toFeedback(component);
        String names = insertionText(component);
        return names.equals(feedback.text()) ? feedback : new Feedback(feedback.key(), feedback.args(), names);
    }

    /** The insertion where a component has one; recurses through list containers and square brackets. */
    private static String insertionText(Component component) {
        String insertion = component.getStyle().getInsertion();
        if (insertion != null) {
            return insertion;
        }
        if (component.getContents() instanceof TranslatableContents translatable
                && translatable.getKey().equals("chat.square_brackets")
                && translatable.getArgs().length == 1 && translatable.getArgs()[0] instanceof Component inner
                && inner.getStyle().getInsertion() != null) {
            return inner.getStyle().getInsertion();
        }
        if (component.getContents() == PlainTextContents.EMPTY && !component.getSiblings().isEmpty()) {
            StringBuilder out = new StringBuilder();
            for (Component sibling : component.getSiblings()) {
                out.append(insertionText(sibling));
            }
            return out.toString();
        }
        return component.getString();
    }
}
