package com.reconiq.normalization.mapping;

import com.reconiq.normalization.rules.StatusMapping;

public class BankMapper extends EnvelopeMapper {
    public BankMapper(StatusMapping statusMapping) {
        super(statusMapping);
    }

    @Override
    public String source() {
        return "BANK";
    }
}