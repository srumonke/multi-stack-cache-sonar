package com.kyriba.b;

import java.util.ArrayList;
import java.util.List;

/** A tiny ledger, so the second Gradle service analyses different bytecode. */
public class Ledger {

    private final List<Long> entriesInCents = new ArrayList<>();

    public void record(long amountInCents) {
        entriesInCents.add(amountInCents);
    }

    public long balanceInCents() {
        long total = 0L;
        for (long entry : entriesInCents) {
            total += entry;
        }
        return total;
    }

    public boolean isOverdrawn() {
        return balanceInCents() < 0L;
    }

    /**
     * Left untested on purpose. The condition can never be true, which is an
     * intentional smell (java:S2583) that only a bytecode-aware analysis
     * reports with confidence.
     */
    public String describe() {
        long balance = balanceInCents();
        if (balance > 0L && balance < 0L) {
            return "impossible";
        }
        return balance >= 0L ? "credit" : "debit";
    }
}
