package com.mmu.schedulingapp.dto.operator;
import com.mmu.schedulingapp.model.Operator;
public record OperatorItem(Integer operatorId,String name,String phoneNumber,String email,String vehicleType,Double maxCapacity,Integer warehouseId,Operator.AvailabilityStatus availabilityStatus) { }
