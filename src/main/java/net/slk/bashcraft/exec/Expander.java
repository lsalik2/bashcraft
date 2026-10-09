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
    public enum Kind { VARIABLE, STATUS, SUBSTITUTION, ESCAPED_DOLLAR, PLACEHOLDER, ESCAPED_PLACEHOLDER, BAD }

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
    private static final Pattern NAME = Pattern.compile("(?:(?:my|global)\\.)?[A-Za-z_][A-Za-z0-9_]*");

    private Expander() {}

    public static List<Reference> scan(String text) {
        return scan(text, false);
    }

    /** With {@code placeholders}, pipe placeholders (and {@code \\{x}} escapes) are references too. */
    public static List<Reference> scan(String text, boolean placeholders) {
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
                Reference reference = reference(text, i);
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

    private static Reference reference(String text, int i) {
        if (i + 1 >= text.length()) {
            return null;
        }
        char next = text.charAt(i + 1);
        if (next == '?') {
            return new Reference(i, i + 2, Kind.STATUS, "?");
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
            return new Reference(i, close < 0 ? text.length() : close + 1, Kind.BAD, "bad ${…}");
        }
        Matcher m = NAME.matcher(text).region(i + 1, text.length());
        return m.lookingAt() ? new Reference(i, m.end(), Kind.VARIABLE, m.group()) : null;
    }

    public static String expand(String text, Variables variables, int lastStatus, Substituter substituter) throws ExpansionException {
        return expand(text, variables, lastStatus, substituter, null, false);
    }

    /**
     * One pass over the command's own text, so nothing inserted is ever read again. With {@code input}, pipe
     * placeholders are filled in, or the value is appended when there are none. With {@code escapeValues} (the command
     * runs a nested {@code sh}), inserted values get {@code \\ & | ; ( ) $} backslash-escaped, so the inner chain reads
     * them as text instead of chain syntax.
     */
    public static String expand(String text, Variables variables, int lastStatus, Substituter substituter, PipeInput input,
                                boolean escapeValues) throws ExpansionException {
        List<Reference> references = scan(text, input != null);
        if (references.size() > MAX_REFERENCES) {
            throw new ExpansionException("too many $ references (max " + MAX_REFERENCES + ")");
        }
        StringBuilder out = new StringBuilder();
        boolean placed = false;
        int last = 0;
        for (Reference reference : references) {
            out.append(text, last, reference.start());
            boolean inserted = true;
            String value;
            switch (reference.kind()) {
                case ESCAPED_DOLLAR -> {
                    value = "$";
                    inserted = false;
                }
                case ESCAPED_PLACEHOLDER -> {
                    value = reference.body();
                    inserted = false;
                }
                case STATUS -> value = Integer.toString(lastStatus);
                case VARIABLE -> {
                    Variables.Ref ref = Variables.ref(reference.body(), Variables.Scope.ANY);
                    value = variables.get(ref.scope(), ref.name())
                            .orElseThrow(() -> new ExpansionException("$" + reference.body() + " is not set"));
                }
                case SUBSTITUTION -> value = substituter.run(reference.body());
                case PLACEHOLDER -> {
                    placed = true;
                    value = placeholder(reference.body(), input);
                }
                default -> throw new ExpansionException(reference.body());
            }
            out.append(escapeValues && inserted ? escape(value) : value);
            checkLength(out);
            last = reference.end();
        }
        out.append(text, last, text.length());
        if (input != null && !placed) {
            String argument = input.value().asArgument();
            String appended = out.toString().stripTrailing() + " " + (escapeValues ? escape(argument) : argument);
            out.setLength(0);
            out.append(appended);
        }
        checkLength(out);
        return out.toString();
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
