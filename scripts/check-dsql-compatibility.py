"""Run SQL compatibility checks against a disposable Aurora DSQL cluster."""

import argparse
from contextlib import contextmanager
import os
from pathlib import Path
from queue import Empty, Queue
import subprocess
from threading import Thread
from uuid import uuid4


@contextmanager
def session(host, env):
    with subprocess.Popen(
        ["psql", "-XqAtw", "-h", host, "-U", "admin", "-d", "postgres"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        env=env,
    ) as client:
        output = Queue()

        def read_output():
            for line in client.stdout:
                output.put(line.rstrip())
            output.put(None)

        Thread(target=read_output, daemon=True).start()

        def run(sql, state="00000", timeout=20):
            client.stdin.write(sql.rstrip(";") + ";\n\\echo sqlstate :SQLSTATE\n")
            client.stdin.flush()
            lines = []
            while True:
                try:
                    line = output.get(timeout=timeout)
                except Empty as error:
                    raise TimeoutError(f"Timed out waiting for {sql}") from error
                if line is None:
                    raise RuntimeError("\n".join(lines))
                if line.startswith("sqlstate "):
                    if line != f"sqlstate {state}":
                        raise RuntimeError(f"{sql}\n{line} (expected {state})\n" + "\n".join(lines))
                    return lines
                lines.append(line)

        try:
            yield run
        finally:
            client.terminate()


def expect(run, sql, *rows, timeout=20):
    actual = [line for line in run(sql, timeout=timeout) if not line.startswith("NOTICE:")]
    if actual != list(rows):
        raise RuntimeError(f"{sql}\nExpected {rows}, got {actual}")


def expect_conflict(run):
    diagnostics = run("COMMIT", "40001")
    if not any("OC000" in line for line in diagnostics):
        raise RuntimeError(f"Expected a data conflict (OC000), got {diagnostics}")


def check(host, env):
    folder = Path(__file__).resolve().parents[1] / "src/main/resources/db/migration"
    schema = "daily_brief_check_" + uuid4().hex
    with session(host, env) as a, session(host, env) as b:
        a(f"CREATE SCHEMA {schema}")
        a(f"SET search_path TO {schema}")

        for path in sorted(folder.glob("V*.sql")):
            for statement in path.read_text().split(";"):
                sql = statement.strip()
                if sql.startswith("CREATE INDEX "):
                    sql = sql.replace("CREATE INDEX ", "CREATE INDEX ASYNC ", 1)
                    job_id, = a(sql)
                    expect(a, f"CALL sys.wait_for_job('{job_id}')", "t", timeout=120)
                    print(f"PASS: {sql}", flush=True)
                elif sql:
                    if "AS IDENTITY PRIMARY KEY" in sql:
                        a(sql, "0A000")
                        sql = sql.replace("AS IDENTITY PRIMARY KEY", "AS IDENTITY (CACHE 65536) PRIMARY KEY")
                    a(sql)
        expect(a, "INSERT INTO brief_subscriber(email,status) VALUES ('test@example.invalid','ACTIVE') "
               "RETURNING (id > 0)", "t")
        a("INSERT INTO brief_project_selection VALUES (-1,'REACT',now())", "23503")
        print("PASS: V2–V5 DDL with explicit identity cache and async indexes; orphan rejected", flush=True)

        a("CREATE TABLE cutoff_claim (id integer PRIMARY KEY, owner integer)")
        a("INSERT INTO cutoff_claim VALUES (1,NULL)")
        b(f"SET search_path TO {schema}")
        a("INSERT INTO brief_management_session SELECT 'first',id,0,'csrf',"
          "'2026-01-01 00:15:00+00' FROM brief_subscriber")
        touch = ("UPDATE brief_management_session SET expires_at=GREATEST(expires_at,"
                 "'2026-01-01 00:29:00+00') WHERE digest='first'"
                 " AND expires_at>'2026-01-01 00:14:00+00' AND EXISTS ("
                 " SELECT 1 FROM brief_subscriber s WHERE s.id=brief_management_session.subscriber_id"
                 " AND s.status='ACTIVE' AND s.generation=brief_management_session.generation)"
                 " RETURNING generation")
        expect(b, "SELECT csrf_token FROM brief_management_session WHERE digest='first'", "csrf")
        a("BEGIN")
        b("BEGIN")
        expect(b, touch, "0")
        a("DELETE FROM brief_management_session WHERE digest='first'")
        a("INSERT INTO brief_management_session SELECT 'second',id,0,'new-csrf',"
          "'2026-01-01 00:30:00+00' FROM brief_subscriber")
        a("COMMIT")
        expect_conflict(b)
        expect(b, touch)
        expect(b, "DELETE FROM brief_management_session WHERE digest IN"
               " (SELECT digest FROM brief_management_session WHERE expires_at<="
               "'2026-01-01 00:30:00+00' ORDER BY expires_at LIMIT 100) RETURNING digest", "second")
        print("PASS: shared management session, replacement conflict, and bounded expiration cleanup", flush=True)

        claim = "UPDATE cutoff_claim SET owner={} WHERE id=1 AND owner IS NULL RETURNING owner"
        a("BEGIN")
        b("BEGIN")
        expect(a, claim.format(10), "10")
        expect(b, claim.format(20), "20")
        a("COMMIT")
        expect_conflict(b)
        expect(b, claim.format(20))
        expect(b, "SELECT owner FROM cutoff_claim WHERE id=1", "10")
        print("PASS: one committed cutoff claim, and retry returned no claim", flush=True)

        a("INSERT INTO brief_delivery "
          "(id,subscriber_id,cutoff,subscriber_generation,kind,recipient,sender,subject,text_body,html_body,state) "
          "SELECT 'a593c2cb-faf7-4abd-a811-fb11d179fd17',id,now(),0,'NEWS',email,"
          "'sender@example.invalid','test','test','test','UNKNOWN' FROM brief_subscriber")
        delivery = "id='a593c2cb-faf7-4abd-a811-fb11d179fd17'"
        read = f"SELECT state FROM brief_delivery WHERE {delivery} FOR UPDATE"
        accept = "UPDATE brief_delivery SET state='SENT',provider_id='ses-test',owner=owner+1,lease_until=NULL"
        accept += f" WHERE {delivery} AND state IN ('UNKNOWN','HELD') RETURNING owner"
        a("BEGIN")
        b("BEGIN")
        expect(a, read, "UNKNOWN")
        expect(b, read, "UNKNOWN")
        expect(a, accept, "1")
        expect(b, accept, "1")
        a("COMMIT")
        expect_conflict(b)
        expect(b, accept)
        expect(b, f"UPDATE brief_delivery SET state='REJECTED' WHERE {delivery} "
               "AND owner=0 AND lease_until>now() RETURNING owner")
        expect(b, f"SELECT state,provider_id,owner FROM brief_delivery WHERE {delivery}", "SENT|ses-test|1")
        print("PASS: duplicate acceptance committed once and retry was a no-op", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cluster", help="disposable DSQL cluster identifier")
    parser.add_argument("--region", default="ap-northeast-2")
    parser.add_argument("--ca", default="system", help="root certificate file or 'system' (libpq 17+)")
    args = parser.parse_args()
    host = f"{args.cluster}.dsql.{args.region}.on.aws"
    token = subprocess.check_output(
        ["aws", "dsql", "generate-db-connect-admin-auth-token", "--region", args.region,
         "--hostname", host],
        text=True,
        timeout=20,
    ).strip()
    check(host, dict(os.environ, PGPASSWORD=token, PGSSLMODE="verify-full",
                     PGSSLROOTCERT=args.ca, PGCONNECT_TIMEOUT="10", PGPORT="5432"))
