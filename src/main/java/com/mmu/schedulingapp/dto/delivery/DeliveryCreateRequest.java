package com.mmu.schedulingapp.dto.delivery;

import tools.jackson.core.*;
import tools.jackson.databind.*;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.mmu.schedulingapp.model.Delivery;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.HashSet;

/** Strict preparation payload; identity, assignment and lifecycle state belong to the server. */
@JsonDeserialize(using = DeliveryCreateRequest.Reader.class)
public record DeliveryCreateRequest(@NotNull @Positive Integer recipientId,
        @NotNull LocalDate scheduledDate, @NotNull LocalTime startTimeWindow,
        @NotNull LocalTime endTimeWindow, @NotNull Delivery.PriorityLevel priorityLevel,
        @Size(max = 255) String specialInstructions) {
    @Override public String toString() { return "DeliveryCreateRequest[REDACTED]"; }

    public static LocalDate date(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("Invalid scheduled date.");
        return LocalDate.parse(value);
    }
    public static final class Reader extends ValueDeserializer<DeliveryCreateRequest> {
        @Override public DeliveryCreateRequest deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.START_OBJECT)) throw DatabindException.from(parser, "Delivery must be a JSON object.");
            Integer recipient = null; LocalDate date = null; LocalTime start = null, end = null;
            Delivery.PriorityLevel priority = null; String instructions = null;
            var seen = new HashSet<String>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (!parser.hasToken(JsonToken.PROPERTY_NAME)) throw DatabindException.from(parser, "Invalid delivery fields.");
                String field = parser.currentName();
                if (!seen.add(field)) throw DatabindException.from(parser, "Duplicate delivery field.");
                parser.nextToken();
                try {
                    switch (field) {
                        case "recipientId" -> {
                            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) throw new IllegalArgumentException();
                            recipient = parser.getIntValue();
                        }
                        case "scheduledDate" -> date = date(text(parser));
                        case "startTimeWindow" -> start = time(text(parser));
                        case "endTimeWindow" -> end = time(text(parser));
                        case "priorityLevel" -> priority = Delivery.PriorityLevel.valueOf(text(parser));
                        case "specialInstructions" -> instructions = parser.hasToken(JsonToken.VALUE_NULL) ? null : text(parser);
                        default -> throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException | java.time.format.DateTimeParseException failure) {
                    throw DatabindException.from(parser, "Invalid delivery fields.");
                }
            }
            return new DeliveryCreateRequest(recipient, date, start, end, priority, instructions);
        }
        private static String text(JsonParser parser) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) throw new IllegalArgumentException();
            return parser.getString();
        }
        private static LocalTime time(String value) {
            if (!value.matches("[0-9]{2}:[0-9]{2}(:[0-9]{2})?")) throw new IllegalArgumentException();
            return LocalTime.parse(value);
        }
    }
}
