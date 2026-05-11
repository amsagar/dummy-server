package com.pods.agent.workflow.engine.domain;

/**
 * Typed error categories. The engine attaches one of these to every activity
 * failure and exposes them to error-edge routing so transitions can match on
 * {@code errorClass=='TIMEOUT'} instead of brittle message-substring checks.
 *
 * Resolves audit finding #6 ("ambiguous error routing — string matching").
 */
public enum ErrorClass {

    /** Expression parse or evaluation error (SecureSpelEvaluator returned failure). */
    EXPRESSION,

    /** Variable validation or schema check failed (required missing, type mismatch). */
    VALIDATION,

    /** Activity exceeded its deadline. */
    TIMEOUT,

    /** Tool / plugin executed but threw or returned a logical failure. */
    TOOL,

    /** Sub-flow ended in closed.terminated. */
    SUBFLOW,

    /** Anything not classified above. Always set when the engine catches an unexpected Throwable. */
    UNCAUGHT
}
