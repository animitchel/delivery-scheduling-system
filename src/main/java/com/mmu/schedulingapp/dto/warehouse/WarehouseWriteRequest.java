package com.mmu.schedulingapp.dto.warehouse;
import com.fasterxml.jackson.annotation.*;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;
import java.util.*;
/** Full create/replace contract: IDs and operator membership cannot be supplied. */
public final class WarehouseWriteRequest {
    private final Set<String> seen = new HashSet<>();
    private String name, address;
    private BigDecimal latitude, longitude;
    private void accept(String field) { if (!seen.add(field)) throw new IllegalArgumentException("Duplicate warehouse field."); }
    @JsonAnySetter public void reject(String field, Object value) { throw new IllegalArgumentException("Unexpected warehouse field."); }
    @JsonProperty @JsonDeserialize(using=WarehouseWriteValues.Text.class) public void setName(String value) { accept("name"); name=value; }
    @JsonProperty @JsonDeserialize(using=WarehouseWriteValues.Text.class) public void setAddress(String value) { accept("address"); address=value; }
    @JsonProperty @JsonDeserialize(using=WarehouseWriteValues.Coordinate.class) public void setLatitude(BigDecimal value) { accept("latitude"); latitude=value; }
    @JsonProperty @JsonDeserialize(using=WarehouseWriteValues.Coordinate.class) public void setLongitude(BigDecimal value) { accept("longitude"); longitude=value; }
    public String getName() { return name; }
    public String getAddress() { return address; }
    public BigDecimal getLatitude() { return latitude; }
    public BigDecimal getLongitude() { return longitude; }
    @Override public String toString() { return "WarehouseWriteRequest[REDACTED]"; }
}
