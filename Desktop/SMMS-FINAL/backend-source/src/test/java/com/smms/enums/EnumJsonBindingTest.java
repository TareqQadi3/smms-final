package com.smms.enums;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smms.config.WebConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regression tests for DEF-001 / DEF-002: lenient, consistent enum JSON and param binding. */
class EnumJsonBindingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void ticketStatusSerializesToLowercaseDbValue(TicketStatus s) throws Exception {
        assertThat(mapper.writeValueAsString(s)).isEqualTo("\"" + s.getDbValue() + "\"");
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void ticketStatusDeserializesLowercaseAndUppercase(TicketStatus s) throws Exception {
        assertThat(mapper.readValue("\"" + s.getDbValue() + "\"", TicketStatus.class)).isEqualTo(s);
        assertThat(mapper.readValue("\"" + s.name() + "\"", TicketStatus.class)).isEqualTo(s);
    }

    @Test
    void ticketStatusRejectsUnknown() {
        assertThatThrownBy(() -> mapper.readValue("\"bogus\"", TicketStatus.class))
            .isInstanceOf(JsonMappingException.class);
    }

    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void paramConverterAcceptsBothForms(TicketStatus s) {
        WebConfig.TicketStatusParamConverter c = new WebConfig.TicketStatusParamConverter();
        assertThat(c.convert(s.getDbValue())).isEqualTo(s);
        assertThat(c.convert(s.name())).isEqualTo(s);
    }

    @Test
    void paramConverterBlankIsNullAndUnknownRejected() {
        WebConfig.TicketStatusParamConverter c = new WebConfig.TicketStatusParamConverter();
        assertThat(c.convert("  ")).isNull();
        assertThatThrownBy(() -> c.convert("bogus")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(SubType.class)
    void subTypeCreatorIsCaseInsensitive(SubType t) throws Exception {
        assertThat(mapper.readValue("\"" + t.name() + "\"", SubType.class)).isEqualTo(t);
        assertThat(mapper.readValue("\"" + t.name().toUpperCase() + "\"", SubType.class)).isEqualTo(t);
    }

    @Test
    void subTypeDoesNotInventMappingsForLabels() {
        assertThatThrownBy(() -> mapper.readValue("\"Civil Works\"", SubType.class))
            .isInstanceOf(JsonMappingException.class);
    }
}
