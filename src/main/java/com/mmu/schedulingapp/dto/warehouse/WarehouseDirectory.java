package com.mmu.schedulingapp.dto.warehouse;
import java.util.List;
public record WarehouseDirectory(List<WarehouseItem> warehouses, int page, int size, long totalElements, int totalPages) { }
