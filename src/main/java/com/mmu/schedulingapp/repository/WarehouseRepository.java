package com.mmu.schedulingapp.repository;

import com.mmu.schedulingapp.model.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WarehouseRepository extends JpaRepository<Warehouse, Integer> {
    Optional<Warehouse> findByName(String name);
    java.util.List<Warehouse> findTop2ByName(String name);
    @org.springframework.data.jpa.repository.Query("select new com.mmu.schedulingapp.dto.warehouse.WarehouseItem(w.warehouseId,w.name,w.address,w.latitude,w.longitude) from Warehouse w where lower(w.name) like :pattern escape '!' or lower(w.address) like :pattern escape '!'")
    org.springframework.data.domain.Page<com.mmu.schedulingapp.dto.warehouse.WarehouseItem> directory(@org.springframework.data.repository.query.Param("pattern") String pattern,org.springframework.data.domain.Pageable pageable);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select w from Warehouse w where w.warehouseId=:id")
    Optional<Warehouse> lockById(@org.springframework.data.repository.query.Param("id") int id);
    @org.springframework.data.jpa.repository.Query("select count(o)>0 from Operator o where o.warehouse.warehouseId=:id")
    boolean hasOperators(@org.springframework.data.repository.query.Param("id") int id);
}
