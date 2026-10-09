package net.slk.bashcraft.exec;

import net.slk.bashcraft.chain.Quotes;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands {@code $} references in one command's text (spec C1 §3): {@code $NAME}, {@code ${NAME}}, scopes,
 * {@code $?}, {@code $(…)} and {@code \$}. Double-quoted text expands; single-quoted text doesn't.
 */
public final class Expander {
    public enum Kind { VARIABLE, STATUS, SUBSTITUTION, ESCAPED_DOLLAR, BAD }

    /** {@code [start, end)} of one reference; body is the qualified name, the inner chain, or an error message. */
    public record Reference(int start, int end, Kind kind, String body) {}

    /** Runs the chain inside {@code $( … )} and returns the value it produced. */
    @FunctionalInterface
    public interface Substituter {
        String run(String chain) throws ExpansionException;
    }

    private static final Pattern NAME = Pattern.compile("(?:(?:my|global)\\.)?[A-Za-z_][A-Za-z0-9_]*");

    private Expander() {}

    public static List<Reference> scan(String text) {
        List<Reference> references = new ArrayList<>();
        boolean inDouble = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                if (text.charAt(i + 1) == '$') {
                    references.add(new Reference(i, i + 2, Kind.ESCAPED_DOLLAR, "$"));
                }
                i += 2;
                continue;
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
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (Reference reference : scan(text)) {
            out.append(text, last, reference.start());
            out.append(switch (reference.kind()) {
                case ESCAPED_DOLLAR -> "$";
                case STATUS -> Integer.toString(lastStatus);
                case VARIABLE -> {
                    Variables.Ref ref = Variables.ref(reference.body(), Variables.Scope.ANY);
                    yield variables.get(ref.scope(), ref.name())
                            .orElseThrow(() -> new ExpansionException("$" + reference.body() + " is not set"));
                }
                case SUBSTITUTION -> substituter.run(reference.body());
                case BAD -> throw new ExpansionException(reference.body());
            });
            last = reference.end();
        }
        return out.append(text.substring(last)).toString();
    }

    /** Same-length text with every reference except {@code \$} replaced by zeros, for client-side parsing. */
    public static String mask(String text) {
        StringBuilder out = new StringBuilder(text);
        for (Reference reference : scan(text)) {
            if (reference.kind() != Kind.ESCAPED_DOLLAR) {
                for (int i = reference.start(); i < reference.end(); i++) {
                    out.setCharAt(i, '0');
                }
            }
        }
        return out.toString();
    }
}
