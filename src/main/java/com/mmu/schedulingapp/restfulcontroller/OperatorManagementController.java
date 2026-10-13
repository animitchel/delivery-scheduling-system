package com.mmu.schedulingapp.restfulcontroller;
import com.mmu.schedulingapp.dto.operator.*;
import com.mmu.schedulingapp.service.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
@RestController
@RequestMapping("/api/admin/operators")
public class OperatorManagementController {
    private final OperatorService operators;
    public OperatorManagementController(OperatorService operators) { this.operators=operators; }
    @GetMapping public ResponseEntity<OperatorDirectory> directory(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String search) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(operators.directory(page,size,search));
    }
    @GetMapping("/{id}") public ResponseEntity<OperatorItem> operator(@PathVariable int id) {
        return operators.snapshot(id).map(state -> response(state,HttpStatus.OK)).orElseGet(() -> ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build());
    }
    @PostMapping public ResponseEntity<OperatorItem> create(@RequestBody OperatorWriteRequest request) {
        var state=operators.create(request);
        return ResponseEntity.created(URI.create("/api/admin/operators/"+state.id())).cacheControl(CacheControl.noStore()).eTag(state.etag()).body(state.response());
    }
    @PutMapping("/{id}") public ResponseEntity<OperatorItem> replace(@PathVariable int id,@RequestBody OperatorWriteRequest request,@RequestHeader(name="If-Match",required=false) String etag) {
        return operators.replace(id,request,etag).map(state -> response(state,HttpStatus.OK)).orElseGet(() -> ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build());
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable int id,@RequestHeader(name="If-Match",required=false) String etag) {
        return ResponseEntity.status(operators.delete(id,etag)?HttpStatus.NO_CONTENT:HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).build();
    }
    private ResponseEntity<OperatorItem> response(OperatorState state,HttpStatus status) { return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).eTag(state.etag()).body(state.response()); }
}
