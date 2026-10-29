package com.kyriba.a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CalcTest {

    private final Calc calc = new Calc();

    @Test
    void addsTwoNumbers() {
        assertEquals(7, calc.add(3, 4));
    }

    @Test
    void dividesTwoNumbers() {
        assertEquals(5, calc.divide(10, 2));
    }

    @Test
    void rejectsDivideByZero() {
        assertThrows(IllegalArgumentException.class, () -> calc.divide(1, 0));
    }
}
