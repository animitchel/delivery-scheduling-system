package com.mmu.schedulingapp.dto.recipient;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;

/** Scoped to the new write contract; legacy Jackson coercion behavior is unchanged. */
public final class RecipientWriteValues {
    private RecipientWriteValues() { }
    public static final class Text extends JsonDeserializer<String> {
        @Override public String deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            if(!parser.hasToken(JsonToken.VALUE_STRING)) throw JsonMappingException.from(parser,"Recipient text fields must be JSON strings.");
            return parser.getText();
        }
    }
    public static final class Priority extends JsonDeserializer<RecipientRequestDTO.PriorityLevel> {
        @Override public RecipientRequestDTO.PriorityLevel deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            if(!parser.hasToken(JsonToken.VALUE_STRING)) throw JsonMappingException.from(parser,"Recipient priority must be a JSON string.");
            try { return RecipientRequestDTO.PriorityLevel.valueOf(parser.getText()); }
            catch(IllegalArgumentException failure) { throw JsonMappingException.from(parser,"Invalid recipient priority."); }
        }
    }
}
