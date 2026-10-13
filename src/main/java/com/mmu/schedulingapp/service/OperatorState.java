package com.mmu.schedulingapp.service;
import com.mmu.schedulingapp.dto.operator.*;
import com.mmu.schedulingapp.model.Operator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.io.*;
public record OperatorState(Integer id,String name,String phoneNumber,String email,String vehicleType,Double maxCapacity,Integer warehouseId,Operator.AvailabilityStatus status) {
    public static OperatorState from(Operator o) { return new OperatorState(o.getOperatorId(),o.getName(),o.getPhoneNumber(),o.getEmail(),o.getVehicleType(),o.getMaxCapacity(),o.getWarehouse()==null?null:o.getWarehouse().getWarehouseId(),o.getAvailabilityStatus()); }
    public OperatorItem response() { return new OperatorItem(id,name,phoneNumber,email,vehicleType,maxCapacity,warehouseId,status); }
    public String etag() {
        try {
            var bytes=new ByteArrayOutputStream(); var output=new DataOutputStream(bytes);
            for(String field:new String[]{"operator-v1",String.valueOf(id),name,phoneNumber,email,vehicleType,String.valueOf(maxCapacity),String.valueOf(warehouseId),String.valueOf(status)}) {
                output.writeBoolean(field!=null);
                if(field!=null) { var encoded=field.getBytes(StandardCharsets.UTF_8); output.writeInt(encoded.length); output.write(encoded); }
            }
            return "\""+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))+"\"";
        } catch(java.security.NoSuchAlgorithmException | IOException failure) { throw new IllegalStateException("Operator version could not be computed."); }
    }
    @Override public String toString() { return "OperatorState[REDACTED]"; }
}
