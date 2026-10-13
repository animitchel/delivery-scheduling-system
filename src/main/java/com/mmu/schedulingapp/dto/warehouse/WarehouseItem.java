package com.mmu.schedulingapp.dto.warehouse;
import java.math.BigDecimal;
/** Scalar setup view; never materializes assigned operator collections. */
public record WarehouseItem(Integer warehouseId, String name, String address, BigDecimal latitude, BigDecimal longitude) { }
