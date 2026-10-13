package com.mmu.schedulingapp.restfulcontroller;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.*;
import java.util.Map;
@RestControllerAdvice(assignableTypes={WarehouseManagementController.class,WarehouseApiController.class})
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class WarehouseManagementErrors {
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Map<String,String>> status(ResponseStatusException failure) {
        return error(failure.getStatusCode(),failure.getReason()==null?"Warehouse request failed.":failure.getReason());
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,IllegalArgumentException.class,org.springframework.web.bind.MethodArgumentNotValidException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,String>> invalid(Exception failure) { return error(HttpStatus.BAD_REQUEST,"Invalid warehouse fields."); }
    @ExceptionHandler({DataIntegrityViolationException.class,ConcurrencyFailureException.class})
    public ResponseEntity<Map<String,String>> conflict(Exception failure) { return error(HttpStatus.CONFLICT,"Warehouse change conflicted with current data. Reload before retrying."); }
    @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,String>> unexpected(Exception failure) {
        if(failure instanceof org.springframework.web.ErrorResponse response && response.getStatusCode().is4xxClientError()) return error(response.getStatusCode(),"Invalid warehouse request.");
        org.slf4j.LoggerFactory.getLogger(WarehouseManagementErrors.class).warn("Warehouse request failed: {}",failure.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR,"Warehouse request could not be completed. Review current data before retrying.");
    }
    private ResponseEntity<Map<String,String>> error(HttpStatusCode status,String message) { return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("error",message)); }
}
