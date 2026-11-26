package com.yuyay.consultation.dto;

import com.yuyay.consultation.validation.ValidHandoffSessionDurations;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@ValidHandoffSessionDurations
public record CreateHandoffSessionDTO(

        @JsonDeserialize(using = IntegerDurationDeserializer.class)
        @Min(1)
        @Max(72)
        Integer expiresInHours,

        @JsonDeserialize(using = IntegerDurationDeserializer.class)
        @Min(1)
        @Max(4320)
        Integer expiresInMinutes,

        @JsonDeserialize(using = IntegerDurationDeserializer.class)
        @NotNull
        @Min(1)
        @Max(4320)
        Integer sessionWindowMinutes,

        Boolean requireAccessCode,

        Boolean allowDownload

) {
    public CreateHandoffSessionDTO(Integer expiresInHours, Integer sessionWindowMinutes, Boolean requireAccessCode) {
        this(expiresInHours, null, sessionWindowMinutes, requireAccessCode, null);
    }

    public long linkDurationMinutes() {
        return expiresInMinutes != null ? expiresInMinutes : expiresInHours * 60L;
    }

    static final class IntegerDurationDeserializer extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Integer.class, "La duración debe ser un número entero");
            }
            return parser.getIntValue();
        }
    }
}
