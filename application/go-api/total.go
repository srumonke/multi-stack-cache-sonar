// Package main holds the settlement helpers scanned by the demo.
package main

import "errors"

// ErrNegativeAmount is returned when an amount is below zero.
var ErrNegativeAmount = errors.New("amount must not be negative")

// Settle sums the supplied amounts, in cents.
func Settle(amounts []int64) (int64, error) {
	var total int64
	for _, amount := range amounts {
		if amount < 0 {
			return 0, ErrNegativeAmount
		}
		total += amount
	}
	return total, nil
}

// Fee returns the processing fee, in cents, for a settled amount.
func Fee(total int64) int64 {
	if total > 100000 {
		return total / 200
	}
	return total / 100
}

// Describe is left untested on purpose so coverage lands below 100 percent.
func Describe(total int64) string {
	if total == 0 {
		return "empty"
	}
	return "settled"
}

func main() {}
