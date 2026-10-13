package com.mmu.schedulingapp.dto.operator;
import com.mmu.schedulingapp.model.Operator;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.util.*;
public final class OperatorWriteRequest {
    private final Set<String> seen=new HashSet<>();
    private void accept(String field) { if(!seen.add(field)) throw new IllegalArgumentException("Duplicate operator field."); }
    @JsonAnySetter public void reject(String field,Object value) { throw new IllegalArgumentException("Unexpected operator field."); }
    private String name;
    @JsonProperty @JsonDeserialize(using=OperatorWriteValues.Text.class) public void setName(String value) { accept("name"); name=value; }
    public String getName() { return name; }
    private String phoneNumber;
    @JsonProperty @JsonDeserialize(using=OperatorWriteValues.Text.class) public void setPhoneNumber(String value) { accept("phoneNumber"); phoneNumber=value; }
    public String getPhoneNumber() { return phoneNumber; }
    private String email;
    @JsonProperty @JsonDeserialize(using=OperatorWriteValues.Text.class) public void setEmail(String value) { accept("email"); email=value; }
    public String getEmail() { return email; }
    private String vehicleType;
    @JsonProperty @JsonDeserialize(using=OperatorWriteValues.Text.class) public void setVehicleType(String value) { accept("vehicleType"); vehicleType=value; }
    public String getVehicleType() { return vehicleType; }
    private Double maxCapacity;
    @JsonProperty @JsonDeserialize(using=OperatorWriteValues.Number.class) public void setMaxCapacity(Double value) { accept("maxCapacity"); maxCapacity=value; }
    public Double getMaxCapacity() { return maxCapacity; }
    private Integer warehouseId;
    @JsonProperty @JsonDeserialize(using=OperatorWriteValues.Id.class) public void setWarehouseId(Integer value) { accept("warehouseId"); warehouseId=value; }
    public Integer getWarehouseId() { return warehouseId; }
    private Operator.AvailabilityStatus availabilityStatus;
    @JsonProperty @JsonDeserialize(using=OperatorWriteValues.Status.class) public void setAvailabilityStatus(Operator.AvailabilityStatus value) { accept("availabilityStatus"); availabilityStatus=value; }
    public Operator.AvailabilityStatus getAvailabilityStatus() { return availabilityStatus; }
    @Override public String toString() { return "OperatorWriteRequest[REDACTED]"; }
}
