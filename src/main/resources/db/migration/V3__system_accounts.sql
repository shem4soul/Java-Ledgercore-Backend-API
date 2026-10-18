-- One SYSTEM "cash" account per currency. Deposits debit it, withdrawals credit it. System accounts may go negative
-- (the negative balance is the total of funds issued to customers), so that the sum of ALL balances is always zero.
INSERT INTO accounts (id, account_number, owner_id, type, currency, status, balance_minor, daily_limit_minor) VALUES
    ('00000000-0000-0000-0000-00000000a001', '0000000001', NULL, 'SYSTEM', 'NGN', 'ACTIVE', 0, 9223372036854775807),
    ('00000000-0000-0000-0000-00000000a002', '0000000002', NULL, 'SYSTEM', 'USD', 'ACTIVE', 0, 9223372036854775807),
    ('00000000-0000-0000-0000-00000000a003', '0000000003', NULL, 'SYSTEM', 'GBP', 'ACTIVE', 0, 9223372036854775807),
    ('00000000-0000-0000-0000-00000000a004', '0000000004', NULL, 'SYSTEM', 'EUR', 'ACTIVE', 0, 9223372036854775807);
