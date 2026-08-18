package com.example.payments.model;

import java.math.BigDecimal;

/** A monetary amount. {@code BigDecimal} is a {@code java.*} type, so it stays a leaf. */
public class Amount {

    private BigDecimal value;
    private String currency;

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }
}
