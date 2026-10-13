package com.mmu.schedulingapp.restfulcontroller;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;

@RestControllerAdvice(assignableTypes={RecipientManagementController.class,RecipientApiController.class})
public class RecipientManagementErrors {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> status(ResponseStatusException failure) {
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore()).body(Map.of("error",failure.getReason()==null?"Recipient request failed.":failure.getReason()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,IllegalArgumentException.class})
    public ResponseEntity<Map<String,String>> invalid(Exception failure) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("error","Invalid recipient fields."));
    }
}
