package com.mmu.schedulingapp.service;

import com.mmu.schedulingapp.mapper.WarehouseMapper;
import com.mmu.schedulingapp.dto.warehouse.*;
import com.mmu.schedulingapp.model.Warehouse;
import com.mmu.schedulingapp.repository.WarehouseRepository;
import jakarta.persistence.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.*;
import java.util.*;

@Service
public class WarehouseService {
    private final WarehouseRepository warehouses;
    private final EntityManager entities;
    @org.springframework.beans.factory.annotation.Autowired
    public WarehouseService(WarehouseRepository warehouses,EntityManager entities) { this.warehouses=warehouses; this.entities=entities; }
    // Retained for recording-repository collection tests without a persistence context.
    public WarehouseService(WarehouseRepository warehouses) { this(warehouses,null); }

    @Transactional(readOnly=true)
    public List<WarehouseResponseDTO> getAllWarehouses() { return warehouses.findAll().stream().map(WarehouseMapper::toResponseDTO).toList(); }
    @Transactional(readOnly=true)
    public Optional<WarehouseResponseDTO> getWarehouse(int id) { return warehouses.findById(id).map(WarehouseMapper::toResponseDTO); }
    @Transactional(readOnly=true)
    public Optional<WarehouseState> snapshot(int id) { validId(id); return warehouses.findById(id).map(WarehouseState::from); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public WarehouseDirectory directory(int page,int size,String search) {
        if(page<0 || page>10000 || size<1 || size>50 || search==null || search.length()>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid warehouse directory query.");
        String pattern="%"+search.strip().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        var result=warehouses.directory(pattern,PageRequest.of(page,size,Sort.by("name","warehouseId")));
        return new WarehouseDirectory(result.getContent(),page,size,result.getTotalElements(),result.getTotalPages());
    }
    @Transactional
    public WarehouseState create(WarehouseWriteRequest request) { return create(WarehouseInput.from(request)); }
    @Transactional
    public WarehouseResponseDTO createWarehouse(WarehouseRequestDTO request) { return WarehouseMapper.toResponseDTO(saveNew(WarehouseInput.from(request))); }
    private WarehouseState create(WarehouseInput input) { return WarehouseState.from(saveNew(input)); }
    private Warehouse saveNew(WarehouseInput input) { var warehouse=new Warehouse(); input.apply(warehouse); return warehouses.saveAndFlush(warehouse); }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Optional<WarehouseState> replace(int id,WarehouseWriteRequest request,String etag) {
        validId(id); requireEtag(etag); var input=WarehouseInput.from(request);
        return locked(id).map(warehouse -> { match(warehouse,etag); input.apply(warehouse); return WarehouseState.from(warehouses.saveAndFlush(warehouse)); });
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Optional<WarehouseResponseDTO> updateWarehouse(int id,WarehouseRequestDTO request) {
        var input=WarehouseInput.from(request);
        return locked(id).map(warehouse -> { input.apply(warehouse); return WarehouseMapper.toResponseDTO(warehouses.saveAndFlush(warehouse)); });
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public boolean delete(int id,String etag) {
        validId(id); requireEtag(etag);
        return locked(id).map(warehouse -> { match(warehouse,etag); remove(warehouse); return true; }).orElse(false);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Optional<WarehouseResponseDTO> deleteWarehouse(int id) {
        return locked(id).map(warehouse -> {
            var state=WarehouseState.from(warehouse); remove(warehouse);
            return WarehouseResponseDTO.builder().warehouseId(state.id()).name(state.name()).address(state.address()).latitude(state.latitude()).longitude(state.longitude()).assignedDrivers(List.of()).build();
        });
    }
    private Optional<Warehouse> locked(int id) {
        return warehouses.lockById(id).map(warehouse -> {
            // Refresh OSIV-cached state under the SQL lock before testing the client fingerprint.
            if(entities!=null) { entities.unwrap(org.hibernate.Session.class).setReadOnly(warehouse,false); entities.refresh(warehouse,LockModeType.PESSIMISTIC_WRITE); }
            return warehouse;
        });
    }
    private void remove(Warehouse warehouse) {
        if(warehouses.hasOperators(warehouse.getWarehouseId())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Warehouse is assigned to operators and cannot be deleted.");
        // Existing FK constraints protect an assignment racing with deletion; flush inside the transaction.
        warehouses.delete(warehouse); warehouses.flush();
    }
    private static void validId(int id) { if(id<1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid warehouse ID."); }
    private static void requireEtag(String etag) {
        if(etag==null || etag.isBlank()) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"Read the warehouse and provide its ETag in If-Match.");
        if(!etag.matches("\"[a-f0-9]{64}\"")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provide one strong warehouse ETag in If-Match.");
    }
    private static void match(Warehouse warehouse,String etag) {
        if(!WarehouseState.from(warehouse).etag().equals(etag)) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,"Warehouse changed. Reload before retrying.");
    }
}
