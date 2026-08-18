package com.example.payments.model;

/** A payee or payer. Reached from {@link Payment}, so it gets a selector by recursion. */
public class Party {

    private String name;
    private Agent agent;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }
}
