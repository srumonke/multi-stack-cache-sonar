package main

import (
	"errors"
	"testing"
)

func TestSettleSumsAmounts(t *testing.T) {
	got, err := Settle([]int64{250, 100, 50})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != 400 {
		t.Fatalf("got %d, want 400", got)
	}
}

func TestSettleRejectsNegative(t *testing.T) {
	if _, err := Settle([]int64{10, -1}); !errors.Is(err, ErrNegativeAmount) {
		t.Fatalf("got %v, want ErrNegativeAmount", err)
	}
}

func TestFeeUsesLowerRateForLargeTotals(t *testing.T) {
	if got := Fee(200000); got != 1000 {
		t.Fatalf("got %d, want 1000", got)
	}
	if got := Fee(10000); got != 100 {
		t.Fatalf("got %d, want 100", got)
	}
}
