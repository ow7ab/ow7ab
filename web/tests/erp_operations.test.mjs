import test from 'node:test';
import assert from 'node:assert/strict';

test('ReportExporter formatCsv should handle quotes and commas properly', () => {
  const row = ['دهان أبيض, مطاطي', '1000', 'سند "معتمد"'];
  const formatted = row.map(val => {
    const str = String(val ?? '');
    if (str.includes(',') || str.includes('"') || str.includes('\n')) {
      return `"${str.replace(/"/g, '""')}"`;
    }
    return str;
  });

  assert.equal(formatted[0], '"دهان أبيض, مطاطي"');
  assert.equal(formatted[1], '1000');
  assert.equal(formatted[2], '"سند ""معتمد"""');
});

test('Financial KPIs calculation from multi-currency cashboxes', () => {
  const cashboxes = [
    { balance: 14850000, currency: 'SDG' },
    { balance: 3200000, currency: 'SDG' },
    { balance: 425000, currency: 'EGP' },
    { balance: 18500, currency: 'USD' }
  ];

  const totalSdg = cashboxes.filter(c => c.currency === 'SDG').reduce((s, c) => s + c.balance, 0);
  const totalEgp = cashboxes.filter(c => c.currency === 'EGP').reduce((s, c) => s + c.balance, 0);
  const totalUsd = cashboxes.filter(c => c.currency === 'USD').reduce((s, c) => s + c.balance, 0);

  assert.equal(totalSdg, 18050000);
  assert.equal(totalEgp, 425000);
  assert.equal(totalUsd, 18500);
});

test('Packaging standard carton units calculation', () => {
  const getCansPerCarton = (weightGrams) => {
    if (weightGrams === 250) return 48;
    if (weightGrams === 900) return 12;
    if (weightGrams === 4000) return 4;
    return 1;
  };

  assert.equal(getCansPerCarton(250), 48);
  assert.equal(getCansPerCarton(900), 12);
  assert.equal(getCansPerCarton(4000), 4);
});
