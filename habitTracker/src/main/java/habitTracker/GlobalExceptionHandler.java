package habitTracker;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import java.util.HashMap;
import java.util.Map;

/**
 * Turns input-validation failures into 400 responses with a per-field message map,
 * instead of leaking a 500 / stack trace. Applies to all @RestController endpoints.
 *
 * To add a new validated type: annotate its fields with jakarta.validation constraints
 * and add @Valid / @Validated to the controller parameter — no change needed here.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Thrown for @Valid / @Validated @RequestBody bodies that fail constraints.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleBodyValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fe.getField(), fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(errors);
    }

    // Thrown for @Validated method params / @RequestParam / @PathVariable constraint failures.
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleParamValidation(ConstraintViolationException ex) {
        Map<String, String> errors = new HashMap<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage());
        }
        return ResponseEntity.badRequest().body(errors);
    }

    // Services use IllegalArgumentException for bad client input (e.g. unknown id).
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
    }
}
