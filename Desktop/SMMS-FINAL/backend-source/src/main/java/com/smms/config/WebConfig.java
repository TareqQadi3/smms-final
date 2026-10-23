package com.smms.config;

import com.smms.enums.TicketStatus;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** MVC customisation: lenient binding of request parameters. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Converts "under_review" or "UNDER_REVIEW" request params to TicketStatus. */
    public static class TicketStatusParamConverter implements Converter<String, TicketStatus> {
        @Override
        public TicketStatus convert(String source) {
            if (source.isBlank()) return null;
            return TicketStatus.fromString(source);
        }
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new TicketStatusParamConverter());
    }
}
