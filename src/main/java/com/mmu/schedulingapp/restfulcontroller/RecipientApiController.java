package com.mmu.schedulingapp.restfulcontroller;


import com.mmu.schedulingapp.dto.recipient.RecipientRequestDTO;
import com.mmu.schedulingapp.dto.recipient.RecipientResponseDTO;
import com.mmu.schedulingapp.service.RecipientService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/scheduling-app/recipients")
public class RecipientApiController {

    private final RecipientService recipientService;

    public RecipientApiController(RecipientService recipientService) {
        this.recipientService = recipientService;
    }

    @GetMapping
    public ResponseEntity<List<RecipientResponseDTO>> getRecipients() {
        List<RecipientResponseDTO> recipients = recipientService.getAllRecipientDTOs();

        if (recipients.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        return ResponseEntity.ok(recipients);
    }

    @GetMapping("/{id}")
    public ResponseEntity<RecipientResponseDTO> getRecipient(@PathVariable int id) {
        Optional<RecipientResponseDTO> optionalRecipient = recipientService.getRecipient(id);

        return optionalRecipient
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/recipients")
    public List<RecipientResponseDTO> getRecipientsByIds(@RequestParam List<Integer> ids) {
        return recipientService.findByIds(ids); // return in any order
    }

    @PostMapping
    public ResponseEntity<RecipientResponseDTO> createRecipient(@Valid @RequestBody RecipientRequestDTO dto) {
        RecipientResponseDTO created = recipientService.createRecipient(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<RecipientResponseDTO> updateRecipient(
            @PathVariable int id,
            @Valid @RequestBody RecipientRequestDTO updatedDto) {
        return recipientService.updateRecipient(id, updatedDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<RecipientResponseDTO> deleteRecipient(@PathVariable int id) {
        return recipientService.deleteRecipient(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
