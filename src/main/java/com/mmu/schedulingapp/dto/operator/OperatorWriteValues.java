package com.mmu.schedulingapp.dto.operator;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.mmu.schedulingapp.model.Operator;
import java.io.IOException;
public final class OperatorWriteValues {
    private OperatorWriteValues() { }
    public static final class Text extends JsonDeserializer<String> {
        public String deserialize(JsonParser p,DeserializationContext c) throws IOException { if(!p.hasToken(JsonToken.VALUE_STRING)) throw JsonMappingException.from(p,"Operator text must be a string."); return p.getText(); }
    }
    public static final class Number extends JsonDeserializer<Double> {
        public Double deserialize(JsonParser p,DeserializationContext c) throws IOException { if(!p.hasToken(JsonToken.VALUE_NUMBER_INT)&&!p.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) throw JsonMappingException.from(p,"Capacity must be a number."); return p.getDoubleValue(); }
    }
    public static final class Id extends JsonDeserializer<Integer> {
        public Integer deserialize(JsonParser p,DeserializationContext c) throws IOException { if(!p.hasToken(JsonToken.VALUE_NUMBER_INT)) throw JsonMappingException.from(p,"Warehouse ID must be an integer."); return p.getIntValue(); }
    }
    public static final class Status extends JsonDeserializer<Operator.AvailabilityStatus> {
        public Operator.AvailabilityStatus deserialize(JsonParser p,DeserializationContext c) throws IOException {
            if(!p.hasToken(JsonToken.VALUE_STRING)) throw JsonMappingException.from(p,"Availability must be a string.");
            try { return Operator.AvailabilityStatus.valueOf(p.getText()); } catch(IllegalArgumentException failure) { throw JsonMappingException.from(p,"Invalid availability."); }
        }
    }
}
