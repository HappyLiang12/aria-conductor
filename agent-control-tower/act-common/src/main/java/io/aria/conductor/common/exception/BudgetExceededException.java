package io.aria.conductor.common.exception;

/**
 * A circuit-breaker or budget refusal. Every cap is configured in System
 * Config, so the failure carries that surface for the dashboard's action.
 */
public class BudgetExceededException extends RuntimeException implements ActionableFailure {

    private final String code;

    public BudgetExceededException(String message) {
        this(message, "BUDGET_EXCEEDED");
    }

    public BudgetExceededException(String message, String code) {
        super(message);
        this.code = code;
    }

    public BudgetExceededException(long used, long limit) {
        super(String.format("Token budget exceeded: used %d, limit %d."
                + " Raise circuit.breaker.max.tokens.per.run in Configure > System Config,"
                + " or split the task into smaller runs.", used, limit));
        this.code = "TOKEN_BUDGET_EXCEEDED";
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public ErrorSurface surface() {
        return ErrorSurface.SYSTEM_CONFIG;
    }
}
