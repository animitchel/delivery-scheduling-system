package com.mmu.schedulingapp.dto.warehouse;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.math.BigDecimal;
public final class WarehouseWriteValues {
    private WarehouseWriteValues() { }
    public static final class Text extends JsonDeserializer<String> {
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) throw JsonMappingException.from(parser,"Warehouse text must be a JSON string.");
            return parser.getText();
        }
    }
    public static final class Coordinate extends JsonDeserializer<BigDecimal> {
        @Override public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT) && !parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) throw JsonMappingException.from(parser,"Warehouse coordinates must be JSON numbers.");
            return parser.getDecimalValue();
        }
    }
}
