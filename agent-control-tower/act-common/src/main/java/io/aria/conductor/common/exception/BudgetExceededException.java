package io.aria.conductor.common.exception;

public class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(String message) {
        super(message);
    }

    public BudgetExceededException(long used, long limit) {
        super(String.format("Token budget exceeded: used %d, limit %d", used, limit));
    }
}
