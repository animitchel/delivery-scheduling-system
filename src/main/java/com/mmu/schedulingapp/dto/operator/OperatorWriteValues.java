package com.mmu.schedulingapp.dto.operator;
import tools.jackson.core.*;
import tools.jackson.databind.*;
import com.mmu.schedulingapp.model.Operator;
public final class OperatorWriteValues {
    private OperatorWriteValues() { }
    public static final class Text extends ValueDeserializer<String> {
        public String deserialize(JsonParser p,DeserializationContext c) { if(!p.hasToken(JsonToken.VALUE_STRING)) throw DatabindException.from(p,"Operator text must be a string."); return p.getString(); }
    }
    public static final class Number extends ValueDeserializer<Double> {
        public Double deserialize(JsonParser p,DeserializationContext c) { if(!p.hasToken(JsonToken.VALUE_NUMBER_INT)&&!p.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) throw DatabindException.from(p,"Capacity must be a number."); return p.getDoubleValue(); }
    }
    public static final class Id extends ValueDeserializer<Integer> {
        public Integer deserialize(JsonParser p,DeserializationContext c) { if(!p.hasToken(JsonToken.VALUE_NUMBER_INT)) throw DatabindException.from(p,"Warehouse ID must be an integer."); return p.getIntValue(); }
    }
    public static final class Status extends ValueDeserializer<Operator.AvailabilityStatus> {
        public Operator.AvailabilityStatus deserialize(JsonParser p,DeserializationContext c) {
            if(!p.hasToken(JsonToken.VALUE_STRING)) throw DatabindException.from(p,"Availability must be a string.");
            try { return Operator.AvailabilityStatus.valueOf(p.getString()); } catch(IllegalArgumentException failure) { throw DatabindException.from(p,"Invalid availability."); }
        }
    }
}
