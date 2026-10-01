package net.slk.bashcraft.pipe;

import java.util.Optional;

/** A value extracted from a command's output, ready to feed into the next command. */
public sealed interface PipeValue {
    /** Text appended to the next command when it has no placeholders. */
    String asArgument();

    /** Value for {@code {name}} (one of x, y, z, value), if this kind of value has it. */
    Optional<String> placeholder(String name);

    /** Short description for error messages, e.g. "a number". */
    String describe();

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
