package com.zzhy.yg_ai.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;

public final class DateTimeUtils {

    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";
    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
    public static final List<DateTimeFormatter> DATE_TIME_PARSE_FORMATTERS = List.of(
            DATE_TIME_FORMATTER,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    );

    private static final JsonDeserializer<LocalDateTime> LOCAL_DATE_TIME_DESERIALIZER = new JsonDeserializer<>() {
        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String value = parser.getValueAsString();
            if (value == null || value.trim().isEmpty()) {
                return null;
            }
            String normalized = value.trim();
            for (DateTimeFormatter formatter : DATE_TIME_PARSE_FORMATTERS) {
                try {
                    return truncateToMillis(LocalDateTime.parse(normalized, formatter));
                } catch (DateTimeParseException ignore) {
                    // try next format
                }
            }
            return (LocalDateTime) context.handleWeirdStringValue(
                    LocalDateTime.class,
                    value,
                    "Unsupported datetime format, expected one of %s".formatted(DATE_TIME_PARSE_FORMATTERS)
            );
        }
    };

    private DateTimeUtils() {
    }

    public static LocalDateTime now() {
        return truncateToMillis(LocalDateTime.now());
    }

    public static LocalDate today() {
        return LocalDate.now();
    }

    public static LocalDateTime truncateToMillis(LocalDateTime value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MILLIS);
    }

    public static String format(LocalDateTime value) {
        return value == null ? null : truncateToMillis(value).format(DATE_TIME_FORMATTER);
    }

    public static JsonDeserializer<LocalDateTime> localDateTimeDeserializer() {
        return LOCAL_DATE_TIME_DESERIALIZER;
    }

    public static ObjectMapper createObjectMapper() {
        return configureObjectMapper(new ObjectMapper());
    }

    public static ObjectMapper configureObjectMapper(ObjectMapper objectMapper) {
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME_FORMATTER));
        javaTimeModule.addDeserializer(LocalDateTime.class, LOCAL_DATE_TIME_DESERIALIZER);
        objectMapper.registerModule(javaTimeModule);
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }
}
