package dev.orwell.loganalyzer;

import java.util.Map;

record AiDecision(
        boolean important,
        String severity,
        String title,
        String message,
        String reason,
        String recommendedAction,
        double confidence
) {
    static AiDecision from(Map<String, Object> values) {
        boolean important = Boolean.TRUE.equals(values.get("important"));
        String severity = stringValue(values.get("severity"), important ? "high" : "low");
        String title = stringValue(values.get("title"), important ? "Important log cluster" : "Log noise");
        String message = stringValue(values.get("message"), important ? "AI flagged an important log cluster." : "AI did not flag this cluster.");
        String reason = stringValue(values.get("reason"), "");
        String recommendedAction = stringValue(values.get("recommended_action"), "");
        double confidence = numberValue(values.get("confidence"), important ? 1.0 : 0.0);
        return new AiDecision(important, severity, title, message, reason, recommendedAction, confidence);
    }

    String importanceLabel() {
        return important ? "important" : "not_important";
    }

    private static String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static double numberValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Double.parseDouble(string);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
