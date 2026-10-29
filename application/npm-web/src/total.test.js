'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');

const { subtotal, applyDiscount } = require('./total');

test('subtotal sums price times quantity', () => {
  assert.equal(subtotal([{ price: 250, qty: 2 }, { price: 100, qty: 1 }]), 600);
});

test('subtotal of an empty cart is zero', () => {
  assert.equal(subtotal([]), 0);
});

test('applyDiscount takes a percentage off', () => {
  assert.equal(applyDiscount(1000, 10), 900);
});

test('applyDiscount rejects an out of range percentage', () => {
  assert.throws(() => applyDiscount(1000, 101), RangeError);
});
