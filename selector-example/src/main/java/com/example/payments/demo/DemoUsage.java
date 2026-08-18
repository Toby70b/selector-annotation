package com.example.payments.demo;

import com.example.payments.model.Account;
import com.example.payments.model.Agent;
import com.example.payments.model.Amount;
import com.example.payments.model.FinancialInstitution;
import com.example.payments.model.Party;
import com.example.payments.model.Payment;
import com.example.payments.model.PaymentSelector;
import com.example.payments.model.Status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Runnable demonstration of the generated selectors.
 *
 * <p>{@code PaymentSelector} and friends do not exist in source control — they are generated
 * into {@code target/generated-sources/annotations} during compilation. If your IDE flags the
 * import as unresolved, build the module once and enable annotation processing.
 *
 * <p>Run with: {@code mvn -pl selector-example exec:java} or straight from the IDE.
 */
public final class DemoUsage {

    private DemoUsage() {
    }

    public static void main(String[] args) {
        Payment populated = populatedPayment();
        Payment sparse = new Payment();   // no payee, no account, no amount
        Payment absent = null;            // the payment itself is missing

        System.out.println("=== fully populated ===");
        printSelections(populated);

        System.out.println();
        System.out.println("=== payment present, nested fields missing ===");
        printSelections(sparse);

        System.out.println();
        System.out.println("=== payment itself null ===");
        printSelections(absent);

        System.out.println();
        System.out.println("=== terminal methods ===");
        System.out.println("orElse on a null payee  : "
                + PaymentSelector.of(absent).payee().orElse(fallbackParty()).getName());
        System.out.println("asOptional (absent)     : "
                + PaymentSelector.of(absent).asOptional().isPresent());
        System.out.println("asOptional (populated)  : "
                + PaymentSelector.of(populated).asOptional().isPresent());
        System.out.println("orNull mid-chain        : "
                + PaymentSelector.of(populated).payee().agent().orNull());
    }

    /**
     * The whole point: each of these is one null-safe line, replacing a hand-written
     * {@code selectBic} / {@code selectAccountNumber} / {@code selectPayeeName} helper.
     * None of them can throw, whatever is missing.
     */
    private static void printSelections(Payment payment) {
        System.out.println("  bic           = "
                + PaymentSelector.of(payment).payee().agent().financialInstitution().bic());
        System.out.println("  accountNumber = "
                + PaymentSelector.of(payment).creditorAccount().accountNumber());
        System.out.println("  payeeName     = "
                + PaymentSelector.of(payment).payee().name());
        System.out.println("  currency      = "
                + PaymentSelector.of(payment).amount().currency());
        // Primitive on the model, boxed in the selector, so "missing" is distinguishable from 0.
        System.out.println("  retryCount    = "
                + PaymentSelector.of(payment).retryCount());
        System.out.println("  status        = "
                + PaymentSelector.of(payment).status());
    }

    private static Payment populatedPayment() {
        FinancialInstitution institution = new FinancialInstitution();
        institution.setBic("NWBKGB2L");
        institution.setName("NatWest");

        Agent agent = new Agent();
        agent.setName("NatWest Agent");
        agent.setFinancialInstitution(institution);

        Party payee = new Party();
        payee.setName("ACME Ltd");
        payee.setAgent(agent);

        Account account = new Account();
        account.setAccountNumber("12345678");
        account.setIban("GB33BUKB20201555555555");

        Amount amount = new Amount();
        amount.setValue(new BigDecimal("125.00"));
        amount.setCurrency("GBP");

        Payment payment = new Payment();
        payment.setId("PMT-001");
        payment.setPayee(payee);
        payment.setCreditorAccount(account);
        payment.setAmount(amount);
        payment.setRetryCount(2);
        payment.setUrgent(true);
        payment.setValueDate(LocalDate.of(2026, 8, 18));
        payment.setStatus(Status.PENDING);
        payment.setTags(List.of("priority", "gbp"));
        return payment;
    }

    private static Party fallbackParty() {
        Party fallback = new Party();
        fallback.setName("<unknown payee>");
        return fallback;
    }
}
