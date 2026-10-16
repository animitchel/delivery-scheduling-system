package com.mmu.schedulingapp.dto.route;

import tools.jackson.core.*;
import tools.jackson.databind.*;
import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.HashSet;

@JsonDeserialize(using=RouteWindowAssessmentRequest.Reader.class)
public record RouteWindowAssessmentRequest(@NotNull LocalDateTime departure,
        @NotNull @PositiveOrZero Long serviceDurationSeconds) {
    public static final class Reader extends ValueDeserializer<RouteWindowAssessmentRequest> {
        @Override public RouteWindowAssessmentRequest deserialize(JsonParser parser,DeserializationContext context) {
            if(!parser.hasToken(JsonToken.START_OBJECT)) throw DatabindException.from(parser,"Assessment must be an object.");
            LocalDateTime departure=null; Long seconds=null; var seen=new HashSet<String>();
            while(parser.nextToken()!=JsonToken.END_OBJECT) {
                if(!parser.hasToken(JsonToken.PROPERTY_NAME)) throw DatabindException.from(parser,"Invalid assessment fields.");
                var field=parser.currentName();
                if(!seen.add(field)) throw DatabindException.from(parser,"Duplicate assessment field.");
                parser.nextToken();
                try {
                    switch(field) {
                        case "departure" -> {
                            if(!parser.hasToken(JsonToken.VALUE_STRING)) throw new IllegalArgumentException();
                            var text=parser.getString();
                            if(!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}")) throw new IllegalArgumentException();
                            departure=LocalDateTime.parse(text);
                            if(departure.getYear()<1000) throw new IllegalArgumentException();
                        }
                        case "serviceDurationSeconds" -> {
                            if(!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) throw new IllegalArgumentException();
                            seconds=parser.getLongValue();
                        }
                        default -> throw new IllegalArgumentException();
                    }
                } catch(IllegalArgumentException | java.time.format.DateTimeParseException failure) {
                    throw DatabindException.from(parser,"Invalid assessment fields.");
                }
            }
            return new RouteWindowAssessmentRequest(departure,seconds);
        }
    }
}
