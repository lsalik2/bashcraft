package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.Quotes;
import net.slk.bashcraft.pipe.PipeValue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands {@code $} references in one command's text (spec C1 §3): {@code $NAME}, {@code ${NAME}}, scopes,
 * {@code $?}, {@code $(…)} and {@code \$}. Double-quoted text expands; single-quoted text doesn't.
 */
public final class Expander {
    public enum Kind { VARIABLE, STATUS, SUBSTITUTION, ESCAPED_DOLLAR, PLACEHOLDER, ESCAPED_PLACEHOLDER, POSITIONAL, BAD }

    /** {@code [start, end)} of one reference; body is the qualified name, the inner chain, or an error message. */
    public record Reference(int start, int end, Kind kind, String body) {}

    /** A value piped into the command, for {@code {x} {y} {z} {value} {result}}. */
    public record PipeInput(PipeValue value, int result) {}

    /** Runs the chain inside {@code $( … )} and returns the value it produced. */
    @FunctionalInterface
    public interface Substituter {
        String run(String chain) throws ExpansionException;
    }

    public static final int MAX_REFERENCES = 256;
    public static final int MAX_LENGTH = 32767;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(x|y|z|value|result)}");
    private static final String ESCAPED_IN_NESTED = "\\&|;()$";
    /**
     * What player-controlled text may look like in an elevated chain: one word with no quotes, brackets, braces,
     * operators, @ or \\ (so it fills exactly one argument and can't add keywords like store/if/as), or only numbers and
     * relative coordinates separated by single spaces ({@code 312 ~ -1040}, {@code ^ ^1.5 ^}).
     */
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_.:~^+\\-]*|(?:[~^]?[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)?)(?: (?:[~^]?[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)?))+");
    static final String NOT_PLAIN = "a value from a player can only be one plain word or a list of numbers here";

    private static final Pattern NAME = Pattern.compile("(?:(?:my|global)\\.)?[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern POSITIONAL_BRACE = Pattern.compile("([1-9])(?::-(.*))?", Pattern.DOTALL);

    private Expander() {}

    public static List<Reference> scan(String text) {
        return scan(text, false);
    }

    /** With {@code placeholders}, pipe placeholders (and {@code \\{x}} escapes) are references too. */
    public static List<Reference> scan(String text, boolean placeholders) {
        return scan(text, placeholders, false);
    }

    /** With {@code positionals} (inside an alias body), {@code $1}…{@code $9}, {@code $@}, {@code $#}, {@code ${N:-d}} are references. */
    public static List<Reference> scan(String text, boolean placeholders, boolean positionals) {
        List<Reference> references = new ArrayList<>();
        boolean inDouble = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                if (text.charAt(i + 1) == '$') {
                    references.add(new Reference(i, i + 2, Kind.ESCAPED_DOLLAR, "$"));
                    i += 2;
                    continue;
                }
                Matcher escaped = PLACEHOLDER.matcher(text).region(i + 1, text.length());
                if (placeholders && escaped.lookingAt()) {
                    references.add(new Reference(i, escaped.end(), Kind.ESCAPED_PLACEHOLDER, escaped.group()));
                    i = escaped.end();
                    continue;
                }
                // Inside double quotes a backslash escapes the next character (as Quotes.closingQuote reads it); outside,
                // the tokenizer already resolved its escapes, and a quote after a backslash opens like any other.
                i += inDouble ? 2 : 1;
                continue;
            }
            if (placeholders && c == '{') {
                Matcher placeholder = PLACEHOLDER.matcher(text).region(i, text.length());
                if (placeholder.lookingAt()) {
                    references.add(new Reference(i, placeholder.end(), Kind.PLACEHOLDER, placeholder.group(1)));
                    i = placeholder.end();
                    continue;
                }
            }
            if (c == '"') {
                if (inDouble) {
                    inDouble = false;
                } else if (Quotes.opensQuote(text, i)) {
                    inDouble = true;
                }
                i++;
                continue;
            }
            if (c == '\'' && !inDouble && Quotes.opensQuote(text, i)) {
                int close = Quotes.closingQuote(text, i);
                i = close < 0 ? text.length() : close + 1;
                continue;
            }
            if (c == '$') {
                Reference reference = reference(text, i, positionals);
                if (reference != null) {
                    references.add(reference);
                    i = reference.end();
                    continue;
                }
            }
            i++;
        }
        return references;
    }

    /** Whether an alias body uses positional references (if not, call arguments are appended to it). */
    public static boolean hasPositionals(String text) {
        return scan(text, false, true).stream().anyMatch(reference -> reference.kind() == Kind.POSITIONAL);
    }

    private static Reference reference(String text, int i, boolean positionals) {
        if (i + 1 >= text.length()) {
            return null;
        }
        char next = text.charAt(i + 1);
        if (next == '?') {
            return new Reference(i, i + 2, Kind.STATUS, "?");
        }
        if (positionals && (next == '@' || next == '#' || (next >= '1' && next <= '9'))) {
            return new Reference(i, i + 2, Kind.POSITIONAL, String.valueOf(next));
        }
        if (next == '(') {
            int close = Quotes.substitutionEnd(text, i);
            return close < 0
                    ? new Reference(i, text.length(), Kind.BAD, "unclosed $(")
                    : new Reference(i, close + 1, Kind.SUBSTITUTION, text.substring(i + 2, close));
        }
        if (next == '{') {
            int close = text.indexOf('}', i + 2);
            if (close >= 0 && NAME.matcher(text.substring(i + 2, close)).matches()) {
                return new Reference(i, close + 1, Kind.VARIABLE, text.substring(i + 2, close));
            }
            if (positionals && close >= 0 && POSITIONAL_BRACE.matcher(text.substring(i + 2, close)).matches()) {
                return new Reference(i, close + 1, Kind.POSITIONAL, text.substring(i + 2, close));
            }
            return new Reference(i, close < 0 ? text.length() : close + 1, Kind.BAD, "bad ${…}");
        }
        Matcher m = NAME.matcher(text).region(i + 1, text.length());
        return m.lookingAt() ? new Reference(i, m.end(), Kind.VARIABLE, m.group()) : null;
    }

    private static String positional(String body, List<String> words) throws ExpansionException {
        if (body.equals("@")) {
            return String.join(" ", words);
        }
        if (body.equals("#")) {
            return Integer.toString(words.size());
        }
        Matcher m = POSITIONAL_BRACE.matcher(body);
        m.matches();
        int index = m.group(1).charAt(0) - '1';
        if (index < words.size()) {
            return words.get(index);
        }
        if (m.group(2) != null) {
            return m.group(2);
        }
        throw new ExpansionException("$" + m.group(1) + " is not set");
    }

    public static String expand(String text, Variables variables, int lastStatus, Substituter substituter) throws ExpansionException {
        return expand(text, variables, lastStatus, substituter, null, false);
    }

    /**
     * One pass over the command's own text, so nothing inserted is ever read again. With {@code input}, pipe
     * placeholders are filled in, or the value is appended when there are none. With {@code escapeValues}, inserted
     * values are escaped for a nested chain (see {@link Expansion#join}).
     */
    public static String expand(String text, Variables variables, int lastStatus, Substituter substituter, PipeInput input,
                                boolean escapeValues) throws ExpansionException {
        return expandParts(text, variables, lastStatus, substituter, input).join(escapeValues);
    }

    /** One piece of an expanded command: the author's own text, or a value inserted by expansion or a pipe. */
    public record Part(String text, boolean inserted) {}

    /** An expanded command that remembers which parts were inserted, so callers can decide how to treat them. */
    public static final class Expansion {
        private final List<Part> parts;

        private Expansion(List<Part> parts) {
            this.parts = List.copyOf(parts);
        }

        /**
         * The command text. With {@code escapeValues} (the command can run other commands), inserted values get
         * {@code \\ & | ; ( ) $} backslash-escaped so an inner chain reads them as text, and a value with a quote is
         * refused, since a quote can't be escaped for the inner parser.
         */
        public String join(boolean escapeValues) throws ExpansionException {
            StringBuilder out = new StringBuilder();
            for (Part part : parts) {
                out.append(escapeValues && part.inserted() ? escape(part.text()) : part.text());
                checkLength(out);
            }
            return out.toString();
        }

        /** Whether {@code [start, end)} of the unescaped text overlaps an inserted value. */
        public boolean insertedOverlaps(int start, int end) {
            int position = 0;
            for (Part part : parts) {
                int partEnd = position + part.text().length();
                if (part.inserted() && position < end && start < partEnd) {
                    return true;
                }
                position = partEnd;
            }
            return false;
        }
    }

    public static Expansion expandParts(String text, Variables variables, int lastStatus, Substituter substituter,
                                        PipeInput input) throws ExpansionException {
        return expandParts(text, variables, lastStatus, substituter, input, null);
    }

    /** @param positionals the alias call's words while an alias body runs, else null */
    public static Expansion expandParts(String text, Variables variables, int lastStatus, Substituter substituter,
                                        PipeInput input, List<String> positionals) throws ExpansionException {
        List<Reference> references = scan(text, input != null, positionals != null);
        if (references.size() > MAX_REFERENCES) {
            throw new ExpansionException("too many $ references (max " + MAX_REFERENCES + ")");
        }
        List<Part> parts = new ArrayList<>();
        int length = 0;
        boolean placed = false;
        int last = 0;
        for (Reference reference : references) {
            parts.add(new Part(text.substring(last, reference.start()), false));
            Part part = switch (reference.kind()) {
                case ESCAPED_DOLLAR -> new Part("$", false);
                case ESCAPED_PLACEHOLDER -> new Part(reference.body(), false);
                case STATUS -> new Part(Integer.toString(lastStatus), true);
                case VARIABLE -> {
                    Variables.Ref ref = Variables.ref(reference.body(), Variables.Scope.ANY);
                    String value = variables.get(ref.scope(), ref.name())
                            .orElseThrow(() -> new ExpansionException("$" + reference.body() + " is not set"));
                    yield new Part(checked(value, !variables.trusted(ref.scope(), ref.name())), true);
                }
                case SUBSTITUTION -> new Part(checked(substituter.run(reference.body()), variables.elevated()), true);
                case PLACEHOLDER -> {
                    placed = true;
                    yield new Part(checked(placeholder(reference.body(), input), variables.elevated()), true);
                }
                case POSITIONAL -> new Part(checked(positional(reference.body(), positionals), variables.elevated()), true);
                default -> throw new ExpansionException(reference.body());
            };
            parts.add(part);
            length += reference.start() - last + part.text().length();
            if (length > MAX_LENGTH) {
                throw new ExpansionException("expansion longer than " + MAX_LENGTH + " characters");
            }
            last = reference.end();
        }
        parts.add(new Part(text.substring(last), false));
        if (input != null && !placed) {
            stripTrailing(parts);
            parts.add(new Part(" ", false));
            parts.add(new Part(checked(input.value().asArgument(), variables.elevated()), true));
        }
        Expansion expansion = new Expansion(parts);
        expansion.join(false);
        return expansion;
    }

    /** Removes trailing whitespace across the last parts (the appended pipe value follows a single space). */
    private static void stripTrailing(List<Part> parts) {
        while (!parts.isEmpty()) {
            Part last = parts.removeLast();
            String stripped = last.text().stripTrailing();
            if (!stripped.isEmpty()) {
                parts.add(new Part(stripped, last.inserted()));
                return;
            }
        }
    }

    /** Player-controlled text in an elevated chain must be plain, so no parser after us can read it as syntax. */
    private static String checked(String value, boolean untrusted) throws ExpansionException {
        if (untrusted && !PLAIN.matcher(value).matches()) {
            throw new ExpansionException(NOT_PLAIN);
        }
        return value;
    }

    private static String placeholder(String name, PipeInput input) throws ExpansionException {
        if (name.equals("result")) {
            return Integer.toString(input.result());
        }
        return input.value().placeholder(name)
                .orElseThrow(() -> new ExpansionException("{" + name + "} is not available from " + input.value().describe()));
    }

    private static String escape(String value) throws ExpansionException {
        if (value.indexOf('"') >= 0 || value.indexOf('\'') >= 0) {
            // A quote can't be escaped for the inner parser: it would decide what the inner chain splits on.
            throw new ExpansionException("a value with quotes can't go into a command that runs other commands");
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (ESCAPED_IN_NESTED.indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private static void checkLength(StringBuilder out) throws ExpansionException {
        if (out.length() > MAX_LENGTH) {
            throw new ExpansionException("expansion longer than " + MAX_LENGTH + " characters");
        }
    }

    /** Same-length text with every reference except {@code \$} replaced by zeros, for client-side parsing. */
    public static String mask(String text) {
        StringBuilder out = new StringBuilder(text);
        for (Reference reference : scan(text)) {
            if (reference.kind() != Kind.ESCAPED_DOLLAR && reference.kind() != Kind.BAD) {
                for (int i = reference.start(); i < reference.end(); i++) {
                    out.setCharAt(i, '0');
                }
            }
        }
        return out.toString();
    }
}
