package com.kyriba.a;

/** Arithmetic used by the demo so there is real bytecode to analyse. */
public class Calc {

    public int add(int a, int b) {
        return a + b;
    }

    public int divide(int a, int b) {
        if (b == 0) {
            throw new IllegalArgumentException("cannot divide by zero");
        }
        return a / b;
    }

    /**
     * Left untested on purpose, and the empty catch block is an intentional
     * smell (java:S108) so the quality gate has a real finding to report.
     */
    public int parseOrZero(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }
}
