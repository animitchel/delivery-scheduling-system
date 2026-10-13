package com.mmu.schedulingapp.restfulcontroller;
import com.mmu.schedulingapp.dto.warehouse.*;
import com.mmu.schedulingapp.service.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
@RestController
@RequestMapping("/api/admin/warehouses")
public class WarehouseManagementController {
    private final WarehouseService warehouses;
    public WarehouseManagementController(WarehouseService warehouses) { this.warehouses=warehouses; }
    @GetMapping public ResponseEntity<WarehouseDirectory> directory(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String search) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(warehouses.directory(page,size,search));
    }
    @GetMapping("/{id}") public ResponseEntity<WarehouseItem> warehouse(@PathVariable int id) {
        return warehouses.snapshot(id).map(state -> response(state,HttpStatus.OK)).orElseGet(() -> ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build());
    }
    @PostMapping public ResponseEntity<WarehouseItem> create(@RequestBody WarehouseWriteRequest request) {
        var state=warehouses.create(request);
        return ResponseEntity.created(URI.create("/api/admin/warehouses/"+state.id())).cacheControl(CacheControl.noStore()).eTag(state.etag()).body(state.response());
    }
    @PutMapping("/{id}") public ResponseEntity<WarehouseItem> replace(@PathVariable int id,@RequestBody WarehouseWriteRequest request,@RequestHeader(name="If-Match",required=false) String etag) {
        return warehouses.replace(id,request,etag).map(state -> response(state,HttpStatus.OK)).orElseGet(() -> ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build());
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable int id,@RequestHeader(name="If-Match",required=false) String etag) {
        return ResponseEntity.status(warehouses.delete(id,etag)?HttpStatus.NO_CONTENT:HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).build();
    }
    private ResponseEntity<WarehouseItem> response(WarehouseState state,HttpStatus status) { return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).eTag(state.etag()).body(state.response()); }
}
