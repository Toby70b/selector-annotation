package com.example.lombokdemo.model;

import com.example.selector.GenerateSelector;
import lombok.Data;

import java.math.BigDecimal;

/**
 * A Lombok-style model: no getters written by hand, all injected by {@code @Data}.
 *
 * <p>If the selector processor could not see Lombok's getters, the generated
 * {@code LombokPaymentSelector} would have only the terminal methods and no properties — and
 * {@code LombokSelectorTest} would fail to compile.
 */
@Data
@GenerateSelector
public class LombokPayment {

    private String id;
    private int retryCount;
    private boolean urgent;
    private BigDecimal amount;
    private LombokParty payee;
}
