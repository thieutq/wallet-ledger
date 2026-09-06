package com.walletledger.domain.ledger;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class HoldStatusConverter implements AttributeConverter<HoldStatus, String> {

    @Override
    public String convertToDatabaseColumn(HoldStatus attribute) {
        return attribute == null ? null : attribute.name().toLowerCase();
    }

    @Override
    public HoldStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : HoldStatus.valueOf(dbData.toUpperCase());
    }
}
