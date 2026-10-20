package io.aria.conductor.common.exception;

public class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(String message) {
        super(message);
    }

    public BudgetExceededException(long used, long limit) {
        super(String.format("Token budget exceeded: used %d, limit %d."
                + " Raise circuit.breaker.max.tokens.per.run in Configure > System Config,"
                + " or split the task into smaller runs.", used, limit));
    }
}
