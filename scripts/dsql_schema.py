"""Initialize the Daily Brief V2–V4 application schema on Aurora DSQL."""

import argparse
from contextlib import contextmanager
import os
from pathlib import Path
from queue import Empty, Queue
import re
import subprocess
import sys
from threading import Thread


MIGRATIONS = Path(__file__).resolve().parents[1] / "src/main/resources/db/migration"
VERSIONS = ("V2__subscriptions.sql", "V3__collection.sql", "V4__delivery.sql")
INDEX_WAIT_TIMEOUT = 120


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


def schema_name(value):
    if (not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", value)
            or value.startswith("pg_") or value in {"public", "sys", "information_schema"}):
        raise ValueError("Use a dedicated schema name starting with a lowercase letter, up to 63 letters, digits or underscores")
    return value


def migration_statements():
    # V2–V4 contain only plain CREATE statements. Reject SQL that needs a real parser.
    statements = []
    for filename in VERSIONS:
        source = (MIGRATIONS / filename).read_text(encoding="utf-8")
        unquoted = re.sub(r"'[^';\\]*'", "", source)
        if re.search(r"""['"$\\]|--|/\*""", unquoted):
            raise ValueError(f"{filename} contains quoted SQL or comments. Review the DSQL conversion.")
        for statement in source.split(";"):
            sql = statement.strip()
            if not sql:
                continue
            if sql.startswith("CREATE TABLE brief_"):
                sql = sql.replace("CREATE TABLE ", "CREATE TABLE IF NOT EXISTS ", 1)
                sql = sql.replace("AS IDENTITY PRIMARY KEY", "AS IDENTITY (CACHE 65536) PRIMARY KEY")
                statements.append((None, sql))
            else:
                match = re.match(r"CREATE INDEX (brief_\w+) ON (brief_\w+)\(", sql)
                if not match:
                    raise ValueError(f"Unsupported DSQL migration statement in {filename}: {sql}")
                statements.append((match.groups(), sql.replace("CREATE INDEX ", "CREATE INDEX ASYNC ", 1)))
    return statements


def build_index(run, schema, index, table, sql):
    # Match jobs by catalog OID so identically named indexes in other schemas cannot interfere.
    catalog = (
        "SELECT i.indisvalid, t.relname, coalesce(j.job_id, '') "
        "FROM pg_catalog.pg_class c "
        "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
        "JOIN pg_catalog.pg_index i ON i.indexrelid = c.oid "
        "JOIN pg_catalog.pg_class t ON t.oid = i.indrelid "
        "LEFT JOIN sys.jobs j ON j.object_id = c.oid AND j.job_type = 'INDEX_BUILD' "
        "AND j.status IN ('submitted', 'processing') "
        f"WHERE n.nspname = '{schema}' AND c.relname = '{index}'"
    )
    validity = ("SELECT indisvalid FROM pg_catalog.pg_index "
                f"WHERE indexrelid = '{schema}.{index}'::regclass")
    rows = run(catalog)
    if rows:
        row, = rows
        valid, actual_table, job_id = row.split("|")
        if actual_table != table:
            raise RuntimeError(f"{schema}.{index} belongs to {actual_table}, expected {table}")
        if valid == "t":
            return
        if not job_id:
            # The job can finish between the catalog and jobs reads. Recheck validity.
            if run(validity) == ["t"]:
                return
            raise RuntimeError(f"{schema}.{index} is invalid with no active build. "
                               "See the documented index recovery procedure.")
    else:
        job_id, = run(sql)
        print(f"Submitted {schema}.{index} as {job_id}", flush=True)

    if not re.fullmatch(r"[a-z0-9]+", job_id):
        raise RuntimeError(f"Unexpected DSQL job ID for {schema}.{index}: {job_id!r}")
    expect(run, f"CALL sys.wait_for_job('{job_id}')", "t", timeout=INDEX_WAIT_TIMEOUT)
    expect(run, validity, "t")
    print(f"Verified {schema}.{index}", flush=True)


def migrate(run, schema):
    schema = schema_name(schema)
    statements = migration_statements()
    # psql autocommits each command sent by run().
    run(f'CREATE SCHEMA IF NOT EXISTS "{schema}"')
    run(f'SET search_path TO "{schema}"')
    for index_target, sql in statements:
        if index_target:
            index, table = index_target
            build_index(run, schema, index, table, sql)
        else:
            run(sql)
    print(f"Initialized V2–V4 in {schema}", flush=True)


def add_connection_arguments(parser):
    parser.add_argument("cluster", help="Aurora DSQL cluster identifier")
    parser.add_argument("--region", default="ap-northeast-2")
    parser.add_argument("--ca", default="system", help="root certificate file or 'system' (libpq 17+)")


def connect_config(args):
    host = f"{args.cluster}.dsql.{args.region}.on.aws"
    token = subprocess.check_output(
        ["aws", "dsql", "generate-db-connect-admin-auth-token", "--region", args.region,
         "--hostname", host],
        text=True,
        timeout=20,
    ).strip()
    return host, dict(os.environ, PGPASSWORD=token, PGSSLMODE="verify-full",
                      PGSSLROOTCERT=args.ca, PGCONNECT_TIMEOUT="10", PGPORT="5432")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    add_connection_arguments(parser)
    parser.add_argument("--schema", required=True, help="dedicated Daily Brief application schema")
    args = parser.parse_args()
    try:
        schema_name(args.schema)
        with session(*connect_config(args)) as run:
            migrate(run, args.schema)
    except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as error:
        print(f"DSQL migration failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
