package net.slk.bashcraft.pipe;

import java.util.List;
import java.util.Optional;

/** A value extracted from a command's output, ready to feed into the next command. */
public sealed interface PipeValue {
    /** Text appended to the next command when it has no placeholders. */
    String asArgument();

    /** Value for {@code {name}} (one of x, y, z, value), if this kind of value has it. */
    Optional<String> placeholder(String name);

    /** Short description for error messages, e.g. "a number". */
    String describe();

    /** The items {@code grep} filters and {@code each} runs once per: a list's items, otherwise this one value. */
    default List<String> items() {
        return List.of(asArgument());
    }

    /** An item as a value: a number when it looks like one, else text. */
    static PipeValue of(String item) {
        return item.matches("-?\\d+(\\.\\d+)?") ? new Numeric(item) : new Text(item);
    }

    /** An ordered list of items (player names, tags, NBT list elements…); inserted as the items joined by spaces. */
    record Items(List<String> items) implements PipeValue {
        public Items {
            items = List.copyOf(items);
        }

        @Override
        public String asArgument() {
            return String.join(" ", items);
        }

        @Override
        public Optional<String> placeholder(String name) {
            return name.equals("value") ? Optional.of(asArgument()) : Optional.empty();
        }

        @Override
        public String describe() {
            return "a list";
        }

        @Override
        public List<String> items() {
            return items;
        }
    }

    record Coordinates(String x, String y, String z) implements PipeValue {
        @Override
        public String asArgument() {
            return x + " " + y + " " + z;
        }

        @Override
        public Optional<String> placeholder(String name) {
            return switch (name) {
                case "x" -> Optional.of(x);
                case "y" -> Optional.of(y);
                case "z" -> Optional.of(z);
                case "value" -> Optional.of(asArgument());
                default -> Optional.empty();
            };
        }

        @Override
        public String describe() {
            return "coordinates";
        }
    }

    record Numeric(String text) implements PipeValue {
        @Override
        public String asArgument() {
            return text;
        }

        @Override
        public Optional<String> placeholder(String name) {
            return name.equals("value") ? Optional.of(text) : Optional.empty();
        }

        @Override
        public String describe() {
            return "a number";
        }
    }

    record Text(String text) implements PipeValue {
        @Override
        public String asArgument() {
            return text;
        }

        @Override
        public Optional<String> placeholder(String name) {
            return name.equals("value") ? Optional.of(text) : Optional.empty();
        }

        @Override
        public String describe() {
            return "text";
        }
    }
}
