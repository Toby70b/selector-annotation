package com.example.lombokdemo.model;

import lombok.Data;

/** Nested Lombok model, reached by recursion — not annotated itself. */
@Data
public class LombokParty {

    private String name;
    private LombokAgent agent;
}
