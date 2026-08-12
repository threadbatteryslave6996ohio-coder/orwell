package dev.orwell.reverseproxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Refuses requests whose target matches one of the configured patterns.
 *
 * <p>A pattern is a regular expression matched <em>in full</em> against the request target
 * ({@code /path} or {@code /path?query}), optionally prefixed with {@code METHOD:} to scope it to
 * one HTTP method:
 *
 * <pre>
 *   /admin.*             every method, every path starting with /admin
 *   POST:/orders         POST /orders only
 *   .*[?&amp;]debug=true     anything carrying ?debug=true
 * </pre>
 *
 * <p>Full match, not substring: {@code /admin} blocks {@code /admin} alone, not
 * {@code /admin/users}. Write {@code /admin.*} for the subtree.
 */
public final class PatternPolicy implements Policy {
    private static final String ANY_METHOD = "*";

    private final List<Rule> blocked;

    public PatternPolicy(List<String> patterns) {
        List<Rule> parsed = new ArrayList<>();
        for (String pattern : Objects.requireNonNull(patterns, "patterns")) {
            if (pattern != null && !pattern.isBlank()) {
                parsed.add(Rule.parse(pattern.trim()));
            }
        }
        this.blocked = List.copyOf(parsed);
    }

    /**
     * Parses the comma-separated {@code PROXY_BLOCKED_PATTERNS} form. A comma always separates two
     * patterns and is never part of one, so a regex that needs a literal comma — a {@code {2,4}}
     * quantifier — has to be written without it ({@code \d\d\d?\d?}).
     */
    public static PatternPolicy fromConfiguration(String configured) {
        if (configured == null || configured.isBlank()) {
            return new PatternPolicy(List.of());
        }
        return new PatternPolicy(List.of(configured.split(",")));
    }

    @Override
    public boolean pass(ProxyRequest request) {
        return blocked.stream().noneMatch(rule -> rule.matches(request));
    }

    @Override
    public String name() {
        return "pattern-policy";
    }

    /** The configured patterns, normalized — reported in {@code /health}. */
    public List<String> patterns() {
        return blocked.stream().map(Rule::describe).toList();
    }

    private record Rule(String method, Pattern target) {
        static Rule parse(String raw) {
            String method = ANY_METHOD;
            String regex = raw;
            int separator = raw.indexOf(':');
            if (separator > 0) {
                String prefix = raw.substring(0, separator);
                // Only a bare word before the colon is a method scope; anything else is part of
                // the regex, so a pattern like /a:b keeps working.
                if (prefix.matches("[A-Za-z]+|\\*")) {
                    method = prefix.toUpperCase(Locale.ROOT);
                    regex = raw.substring(separator + 1);
                }
            }
            try {
                return new Rule(method, Pattern.compile(regex));
            } catch (PatternSyntaxException exception) {
                // A pattern nobody can compile must not degrade into "this rule blocks nothing" —
                // that fails open silently. Refuse to start instead.
                throw new IllegalArgumentException("Invalid blocked pattern: " + raw, exception);
            }
        }

        boolean matches(ProxyRequest request) {
            return (ANY_METHOD.equals(method) || method.equals(request.method()))
                    && target.matcher(request.target()).matches();
        }

        String describe() {
            return ANY_METHOD.equals(method) ? target.pattern() : method + ":" + target.pattern();
        }
    }
}
