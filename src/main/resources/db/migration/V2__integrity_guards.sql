-- Database-enforced integrity. These hold even if application code has a bug or someone uses a SQL console.

-- 1) The ledger and the audit trail are append-only.
CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: table is append-only', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_ledger_entries_no_truncate
    BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER trg_audit_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_audit_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- 2) Every ledger transaction must balance (debits = credits, at least two entries). Checked at COMMIT,
--    so the application may insert the entries one by one but can never commit an unbalanced transaction.
CREATE FUNCTION check_transaction_balanced() RETURNS trigger AS $$
DECLARE
    total_debit  bigint;
    total_credit bigint;
    entry_count  integer;
BEGIN
    SELECT COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0),
           COUNT(*)
      INTO total_debit, total_credit, entry_count
      FROM ledger_entries
     WHERE transaction_id = NEW.transaction_id;

    IF total_debit <> total_credit OR entry_count < 2 THEN
        RAISE EXCEPTION 'Ledger transaction % is unbalanced (debits %, credits %, entries %)',
            NEW.transaction_id, total_debit, total_credit, entry_count
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_transaction_balanced();
