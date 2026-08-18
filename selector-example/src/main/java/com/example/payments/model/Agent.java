package com.example.payments.model;

/** The servicing agent for a {@link Party}. */
public class Agent {

    private String name;
    private FinancialInstitution financialInstitution;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public FinancialInstitution getFinancialInstitution() {
        return financialInstitution;
    }

    public void setFinancialInstitution(FinancialInstitution financialInstitution) {
        this.financialInstitution = financialInstitution;
    }
}
