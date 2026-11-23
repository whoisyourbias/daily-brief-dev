"""Run SQL compatibility checks against a disposable Aurora DSQL cluster."""

import argparse
from uuid import uuid4

from dsql_schema import add_connection_arguments, connect_config, expect, migrate, session


def expect_conflict(run):
    diagnostics = run("COMMIT", "40001")
    if not any("OC000" in line for line in diagnostics):
        raise RuntimeError(f"Expected a data conflict (OC000), got {diagnostics}")


def interrupt_after_index_submission(run, schema):
    class Interruption(Exception):
        pass

    def interrupted_run(sql, *args, **kwargs):
        if sql.startswith("CALL sys.wait_for_job("):
            raise Interruption()
        return run(sql, *args, **kwargs)

    try:
        migrate(interrupted_run, schema)
    except Interruption:
        return
    raise RuntimeError("Expected migration to submit an asynchronous index job")


def check(host, env):
    schema = "daily_brief_check_" + uuid4().hex
    with session(host, env) as first_attempt:
        interrupt_after_index_submission(first_attempt, schema)

    with session(host, env) as a, session(host, env) as b:
        migrate(a, schema)
        expect(a, "INSERT INTO brief_subscriber(email,status) "
               "VALUES ('test@example.invalid','ACTIVE'), ('other@example.invalid','ACTIVE') "
               "RETURNING (id > 0)", "t", "t")
        migrate(a, schema)
        expect(a, "SELECT count(DISTINCT id) FROM brief_subscriber", "2")
        a("DELETE FROM brief_subscriber WHERE email='other@example.invalid'")
        a("INSERT INTO brief_project_selection VALUES (-1,'REACT',now())", "23503")
        a("INSERT INTO brief_news VALUES (now(), 1, 'test', 'test', 'test')", "23503")
        a("INSERT INTO brief_delivery_release VALUES "
          "('a593c2cb-faf7-4abd-a811-fb11d179fd17', -1, -1, 'REACT', now())", "23503")
        expect(a, "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() "
               "AND indexname IN ('brief_release_published_idx', 'brief_delivery_retry_idx') "
               "ORDER BY indexname", "brief_delivery_retry_idx", "brief_release_published_idx")
        print("PASS: V2–V4 resumed after interruption and reapplied, generated IDs preserved, "
              "foreign keys enforced and both indexes present", flush=True)

        a("CREATE TABLE cutoff_claim (id integer PRIMARY KEY, owner integer)")
        a("INSERT INTO cutoff_claim VALUES (1,NULL)")
        b(f"SET search_path TO {schema}")
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
    add_connection_arguments(parser)
    check(*connect_config(parser.parse_args()))
