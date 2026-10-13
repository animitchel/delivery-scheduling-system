package com.mmu.schedulingapp.restfulcontroller;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.*;
import java.util.Map;

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {RouteGenerationController.class, RouteApiController.class})
public class RouteGenerationErrors {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RouteGenerationErrors.class);
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException failure) {
        return error(failure.getStatusCode(), failure.getReason() == null ? "Route request failed." : failure.getReason());
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class,
            java.time.format.DateTimeParseException.class, MethodArgumentTypeMismatchException.class,
            MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception failure) {
        return error(HttpStatus.BAD_REQUEST, "Invalid route fields or filters.");
    }
    @ExceptionHandler({DataIntegrityViolationException.class, ConcurrencyFailureException.class})
    public ResponseEntity<Map<String, String>> conflict(Exception failure) {
        return error(HttpStatus.CONFLICT, "Route generation could not be committed. Reload routes and deliveries before requesting another plan.");
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> uncertain(Exception failure) {
        if (failure instanceof org.springframework.web.ErrorResponse framework && framework.getStatusCode().is4xxClientError())
            return error(framework.getStatusCode(), "Invalid route request.");
        log.error("Unexpected route request failure (type={}).", failure.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Route request outcome could not be confirmed. Review routes and deliveries before another generation request.");
    }
    private ResponseEntity<Map<String, String>> error(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error", message));
    }
}
