package com.mmu.schedulingapp.dto.recipient;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.*;

/** Scoped to the new write contract; legacy Jackson coercion behavior is unchanged. */
public final class RecipientWriteValues {
    private RecipientWriteValues() { }
    public static final class Text extends ValueDeserializer<String> {
        @Override public String deserialize(JsonParser parser,DeserializationContext context) {
            if(!parser.hasToken(JsonToken.VALUE_STRING)) throw DatabindException.from(parser,"Recipient text fields must be JSON strings.");
            return parser.getString();
        }
    }
    public static final class Priority extends ValueDeserializer<RecipientRequestDTO.PriorityLevel> {
        @Override public RecipientRequestDTO.PriorityLevel deserialize(JsonParser parser,DeserializationContext context) {
            if(!parser.hasToken(JsonToken.VALUE_STRING)) throw DatabindException.from(parser,"Recipient priority must be a JSON string.");
            try { return RecipientRequestDTO.PriorityLevel.valueOf(parser.getString()); }
            catch(IllegalArgumentException failure) { throw DatabindException.from(parser,"Invalid recipient priority."); }
        }
    }
}
