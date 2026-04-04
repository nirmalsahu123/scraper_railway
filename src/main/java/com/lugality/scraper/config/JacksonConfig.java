package com.lugality.scraper.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Jackson configuration.
 *
 * Without this, fields like LocalDate / LocalDateTime in TrademarkData
 * are serialized as arrays [year, month, day] instead of "2024-01-15".
 *
 * FIX: registers JavaTimeModule so all java.time.* types serialize as ISO strings.
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // Write dates as "2024-01-15" not as [2024, 1, 15]
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
