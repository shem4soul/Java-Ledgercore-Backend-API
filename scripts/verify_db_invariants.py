#!/usr/bin/env python3
"""
Verifies LedgerCore's database layer on a real PostgreSQL, independent of the Java code:
  * Flyway migrations V1..V3 apply cleanly
  * append-only ledger/audit triggers, deferred "transaction must balance" trigger, no-overdraft CHECK
  * the exact locking + idempotency algorithm the Java services use, under heavy concurrency:
      - money is conserved and no customer account goes negative
      - opposing transfers (A->B and B->A) never deadlock when locks are taken in id order
        (negative control: taking them in request order DOES deadlock)
      - a hot account allows exactly as many debits as it can afford
      - 20 concurrent requests with the same Idempotency-Key move money exactly once
Usage: PGHOST=localhost PGUSER=ledgercore PGPASSWORD=... python3 scripts/verify_db_invariants.py
"""
import os, sys, uuid, random, glob, threading, subprocess
from concurrent.futures import ThreadPoolExecutor
import psycopg2
from psycopg2 import errors
import psycopg2.extras
psycopg2.extras.register_uuid()

HOST, PORT = os.getenv("PGHOST", "localhost"), os.getenv("PGPORT", "5432")
USER, PWD = os.getenv("PGUSER", "ledgercore"), os.getenv("PGPASSWORD", "ledgercore")
DB = os.getenv("VERIFY_DB", "ledgercore_verify")
MIGRATIONS = sorted(glob.glob(os.path.join(os.path.dirname(__file__), "..", "src/main/resources/db/migration/V*.sql")))
passed, failed = 0, 0

def connect(db=DB, autocommit=False):
    c = psycopg2.connect(host=HOST, port=PORT, user=USER, password=PWD, dbname=db)
    c.autocommit = autocommit
    return c

def check(name, cond, detail=""):
    global passed, failed
    if cond: passed += 1; print(f"  PASS  {name}")
    else: failed += 1; print(f"  FAIL  {name} {detail}")

def setup():
    admin = connect("postgres", True)
    cur = admin.cursor()
    cur.execute(f"DROP DATABASE IF EXISTS {DB}"); cur.execute(f"CREATE DATABASE {DB}")
    admin.close()
    for m in MIGRATIONS:
        subprocess.run(["psql", "-q", "-v", "ON_ERROR_STOP=1", "-h", HOST, "-p", PORT, "-U", USER, "-d", DB, "-f", m],
                       check=True, env={**os.environ, "PGPASSWORD": PWD}, stdout=subprocess.DEVNULL)
    print(f"applied {len(MIGRATIONS)} migrations")

def new_user_account(cur, currency="NGN", balance=0):
    uid, aid = uuid.uuid4(), uuid.uuid4()
    cur.execute("INSERT INTO users(id,email,password_hash,full_name) VALUES (%s,%s,'x','T')", (uid, f"{uid}@t.io"))
    cur.execute("""INSERT INTO accounts(id,account_number,owner_id,type,currency,daily_limit_minor)
                   VALUES (%s, lpad(nextval('account_number_seq')::text,10,'0'), %s,'CUSTOMER',%s,10000000000)""", (aid, uid, currency))
    return aid

class Insufficient(Exception): pass

def post(conn, debit, credit, amount, ordered=True):
    """Same algorithm as LedgerService.post(): lock both rows (ordered by id), validate, write tx + 2 entries, update balances."""
    cur = conn.cursor()
    cur.execute("SET LOCAL lock_timeout = '10s'")
    ids = sorted([debit, credit], key=str) if ordered else [debit, credit]
    if ordered:
        cur.execute("SELECT id, balance_minor, type FROM accounts WHERE id = ANY(%s::uuid[]) ORDER BY id FOR UPDATE", ([str(debit), str(credit)],))
    else:  # negative control: lock in request order, one at a time
        for i in ids: cur.execute("SELECT id FROM accounts WHERE id=%s FOR UPDATE", (str(i),))
        cur.execute("SELECT id, balance_minor, type FROM accounts WHERE id = ANY(%s::uuid[])", ([str(debit), str(credit)],))
    rows = {str(r[0]): r for r in cur.fetchall()}
    d = rows[str(debit)]
    if d[2] == "CUSTOMER" and d[1] < amount: raise Insufficient()
    tx = uuid.uuid4()
    cur.execute("INSERT INTO ledger_transactions(id,reference,type,status,amount_minor,currency) VALUES (%s,%s,'TRANSFER','POSTED',%s,'NGN')",
                (tx, "TXN-" + tx.hex[:20], amount))
    cur.execute("UPDATE accounts SET balance_minor = balance_minor - %s WHERE id=%s RETURNING balance_minor", (amount, debit)); bd = cur.fetchone()[0]
    cur.execute("UPDATE accounts SET balance_minor = balance_minor + %s WHERE id=%s RETURNING balance_minor", (amount, credit)); bc = cur.fetchone()[0]
    cur.execute("INSERT INTO ledger_entries(id,transaction_id,account_id,direction,amount_minor,balance_after) VALUES (%s,%s,%s,'DEBIT',%s,%s)", (uuid.uuid4(), tx, debit, amount, bd))
    cur.execute("INSERT INTO ledger_entries(id,transaction_id,account_id,direction,amount_minor,balance_after) VALUES (%s,%s,%s,'CREDIT',%s,%s)", (uuid.uuid4(), tx, credit, amount, bc))

def run_tx(fn):
    """Runs fn(conn) in its own transaction. Returns 'ok' | 'insufficient' | 'deadlock' | other error text."""
    conn = connect()
    try:
        fn(conn); conn.commit(); return "ok"
    except Insufficient: conn.rollback(); return "insufficient"
    except errors.DeadlockDetected: conn.rollback(); return "deadlock"
    except Exception as e: conn.rollback(); return f"error:{type(e).__name__}:{e}"
    finally: conn.close()

def system_account(cur, currency="NGN"):
    cur.execute("SELECT id FROM accounts WHERE type='SYSTEM' AND currency=%s", (currency,)); return cur.fetchone()[0]

def fund(accounts, amount):
    conn = connect(); cur = conn.cursor(); sysid = system_account(cur)
    for a in accounts:
        post(conn, sysid, a, amount)
    conn.commit(); conn.close()

def reconcile():
    conn = connect(); cur = conn.cursor()
    cur.execute("""SELECT count(*) FROM (SELECT a.id FROM accounts a LEFT JOIN ledger_entries e ON e.account_id=a.id
                   GROUP BY a.id HAVING a.balance_minor <> COALESCE(SUM(CASE e.direction WHEN 'CREDIT' THEN e.amount_minor ELSE -e.amount_minor END),0)) x""")
    mism = cur.fetchone()[0]
    cur.execute("SELECT COALESCE(SUM(balance_minor),0) FROM accounts"); total = cur.fetchone()[0]
    cur.execute("""SELECT count(*) FROM (SELECT transaction_id FROM ledger_entries GROUP BY transaction_id
                   HAVING SUM(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0) x""")
    unbalanced = cur.fetchone()[0]
    cur.execute("SELECT count(*) FROM accounts WHERE type='CUSTOMER' AND balance_minor < 0"); neg = cur.fetchone()[0]
    conn.close(); return mism, total, unbalanced, neg

def test_schema_guards():
    print("\n[1] integrity guards")
    conn = connect(); cur = conn.cursor()
    a, b = new_user_account(cur), new_user_account(cur); conn.commit()
    fund([a], 1000)
    c = connect(); cu = c.cursor()
    try: cu.execute("UPDATE ledger_entries SET amount_minor = 1"); c.commit(); ok = False
    except errors.RestrictViolation: c.rollback(); ok = True
    check("UPDATE on ledger_entries is rejected", ok)
    try: cu.execute("DELETE FROM ledger_entries"); c.commit(); ok = False
    except errors.RestrictViolation: c.rollback(); ok = True
    check("DELETE on ledger_entries is rejected", ok)
    try: cu.execute("TRUNCATE ledger_entries"); c.commit(); ok = False
    except errors.RestrictViolation: c.rollback(); ok = True
    check("TRUNCATE on ledger_entries is rejected", ok)
    cu.execute("INSERT INTO audit_logs(id,action) VALUES (%s,'X')", (uuid.uuid4(),)); c.commit()
    try: cu.execute("DELETE FROM audit_logs"); c.commit(); ok = False
    except errors.RestrictViolation: c.rollback(); ok = True
    check("audit_logs is append-only", ok)
    # an unbalanced transaction cannot commit (deferred trigger)
    tx = uuid.uuid4()
    cu.execute("INSERT INTO ledger_transactions(id,reference,type,status,amount_minor,currency) VALUES (%s,'TXN-BAD','TRANSFER','POSTED',100,'NGN')", (tx,))
    cu.execute("INSERT INTO ledger_entries(id,transaction_id,account_id,direction,amount_minor,balance_after) VALUES (%s,%s,%s,'DEBIT',100,0)", (uuid.uuid4(), tx, a))
    try: c.commit(); ok = False
    except errors.IntegrityConstraintViolation: c.rollback(); ok = True
    check("one-sided (unbalanced) ledger transaction cannot commit", ok)
    tx = uuid.uuid4()
    cu.execute("INSERT INTO ledger_transactions(id,reference,type,status,amount_minor,currency) VALUES (%s,'TXN-BAD2','TRANSFER','POSTED',100,'NGN')", (tx,))
    cu.execute("INSERT INTO ledger_entries(id,transaction_id,account_id,direction,amount_minor,balance_after) VALUES (%s,%s,%s,'DEBIT',100,0)", (uuid.uuid4(), tx, a))
    cu.execute("INSERT INTO ledger_entries(id,transaction_id,account_id,direction,amount_minor,balance_after) VALUES (%s,%s,%s,'CREDIT',90,0)", (uuid.uuid4(), tx, b))
    try: c.commit(); ok = False
    except errors.IntegrityConstraintViolation: c.rollback(); ok = True
    check("debits != credits cannot commit", ok)
    try: cu.execute("UPDATE accounts SET balance_minor = -1 WHERE id=%s", (a,)); c.commit(); ok = False
    except errors.CheckViolation: c.rollback(); ok = True
    check("customer balance cannot go negative (CHECK)", ok)
    cu.execute("SELECT count(*) FROM accounts WHERE type='SYSTEM'"); check("4 system accounts seeded", cu.fetchone()[0] == 4)
    c.close(); conn.close()

def test_conservation():
    print("\n[2] 400 concurrent random transfers, 32 threads")
    conn = connect(); cur = conn.cursor(); accts = [new_user_account(cur) for _ in range(10)]; conn.commit(); conn.close()
    fund(accts, 100_000)
    def one(_):
        a, b = random.sample(accts, 2); amt = random.randint(1, 30_000)
        return run_tx(lambda c: post(c, a, b, amt))
    with ThreadPoolExecutor(32) as ex: res = list(ex.map(one, range(400)))
    ok = res.count("ok"); ins = res.count("insufficient"); other = [r for r in res if r not in ("ok", "insufficient")]
    print(f"        ok={ok} insufficient={ins} other={len(other)}")
    check("no deadlocks / unexpected errors", not other, str(other[:2]))
    c = connect(); cu = c.cursor()
    cu.execute("SELECT SUM(balance_minor) FROM accounts WHERE id = ANY(%s::uuid[])", ([str(x) for x in accts],))
    check("customer money conserved (1,000,000 in = 1,000,000 out)", cu.fetchone()[0] == 1_000_000); c.close()
    mism, total, unb, neg = reconcile()
    check("reconciliation: balances == ledger sums", mism == 0)
    check("sum of ALL balances (incl. system) is zero", total == 0)
    check("no unbalanced ledger transactions", unb == 0)
    check("no negative customer balances", neg == 0)

def test_opposing(ordered):
    conn = connect(); cur = conn.cursor(); a, b = new_user_account(cur), new_user_account(cur); conn.commit(); conn.close()
    fund([a, b], 10_000_000)
    def one(i):
        x, y = (a, b) if i % 2 == 0 else (b, a)
        return run_tx(lambda c: post(c, x, y, 10, ordered=ordered))
    with ThreadPoolExecutor(32) as ex: res = list(ex.map(one, range(400)))
    return res

def test_deadlocks():
    print("\n[3] opposing transfers A<->B (400 concurrent)")
    res = test_opposing(True)
    check("ordered locking: 400/400 succeed, zero deadlocks", res.count("ok") == 400, f"{res.count('deadlock')} deadlocks")
    res = test_opposing(False)
    print(f"        negative control (unordered locking): ok={res.count('ok')} deadlocks={res.count('deadlock')}")
    check("negative control: unordered locking DOES deadlock (proves the ordering matters)", res.count("deadlock") > 0)

def test_hot_account():
    print("\n[4] hot account: 50 concurrent debits of 10,000 against a balance of 100,000")
    conn = connect(); cur = conn.cursor(); a, b = new_user_account(cur), new_user_account(cur); conn.commit(); conn.close()
    fund([a], 100_000)
    with ThreadPoolExecutor(50) as ex: res = list(ex.map(lambda _: run_tx(lambda c: post(c, a, b, 10_000)), range(50)))
    check("exactly 10 succeed, 40 rejected for insufficient funds", res.count("ok") == 10 and res.count("insufficient") == 40, str((res.count("ok"), res.count("insufficient"))))
    c = connect(); cu = c.cursor(); cu.execute("SELECT balance_minor FROM accounts WHERE id=%s", (a,)); check("final balance exactly 0", cu.fetchone()[0] == 0); c.close()

def test_idempotency():
    print("\n[5] idempotency: 20 concurrent requests, same key")
    conn = connect(); cur = conn.cursor(); u = uuid.uuid4()
    cur.execute("INSERT INTO users(id,email,password_hash,full_name) VALUES (%s,%s,'x','T')", (u, f"{u}@t.io")); a, b = new_user_account(cur), new_user_account(cur); conn.commit(); conn.close()
    fund([a], 100_000)
    key, req_hash = "key-12345678", "h" * 64
    outcomes = []; lock = threading.Lock()
    def request(_):
        c = connect(); cu = c.cursor()
        try:
            cu.execute("""INSERT INTO idempotency_keys(user_id,idem_key,scope,request_hash,status) VALUES (%s,%s,'TRANSFER',%s,'IN_PROGRESS')
                          ON CONFLICT (user_id, idem_key) DO NOTHING""", (u, key, req_hash))
            if cu.rowcount == 1:                       # we own the key: do the work, store the response, commit together
                post(c, a, b, 5_000)
                cu.execute("UPDATE idempotency_keys SET status='COMPLETED', response_body='{\"ok\":true}', completed_at=now() WHERE user_id=%s AND idem_key=%s", (u, key))
                c.commit(); r = "executed"
            else:                                      # conflict: the INSERT already waited for the owner to commit/rollback
                cu.execute("SELECT status, request_hash FROM idempotency_keys WHERE user_id=%s AND idem_key=%s", (u, key)); st, h = cu.fetchone()
                c.commit(); r = "replayed" if st == "COMPLETED" and h == req_hash else f"bad:{st}"
        except Exception as e: c.rollback(); r = f"error:{e}"
        finally: c.close()
        with lock: outcomes.append(r)
    with ThreadPoolExecutor(20) as ex: list(ex.map(request, range(20)))
    check("exactly 1 execution, 19 replays", outcomes.count("executed") == 1 and outcomes.count("replayed") == 19, str({k: outcomes.count(k) for k in set(outcomes)}))
    c = connect(); cu = c.cursor(); cu.execute("SELECT balance_minor FROM accounts WHERE id=%s", (a,)); check("customer debited exactly once (100,000 - 5,000)", cu.fetchone()[0] == 95_000); c.close()
    # rollback leaves no key behind, so a failed attempt can be retried safely
    c = connect(); cu = c.cursor()
    cu.execute("INSERT INTO idempotency_keys(user_id,idem_key,scope,request_hash,status) VALUES (%s,'key-rollback1','TRANSFER',%s,'IN_PROGRESS')", (u, req_hash)); c.rollback()
    cu.execute("SELECT count(*) FROM idempotency_keys WHERE idem_key='key-rollback1'"); check("rolled-back attempt leaves no idempotency record", cu.fetchone()[0] == 0); c.close()

def test_outbox_claim():
    print("\n[6] outbox claim with SKIP LOCKED: 3 workers, 60 events")
    conn = connect(); cur = conn.cursor()
    for _ in range(60):
        cur.execute("INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload) VALUES (%s,'T',%s,'E','{}')", (uuid.uuid4(), uuid.uuid4()))
    conn.commit(); conn.close()
    claimed, lock = [], threading.Lock()
    def worker(_):
        while True:
            c = connect(); cu = c.cursor()
            cu.execute("""UPDATE outbox_events SET status='PROCESSING', locked_until = now() + interval '120 seconds' WHERE id IN (
                            SELECT id FROM outbox_events WHERE (status IN ('PENDING','FAILED') AND next_attempt_at <= now())
                               OR (status='PROCESSING' AND locked_until < now())
                            ORDER BY created_at LIMIT 7 FOR UPDATE SKIP LOCKED) RETURNING id""")
            ids = [r[0] for r in cu.fetchall()]; c.commit(); c.close()
            if not ids: return
            with lock: claimed.extend(ids)
    with ThreadPoolExecutor(3) as ex: list(ex.map(worker, range(3)))
    check("every event claimed exactly once across workers", len(claimed) == 60 and len(set(claimed)) == 60, f"{len(claimed)} claims, {len(set(claimed))} unique")
    # crash recovery: an expired lease makes an event claimable again
    c = connect(); cu = c.cursor(); cu.execute("UPDATE outbox_events SET locked_until = now() - interval '1 second' WHERE id = %s", (claimed[0],)); c.commit()
    cu.execute("""UPDATE outbox_events SET locked_until = now() + interval '120 seconds' WHERE id IN (SELECT id FROM outbox_events WHERE status='PROCESSING' AND locked_until < now() FOR UPDATE SKIP LOCKED) RETURNING id""")
    check("event with an expired lease is reclaimed after a worker crash", [r[0] for r in cu.fetchall()] == [claimed[0]]); c.commit(); c.close()

if __name__ == "__main__":
    setup()
    for t in (test_schema_guards, test_conservation, test_deadlocks, test_hot_account, test_idempotency, test_outbox_claim): t()
    print(f"\n{passed} passed, {failed} failed"); sys.exit(1 if failed else 0)
