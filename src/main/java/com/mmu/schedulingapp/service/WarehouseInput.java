package com.mmu.schedulingapp.service;
import com.mmu.schedulingapp.dto.warehouse.*;
import com.mmu.schedulingapp.model.Warehouse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.*;
public record WarehouseInput(String name, String address, BigDecimal latitude, BigDecimal longitude) {
    public static WarehouseInput from(WarehouseWriteRequest request) { return create(request.getName(),request.getAddress(),request.getLatitude(),request.getLongitude()); }
    public static WarehouseInput from(WarehouseRequestDTO request) { return create(request.getName(),request.getAddress(),request.getLatitude(),request.getLongitude()); }
    private static WarehouseInput create(String name,String address,BigDecimal latitude,BigDecimal longitude) {
        return new WarehouseInput(text(name,255),text(address,1000),coordinate(latitude,90),coordinate(longitude,180));
    }
    private static String text(String value,int limit) {
        if(value==null || value.isBlank() || value.length()>limit) throw invalid();
        return value.strip();
    }
    private static BigDecimal coordinate(BigDecimal value,int bound) {
        if(value==null || value.precision()>18 || value.scale() < -6 || value.scale()>12 || value.abs().compareTo(BigDecimal.valueOf(bound))>0) throw invalid();
        try { return value.setScale(6,RoundingMode.UNNECESSARY); } catch(ArithmeticException failure) { throw invalid(); }
    }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid warehouse fields. Use valid names, addresses and coordinates with at most six decimal places."); }
    public void apply(Warehouse warehouse) { warehouse.setName(name); warehouse.setAddress(address); warehouse.setLatitude(latitude); warehouse.setLongitude(longitude); }
    @Override public String toString() { return "WarehouseInput[REDACTED]"; }
}
