package com.kyriba.b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LedgerTest {

    @Test
    void sumsRecordedEntries() {
        Ledger ledger = new Ledger();
        ledger.record(250L);
        ledger.record(100L);
        assertEquals(350L, ledger.balanceInCents());
    }

    @Test
    void startsAtZero() {
        assertEquals(0L, new Ledger().balanceInCents());
    }

    @Test
    void reportsOverdraft() {
        Ledger ledger = new Ledger();
        ledger.record(-500L);
        assertTrue(ledger.isOverdrawn());
    }

    @Test
    void creditIsNotOverdrawn() {
        Ledger ledger = new Ledger();
        ledger.record(500L);
        assertFalse(ledger.isOverdrawn());
    }
}
