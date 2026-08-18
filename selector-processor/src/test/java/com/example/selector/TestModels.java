package com.example.selector;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixture sources compiled by {@link SelectorCompilation}.
 *
 * <p>{@link #paymentGraph()} is deliberately awkward: it mixes primitives, boxed types, enums,
 * collections, arrays, JDK types, a third-party type, an acronym-style getter, a self-reference,
 * a two-type cycle, a shared nested type and a spread of methods that must <em>not</em> be
 * mistaken for properties.
 */
final class TestModels {

    static final String BASE_PACKAGE = "com.acme.model";

    private TestModels() {
    }

    /** A realistic, messy model graph rooted at {@code com.acme.model.Payment}. */
    static Map<String, String> paymentGraph() {
        Map<String, String> sources = new LinkedHashMap<>();

        sources.put("com.acme.model.Payment", """
                package com.acme.model;

                import com.example.selector.GenerateSelector;
                import com.thirdparty.Vendor;
                import java.math.BigDecimal;
                import java.time.LocalDate;
                import java.util.List;
                import java.util.Map;

                @GenerateSelector
                public class Payment extends BaseEntity {

                    private String id;
                    private int retryCount;
                    private boolean urgent;
                    private Boolean verified;
                    private char flag;
                    private Party creditor;
                    private Party debtor;
                    private Amount amount;
                    private Status status;
                    private List<String> tags;
                    private Map<String, Amount> breakdown;
                    private LocalDate valueDate;
                    private String url;
                    private Payment parent;
                    private double[] rates;
                    private Vendor vendor;

                    // --- ordinary properties -------------------------------------------------
                    public String getId() { return id; }
                    public void setId(String id) { this.id = id; }

                    // primitive -> must be boxed in the selector so it can return null
                    public int getRetryCount() { return retryCount; }
                    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

                    public char getFlag() { return flag; }
                    public void setFlag(char flag) { this.flag = flag; }

                    // is-style boolean getters
                    public boolean isUrgent() { return urgent; }
                    public void setUrgent(boolean urgent) { this.urgent = urgent; }

                    public Boolean getVerified() { return verified; }
                    public void setVerified(Boolean verified) { this.verified = verified; }

                    // --- nested model types --------------------------------------------------
                    public Party getCreditor() { return creditor; }
                    public void setCreditor(Party creditor) { this.creditor = creditor; }

                    // same nested type twice: the selector must be generated only once
                    public Party getDebtor() { return debtor; }
                    public void setDebtor(Party debtor) { this.debtor = debtor; }

                    public Amount getAmount() { return amount; }
                    public void setAmount(Amount amount) { this.amount = amount; }

                    // self-reference: recursion must terminate
                    public Payment getParent() { return parent; }
                    public void setParent(Payment parent) { this.parent = parent; }

                    // --- leaves that must NOT be descended into ------------------------------
                    public Status getStatus() { return status; }              // enum
                    public void setStatus(Status status) { this.status = status; }

                    public List<String> getTags() { return tags; }            // collection
                    public Map<String, Amount> getBreakdown() { return breakdown; }
                    public LocalDate getValueDate() { return valueDate; }     // java.*
                    public double[] getRates() { return rates; }              // array
                    public Vendor getVendor() { return vendor; }              // outside basePackage

                    // JavaBeans acronym rule: getURL -> URL, not uRL
                    public String getURL() { return url; }

                    // --- must all be ignored -------------------------------------------------
                    public static String getStaticThing() { return "no"; }    // static
                    public void getNothing() { }                              // void
                    public String getWithParam(String in) { return in; }      // has parameters
                    protected String getProtectedThing() { return "no"; }     // not public
                    private String getSecret() { return "no"; }               // not public
                    public String notAGetter() { return "no"; }               // wrong prefix
                    public String isNotBoolean() { return "no"; }             // is- but not boolean
                    public String get() { return "no"; }                      // bare "get"
                }
                """);

        // Inherited getters must be picked up too.
        sources.put("com.acme.model.BaseEntity", """
                package com.acme.model;

                public class BaseEntity {
                    private String correlationId;
                    public String getCorrelationId() { return correlationId; }
                    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
                }
                """);

        sources.put("com.acme.model.Party", """
                package com.acme.model;

                public class Party {
                    private String name;
                    private Agent agent;
                    private Payment payment;

                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }

                    public Agent getAgent() { return agent; }
                    public void setAgent(Agent agent) { this.agent = agent; }

                    // back-reference: Payment -> Party -> Payment must not loop forever
                    public Payment getPayment() { return payment; }
                    public void setPayment(Payment payment) { this.payment = payment; }
                }
                """);

        sources.put("com.acme.model.Agent", """
                package com.acme.model;

                public class Agent {
                    private FinancialInstitution financialInstitution;
                    public FinancialInstitution getFinancialInstitution() { return financialInstitution; }
                    public void setFinancialInstitution(FinancialInstitution fi) { this.financialInstitution = fi; }
                }
                """);

        sources.put("com.acme.model.FinancialInstitution", """
                package com.acme.model;

                public class FinancialInstitution {
                    private String bic;
                    public String getBic() { return bic; }
                    public void setBic(String bic) { this.bic = bic; }
                }
                """);

        sources.put("com.acme.model.Amount", """
                package com.acme.model;

                import java.math.BigDecimal;

                public class Amount {
                    private BigDecimal value;
                    private String currency;

                    public BigDecimal getValue() { return value; }
                    public void setValue(BigDecimal value) { this.value = value; }

                    public String getCurrency() { return currency; }
                    public void setCurrency(String currency) { this.currency = currency; }
                }
                """);

        sources.put("com.acme.model.Status", """
                package com.acme.model;

                public enum Status { PENDING, SETTLED, REJECTED }
                """);

        // Outside the configured base package: must be treated as a leaf.
        sources.put("com.thirdparty.Vendor", """
                package com.thirdparty;

                public class Vendor {
                    private String name;
                    public String getName() { return name; }
                }
                """);

        return sources;
    }

    /** Convenience: the fixture graph plus one extra source. */
    static Map<String, String> paymentGraphPlus(String typeName, String source) {
        Map<String, String> sources = paymentGraph();
        sources.put(typeName, source);
        return sources;
    }
}
