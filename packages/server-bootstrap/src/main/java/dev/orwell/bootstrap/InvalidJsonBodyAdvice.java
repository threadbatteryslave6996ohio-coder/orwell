package dev.orwell.bootstrap;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Standard 400 contract for unreadable request bodies, in the shared
 * {@code {"success":false,"error":...}} envelope. Lets controllers use plain typed
 * {@code @RequestBody} parameters instead of hand-parsing raw bytes to control the error body.
 *
 * <p>The message distinguishes the three causes Spring folds into
 * {@link HttpMessageNotReadableException}: syntactically broken JSON, structurally wrong JSON
 * (valid JSON that doesn't bind to the target type), and a missing body. Cause detection is by
 * class name because Spring MVC may parse with Jackson 3 ({@code tools.jackson}) while app code
 * uses Jackson 2 ({@code com.fasterxml}) — an instanceof against either package would miss the other.
 */
@RestControllerAdvice
public class InvalidJsonBodyAdvice {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", errorMessage(exception)));
    }

    private static String errorMessage(HttpMessageNotReadableException exception) {
        Throwable cause = exception.getCause();
        if (cause == null) {
            // The missing/empty-required-body case is raised by Spring itself with no cause.
            return "missing request body";
        }
        String type = cause.getClass().getName();
        if (type.contains("JsonParse") || type.contains("StreamRead")) {
            // Jackson 2 JsonParseException / Jackson 3 StreamReadException: broken syntax.
            return "invalid json";
        }
        // Valid JSON that doesn't bind to the target type (MismatchedInputException etc.).
        return "invalid request body";
    }
}
