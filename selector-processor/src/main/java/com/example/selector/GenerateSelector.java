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
 * <p>Bound what counts as "reachable" with the compiler option
 * {@code -Aselector.basePackage=<your.model.package>}; see {@link SelectorProcessor} for the
 * rules on which property types are descended into and which are treated as leaves.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface GenerateSelector {
}
