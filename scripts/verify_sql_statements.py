#!/usr/bin/env python3
"""
Extracts every SQL string literal from the Java sources and PREPAREs it against the migrated schema on a real PostgreSQL.
This validates syntax, table/column names and parameter typing for all hand-written SQL without needing to run the app.
Usage: PGHOST=localhost PGUSER=ledgercore PGPASSWORD=... python3 scripts/verify_sql_statements.py   (run verify_db_invariants.py first)
"""
import glob, os, re, sys
import psycopg2

DB = os.getenv("VERIFY_DB", "ledgercore_verify")
conn = psycopg2.connect(host=os.getenv("PGHOST", "localhost"), port=os.getenv("PGPORT", "5432"),
                        user=os.getenv("PGUSER", "ledgercore"), password=os.getenv("PGPASSWORD", "ledgercore"), dbname=DB)
conn.autocommit = True
cur = conn.cursor()
SQL_START = re.compile(r'^\s*(SELECT|INSERT|UPDATE|DELETE|WITH)\b', re.I)
LIT = r'"((?:[^"\\]|\\.)*)"'
CHAIN = re.compile(LIT + r'(?:\s*\+\s*' + LIT + r')*', re.S)

def literals_in(src):
    for m in CHAIN.finditer(src):
        parts = re.findall(LIT, m.group(0), re.S)
        yield ''.join(p.encode().decode('unicode_escape') for p in parts)

ok = bad = skipped = 0
for f in sorted(glob.glob('src/main/java/**/*.java', recursive=True)):
    src = open(f).read()
    for sql in literals_in(src):
        if not SQL_START.match(sql) or ' ' not in sql.strip(): continue
        # skip JPQL (named :params, entity aliases) and fragments that are completed at runtime
        if re.search(r'(?<!:):[a-z]\w*', sql) or sql.rstrip().endswith(('WHERE', '(')) or 'WHERE " + ' in sql:
            skipped += 1; continue
        n = [0]
        def ph(_):
            n[0] += 1; return f'${n[0]}'
        stmt = re.sub(r'\?', ph, sql)
        name = f'p{ok + bad}'
        try:
            cur.execute(f'PREPARE {name} AS {stmt}'); cur.execute(f'DEALLOCATE {name}'); ok += 1
        except Exception as e:
            bad += 1; print(f'FAIL {os.path.basename(f)}: {str(e).strip().splitlines()[0]}\n     {re.sub(chr(10), " ", sql)[:170]}')
print(f'\n{ok} SQL statements prepared OK, {bad} failed, {skipped} dynamic statements skipped')
sys.exit(1 if bad else 0)
