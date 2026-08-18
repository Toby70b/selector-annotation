package com.example.payments.model;

/** Four levels below {@link Payment} — the depth that makes hand-written selectors painful. */
public class FinancialInstitution {

    private String bic;
    private String name;

    public String getBic() {
        return bic;
    }

    public void setBic(String bic) {
        this.bic = bic;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
