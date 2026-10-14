package com.mmu.schedulingapp.dto.warehouse;
import tools.jackson.core.*;
import tools.jackson.databind.*;
import java.math.BigDecimal;
public final class WarehouseWriteValues {
    private WarehouseWriteValues() { }
    public static final class Text extends ValueDeserializer<String> {
        @Override public String deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) throw DatabindException.from(parser,"Warehouse text must be a JSON string.");
            return parser.getString();
        }
    }
    public static final class Coordinate extends ValueDeserializer<BigDecimal> {
        @Override public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT) && !parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) throw DatabindException.from(parser,"Warehouse coordinates must be JSON numbers.");
            return parser.getDecimalValue();
        }
    }
}
