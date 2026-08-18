package com.example.lombokdemo;

import com.example.lombokdemo.model.LombokAgent;
import com.example.lombokdemo.model.LombokParty;
import com.example.lombokdemo.model.LombokPayment;
import com.example.lombokdemo.model.LombokPaymentSelector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the processor sees getters that Lombok injects rather than ones written by hand.
 *
 * <p>Much of the proof is compile-time: if the selector were generated without Lombok's
 * properties, the calls below would not resolve and this class would not compile.
 */
class LombokSelectorTest {

    private static LombokPayment populated() {
        LombokAgent agent = new LombokAgent();
        agent.setBic("NWBKGB2L");

        LombokParty payee = new LombokParty();
        payee.setName("ACME Ltd");
        payee.setAgent(agent);

        LombokPayment payment = new LombokPayment();
        payment.setId("PMT-001");
        payment.setRetryCount(3);
        payment.setUrgent(true);
        payment.setAmount(new BigDecimal("125.00"));
        payment.setPayee(payee);
        return payment;
    }

    @Test
    @DisplayName("Lombok-generated getters become selector properties")
    void readsLombokGeneratedProperties() {
        LombokPayment payment = populated();

        assertEquals("PMT-001", LombokPaymentSelector.of(payment).id());
        assertEquals(new BigDecimal("125.00"), LombokPaymentSelector.of(payment).amount());
    }

    @Test
    @DisplayName("primitives injected by Lombok are still boxed")
    void boxesLombokPrimitives() {
        assertEquals(3, LombokPaymentSelector.of(populated()).retryCount());
        assertEquals(true, LombokPaymentSelector.of(populated()).urgent());

        // Boxed, so a null root reports "absent" rather than 0/false.
        assertNull(LombokPaymentSelector.of(null).retryCount());
        assertNull(LombokPaymentSelector.of(null).urgent());
    }

    @Test
    @DisplayName("recursion works through Lombok types that are not annotated themselves")
    void recursesThroughLombokTypes() {
        assertEquals("NWBKGB2L", LombokPaymentSelector.of(populated()).payee().agent().bic());
    }

    @Test
    @DisplayName("null-safety holds across a Lombok graph")
    void chainIsNullSafe() {
        assertNull(LombokPaymentSelector.of(new LombokPayment()).payee().agent().bic());
        assertNull(LombokPaymentSelector.of(null).payee().agent().bic());
    }
}
