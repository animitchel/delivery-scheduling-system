package com.mmu.schedulingapp.service;
import com.mmu.schedulingapp.dto.operator.*;
import com.mmu.schedulingapp.model.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Validator;
public record OperatorInput(String name,String phoneNumber,String email,String vehicleType,Double maxCapacity,Integer warehouseId,Operator.AvailabilityStatus status) {
    public static OperatorInput from(OperatorWriteRequest r,Validator v) { return create(r.getName(),r.getPhoneNumber(),r.getEmail(),r.getVehicleType(),r.getMaxCapacity(),r.getWarehouseId(),r.getAvailabilityStatus(),v); }
    public static OperatorInput from(OperatorRequestDTO r,Integer warehouseId,Validator v) { return create(r.getName(),r.getPhoneNumber(),r.getEmail(),r.getVehicleType(),r.getMaxCapacity(),warehouseId,r.getAvailabilityStatus(),v); }
    private static OperatorInput create(String name,String phone,String email,String vehicle,Double capacity,Integer warehouse,Operator.AvailabilityStatus status,Validator validator) {
        name=text(name,100);phone=text(phone,20);email=text(email,100);
        if(!phone.matches("^[+]?[0-9]{10,15}$") || !validator.validateValue(OperatorRequestDTO.class,"email",email).isEmpty() || vehicle!=null&&vehicle.length()>50 || capacity==null || !Double.isFinite(capacity) || capacity<0 || warehouse==null || warehouse<1 || status==null) throw invalid();
        return new OperatorInput(name,phone,email,vehicle==null||vehicle.isBlank()?null:vehicle.strip(),capacity==0?0.0:capacity,warehouse,status);
    }
    private static String text(String value,int limit) { if(value==null||value.isBlank()||value.length()>limit) throw invalid();return value.strip(); }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid operator fields."); }
    public void apply(Operator operator,Warehouse warehouse) { operator.setName(name);operator.setPhoneNumber(phoneNumber);operator.setEmail(email);operator.setVehicleType(vehicleType);operator.setMaxCapacity(maxCapacity);operator.setWarehouse(warehouse);operator.setAvailabilityStatus(status); }
    @Override public String toString() { return "OperatorInput[REDACTED]"; }
}
