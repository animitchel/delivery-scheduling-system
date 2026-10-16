package com.mmu.schedulingapp.restfulcontroller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=RouteWindowAssessmentController.class)
public class RouteWindowAssessmentErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> status(ResponseStatusException failure) {
        return error(failure.getStatusCode(),failure.getReason()==null ? "Route assessment failed." : failure.getReason());
    }
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,String>> invalid(Exception failure) { return error(HttpStatus.BAD_REQUEST,"Invalid route assessment fields."); }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,String>> failure(Exception failure) {
        if(failure instanceof org.springframework.web.ErrorResponse framework && framework.getStatusCode().is4xxClientError())
            return error(framework.getStatusCode(),"Invalid route assessment request.");
        return error(HttpStatus.INTERNAL_SERVER_ERROR,"Route assessment could not be completed.");
    }
    private ResponseEntity<Map<String,String>> error(HttpStatusCode status,String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error",message));
    }
}
