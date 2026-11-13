package com.reconiq.normalization.mapping;

import com.reconiq.normalization.rules.StatusMapping;

public class GatewayMapper extends EnvelopeMapper {
    public GatewayMapper(StatusMapping statusMapping) {
        super(statusMapping);
    }

    @Override
    public String source() {
        return "PAYMENT_GATEWAY";
    }
}