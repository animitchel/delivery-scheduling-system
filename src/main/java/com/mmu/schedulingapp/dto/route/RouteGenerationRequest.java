package com.mmu.schedulingapp.dto.route;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.time.LocalDate;

/** Date-only generation contract; delivery selection and assignment belong to the planner. */
@JsonDeserialize(using = RouteGenerationRequest.Reader.class)
public record RouteGenerationRequest(@NotNull LocalDate scheduledDate) {
    public static LocalDate date(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("Invalid planning date.");
        LocalDate date = LocalDate.parse(value);
        if (date.getYear() < 1000 || date.getYear() > 9999) throw new IllegalArgumentException("Invalid planning date.");
        return date;
    }
    public static final class Reader extends JsonDeserializer<RouteGenerationRequest> {
        @Override public RouteGenerationRequest deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.START_OBJECT)) throw JsonMappingException.from(parser, "Planning request must be an object.");
            LocalDate date = null; boolean seen = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (!parser.hasToken(JsonToken.FIELD_NAME) || !"scheduledDate".equals(parser.currentName()) || seen)
                    throw JsonMappingException.from(parser, "Unexpected or duplicate planning field.");
                seen = true;
                if (parser.nextToken() != JsonToken.VALUE_STRING) throw JsonMappingException.from(parser, "Planning date must be a string.");
                try { date = date(parser.getText()); }
                catch (IllegalArgumentException | java.time.format.DateTimeParseException failure) {
                    throw JsonMappingException.from(parser, "Invalid planning date.");
                }
            }
            return new RouteGenerationRequest(date);
        }
    }
}
