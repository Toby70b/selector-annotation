package com.example.selector;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a model type as the root of a null-safe selector graph.
 *
 * <p>Placing this on a class, record or interface makes {@link SelectorProcessor} generate a
 * {@code <Type>Selector} exposing one method per property. Scalar/leaf properties return the
 * raw (nullable) value; properties whose type is itself a model type return that type's
 * selector, so chains stay null-safe from end to end:
 *
 * <pre>{@code
 * String bic = PaymentSelector.of(payment)
 *         .creditor()
 *         .agent()
 *         .financialInstitution()
 *         .bic();          // null anywhere in the chain -> null, never an NPE
 * }</pre>
 *
 * <p>Generation is <strong>recursive</strong>: annotate the root only, and the processor walks
 * the type graph and generates a selector for every reachable model type. Cycles
 * ({@code A -> B -> A}) and diamonds (two properties of the same type) are handled — each type
 * is generated exactly once.
 *
 * <p>Recursion simply follows the types your model references — across packages, however they
 * are arranged — stopping at the leaf rules described in {@link SelectorProcessor}: JDK types,
 * enums, collections, arrays and primitives are returned as values rather than descended into.
 * No configuration is required.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface GenerateSelector {

    /**
     * Optional package prefix bounding how far recursion follows property types. When set, only
     * types under this package get a selector; anything else becomes an opaque leaf value.
     *
     * <p>Empty by default, meaning no bound — every referenced type outside the JDK is descended
     * into. Set it when a model references a third-party type you would rather not generate
     * selectors for, e.g. {@code @GenerateSelector("com.acme")} to keep generation to your own
     * code.
     *
     * <p>Matching is on whole package segments, so {@code com.acme.model} does not capture
     * {@code com.acme.modelling}.
     */
    String value() default "";
}
