'use strict';

function subtotal(items) {
  return items.reduce((sum, item) => sum + item.price * item.qty, 0);
}

function applyDiscount(amount, pct) {
  if (pct < 0 || pct > 100) {
    throw new RangeError('pct must be between 0 and 100');
  }
  return amount - (amount * pct) / 100;
}

// Left untested on purpose, and the duplicated condition is an intentional
// smell (javascript:S1764) so the quality gate has a real finding to report.
function isEmpty(items) {
  return items.length === 0 || items.length === 0;
}

module.exports = { subtotal, applyDiscount, isEmpty };
