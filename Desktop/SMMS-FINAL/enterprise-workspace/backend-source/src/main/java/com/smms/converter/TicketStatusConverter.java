package com.smms.converter;

import com.smms.enums.TicketStatus;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Converts TicketStatus enum to/from its lowercase DB value string.
 * This is needed because Java enum names are uppercase (NEW, IN_PROGRESS)
 * while the PostgreSQL ticket_status enum uses lowercase (new, in_progress).
 *
 * autoApply = true means this converter is used for ALL TicketStatus fields
 * across all entities without requiring explicit @Convert annotations.
 */
@Converter(autoApply = true)
public class TicketStatusConverter implements AttributeConverter<TicketStatus, String> {

    @Override
    public String convertToDatabaseColumn(TicketStatus attribute) {
        return attribute == null ? null : attribute.getDbValue();
    }

    @Override
    public TicketStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : TicketStatus.fromDbValue(dbData);
    }
}
