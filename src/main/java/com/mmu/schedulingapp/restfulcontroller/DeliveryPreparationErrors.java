package com.mmu.schedulingapp.restfulcontroller;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.Map;

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {DeliveryPreparationController.class, DeliveryApiController.class})
public class DeliveryPreparationErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(Map.of("error", failure.getReason() == null ? "Delivery request failed." : failure.getReason()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class,
            java.time.format.DateTimeParseException.class, MethodArgumentTypeMismatchException.class,
            MethodArgumentNotValidException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception failure) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("error", "Invalid delivery fields or filters."));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException failure) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                .body(Map.of("error", "Delivery could not be saved. Reload recipient and delivery details before retrying."));
    }
}
