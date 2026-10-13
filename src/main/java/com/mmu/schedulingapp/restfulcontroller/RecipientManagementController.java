package com.mmu.schedulingapp.restfulcontroller;

import com.mmu.schedulingapp.dto.recipient.*;
import com.mmu.schedulingapp.service.RecipientService;
import com.mmu.schedulingapp.service.RecipientState;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/admin/recipients")
public class RecipientManagementController {
    private final RecipientService recipients;
    public RecipientManagementController(RecipientService recipients) { this.recipients=recipients; }
    @GetMapping public ResponseEntity<RecipientDirectory> directory(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String search) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(recipients.directory(page,size,search));
    }
    @GetMapping("/{id}") public ResponseEntity<RecipientResponseDTO> recipient(@PathVariable int id) {
        return recipients.versionedRecipient(id).map(state -> response(state,HttpStatus.OK)).orElseGet(() -> ResponseEntity.notFound().build());
    }
    @PostMapping public ResponseEntity<RecipientResponseDTO> create(@Valid @RequestBody RecipientWriteRequest request) {
        var state=recipients.createRecipient(request);
        return ResponseEntity.created(URI.create("/api/admin/recipients/"+state.id())).cacheControl(CacheControl.noStore()).eTag(state.etag()).body(state.response());
    }
    @PutMapping("/{id}") public ResponseEntity<RecipientResponseDTO> replace(@PathVariable int id,@Valid @RequestBody RecipientWriteRequest request,@RequestHeader(name="If-Match",required=false) String etag) {
        return recipients.replaceRecipient(id,request,etag).map(state -> response(state,HttpStatus.OK)).orElseGet(() -> ResponseEntity.notFound().build());
    }
    private ResponseEntity<RecipientResponseDTO> response(RecipientState state,HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).eTag(state.etag()).body(state.response());
    }
}
