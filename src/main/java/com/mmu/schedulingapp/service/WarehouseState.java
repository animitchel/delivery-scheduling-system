package com.mmu.schedulingapp.service;
import com.mmu.schedulingapp.dto.warehouse.WarehouseItem;
import com.mmu.schedulingapp.model.Warehouse;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.io.*;
/** Content fingerprint, without schema changes; references are checked separately at deletion. */
public record WarehouseState(Integer id,String name,String address,BigDecimal latitude,BigDecimal longitude) {
    public static WarehouseState from(Warehouse warehouse) { return new WarehouseState(warehouse.getWarehouseId(),warehouse.getName(),warehouse.getAddress(),warehouse.getLatitude(),warehouse.getLongitude()); }
    public WarehouseItem response() { return new WarehouseItem(id,name,address,latitude,longitude); }
    public String etag() {
        try {
            var bytes=new ByteArrayOutputStream(); var output=new DataOutputStream(bytes);
            for(String field:new String[]{"warehouse-v1",String.valueOf(id),name,address,number(latitude),number(longitude)}) {
                output.writeBoolean(field!=null);
                if(field!=null) { var encoded=field.getBytes(StandardCharsets.UTF_8); output.writeInt(encoded.length); output.write(encoded); }
            }
            return "\""+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))+"\"";
        } catch(java.security.NoSuchAlgorithmException | IOException failure) { throw new IllegalStateException("Warehouse version could not be computed."); }
    }
    private static String number(BigDecimal value) { return value==null?null:value.stripTrailingZeros().toPlainString(); }
    @Override public String toString() { return "WarehouseState[REDACTED]"; }
}
