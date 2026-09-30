package com.cqwlw.maintenance.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 统一后端输入输出时间格式：yyyy-MM-dd HH:mm:ss（GMT+8）。 */
@Configuration
public class JacksonConfig {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer javaTimeFormat() {
        return builder -> builder
                .serializers(new LocalDateTimeSerializer(FORMATTER))
                .deserializers(new LocalDateTimeDeserializer(FORMATTER));
    }
}
