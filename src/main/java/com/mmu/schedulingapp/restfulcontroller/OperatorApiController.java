package com.mmu.schedulingapp.restfulcontroller;

import com.mmu.schedulingapp.dto.operator.OperatorRequestDTO;
import com.mmu.schedulingapp.dto.operator.OperatorResponseDTO;
import com.mmu.schedulingapp.service.OperatorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/scheduling-app/operators")

public class OperatorApiController {

    private final OperatorService operatorService;

    public OperatorApiController(OperatorService operatorService){
        this.operatorService = operatorService;
    }

    @GetMapping
    public ResponseEntity<List<OperatorResponseDTO>> getDrivers() {
        List<OperatorResponseDTO> drivers = operatorService.getAllDriverDTOs();


        return ResponseEntity.ok(drivers);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OperatorResponseDTO> getDriver(@PathVariable int id){
        Optional<OperatorResponseDTO> optionalDriver = operatorService.getDriver(id);

       return optionalDriver
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }


    @PostMapping
    public ResponseEntity<OperatorResponseDTO> createDriver(@Valid @RequestBody OperatorRequestDTO dto){
        return ResponseEntity.status(HttpStatus.CREATED).body(operatorService.createDriver(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<OperatorResponseDTO> updateDriver(@PathVariable int id,
                                                            @Valid @RequestBody OperatorRequestDTO updatedDriverDto) {
        return operatorService.updateDriver(id, updatedDriverDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping("/{id}")
    public ResponseEntity<OperatorResponseDTO> patchDriver(@PathVariable int id,
                                                           @RequestBody OperatorRequestDTO partialDto) {
        return operatorService.patchDriver(id, partialDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<OperatorResponseDTO> deleteDriver(@PathVariable int id) {
        return operatorService.deleteDriver(id).map(
                ResponseEntity::ok).orElse(ResponseEntity.notFound().build());

    }



}
