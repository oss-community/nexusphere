import dataclasses
import re
import unittest

from nexusphere_ledger.keys import PrivateKey
from nexusphere_ledger.mandate import Jws, StaticKeys
from nexusphere_ledger.reconcile import (INBOUND, MISMATCH, NOT_RECEIVED, NOT_SENT, OUTBOUND, STATEMENT_TYPE,
                                         Exchange, ExchangeStatement, parse_statement, reconcile, verify_statement)

OURS = "https://ledger.example"
THEIRS = "https://supplier.example"
FROM = 1790841600
TO = FROM + 86400
MANDATE = "00000000-0000-4000-8000-0000000000aa"
OUR_KEY = PrivateKey.generate()
THEIR_KEY = PrivateKey.generate()


def exchange(direction, n, at=FROM + 10, **changes) -> Exchange:
    item = Exchange(direction, "00000000-0000-4000-8000-%012d" % n, at, "message/send", MANDATE, "aa" * 32,
                    "bb" * 32, 200, "SUCCEEDED", "cc" * 32)
    return dataclasses.replace(item, **changes)


def statement(issuer, peer, *exchanges, start=FROM, end=TO) -> ExchangeStatement:
    return ExchangeStatement(issuer, peer, start, end, end + 60, list(exchanges))


def ours(*exchanges, **window) -> ExchangeStatement:
    return statement(OURS, THEIRS, *exchanges, **window)


def theirs(*exchanges, **window) -> ExchangeStatement:
    return statement(THEIRS, OURS, *exchanges, **window)


class Statements(unittest.TestCase):

    def test_signed_and_verified(self):
        signed = ours(exchange(OUTBOUND, 2, FROM + 20), exchange(INBOUND, 1, FROM + 20), exchange(OUTBOUND, 3))
        token = signed.sign(OUR_KEY.key_id, OUR_KEY)
        verified = verify_statement(token, StaticKeys.fixed(OURS, OUR_KEY.public_key))
        self.assertEqual(signed, verified)
        self.assertEqual(signed, parse_statement(token))
        self.assertEqual(["00000000-0000-4000-8000-000000000003", "00000000-0000-4000-8000-000000000001",
                          "00000000-0000-4000-8000-000000000002"], [e.request_id for e in verified.exchanges])
        with self.assertRaisesRegex(ValueError, "unknown key"):
            verify_statement(token, StaticKeys.fixed(OURS, THEIR_KEY.public_key))
        with self.assertRaisesRegex(ValueError, "not issued by " + THEIRS):
            verify_statement(token, StaticKeys.fixed(THEIRS, OUR_KEY.public_key), THEIRS)

    def test_other_tokens_are_refused(self):
        token = ours().sign(OUR_KEY.key_id, OUR_KEY)
        header, payload, signature = token.split(".")
        forged = header + "." + payload + "." + ("A" if signature[0] != "A" else "B") + signature[1:]
        with self.assertRaisesRegex(ValueError, "does not match"):
            verify_statement(forged, StaticKeys.fixed(OURS, OUR_KEY.public_key))
        other = Jws.sign_typed("JWT", OUR_KEY.key_id, ours().to_payload(), OUR_KEY)
        with self.assertRaisesRegex(ValueError, re.escape("not an EdDSA " + STATEMENT_TYPE)):
            verify_statement(other, StaticKeys.fixed(OURS, OUR_KEY.public_key))

    def test_malformed_statements(self):
        with self.assertRaisesRegex(ValueError, "end after it starts"):
            ours(start=TO, end=TO)
        with self.assertRaisesRegex(ValueError, "OUTBOUND or INBOUND"):
            exchange("SIDEWAYS", 1)
        with self.assertRaisesRegex(ValueError, "malformed hash"):
            exchange(OUTBOUND, 1, response_hash="xyz")
        with self.assertRaisesRegex(ValueError, "no exchanges"):
            ExchangeStatement.from_payload({"iss": OURS, "peer": THEIRS, "iat": TO, "from": FROM, "to": TO})


class Reconciliations(unittest.TestCase):

    def test_matched_both_ways(self):
        r = reconcile(ours(exchange(OUTBOUND, 1), exchange(INBOUND, 2)),
                      theirs(exchange(INBOUND, 1), exchange(OUTBOUND, 2)))
        self.assertTrue(r.reconciled)
        self.assertEqual((2, 0, []), (r.matched, r.undelivered, r.discrepancies))
        self.assertEqual({"ours": OURS, "theirs": THEIRS, "from": "2026-10-01T08:00:00Z",
                          "to": "2026-10-02T08:00:00Z", "matched": 2, "undelivered": 0, "discrepancies": []},
                         r.to_dict())

    def test_not_received(self):
        r = reconcile(ours(exchange(OUTBOUND, 1), exchange(OUTBOUND, 2, status=401, receipt_hash=None),
                           exchange(OUTBOUND, 3, status=503), exchange(OUTBOUND, 4, status=None, receipt_hash=None)),
                      theirs())
        self.assertEqual(1, r.undelivered)
        self.assertEqual([("00000000-0000-4000-8000-000000000001", NOT_RECEIVED, OURS),
                          ("00000000-0000-4000-8000-000000000003", NOT_RECEIVED, OURS),
                          ("00000000-0000-4000-8000-000000000004", NOT_RECEIVED, OURS)],
                         [(d.request_id, d.kind, d.sender) for d in r.discrepancies])

    def test_not_sent(self):
        r = reconcile(ours(exchange(INBOUND, 1, FROM + 30)), theirs(exchange(INBOUND, 2, FROM + 20)))
        self.assertFalse(r.reconciled)
        self.assertEqual([{"requestId": "00000000-0000-4000-8000-000000000002", "kind": NOT_SENT, "sender": OURS,
                           "requestedAt": "2026-10-01T08:00:20Z", "fields": []},
                          {"requestId": "00000000-0000-4000-8000-000000000001", "kind": NOT_SENT, "sender": THEIRS,
                           "requestedAt": "2026-10-01T08:00:30Z", "fields": []}],
                         [d.to_dict() for d in r.discrepancies])

    def test_mismatch(self):
        r = reconcile(ours(exchange(OUTBOUND, 1), exchange(OUTBOUND, 2), exchange(OUTBOUND, 3),
                           exchange(OUTBOUND, 4, status=None, response_hash=None, receipt_hash=None)),
                      theirs(exchange(INBOUND, 1, FROM + 11, method="tasks/get", mandate_id=MANDATE[:-2] + "bb",
                                      request_hash="dd" * 32, status=500, response_hash="ee" * 32,
                                      receipt_hash="ff" * 32, outcome="FAILED"),
                             exchange(INBOUND, 2, outcome="FAILED"),
                             exchange(INBOUND, 3, outcome="FAILED", receipt_hash=None),
                             exchange(INBOUND, 4, status=500, response_hash="ee" * 32)))
        self.assertEqual(2, r.matched)
        self.assertEqual([MISMATCH, MISMATCH], [d.kind for d in r.discrepancies])
        self.assertEqual(["requestedAt", "method", "mandateId", "requestHash", "status", "responseHash",
                          "receiptHash"], r.discrepancies[0].fields)
        self.assertEqual(["outcome"], r.discrepancies[1].fields)

    def test_discrepancies_sort_like_uuids(self):
        late = exchange(INBOUND, 2, request_id="80000000-0000-4000-8000-000000000002")
        r = reconcile(ours(exchange(OUTBOUND, 1)), theirs(late))
        self.assertEqual(["80000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000001"],
                         [d.request_id for d in r.discrepancies])

    def test_statements_about_others(self):
        with self.assertRaises(ValueError) as error:
            reconcile(ours(), statement(THEIRS, "https://other.example"))
        self.assertEqual("The statements are not about each other: https://ledger.example names "
                         "https://supplier.example and https://supplier.example names https://other.example",
                         str(error.exception))

    def test_different_windows(self):
        with self.assertRaises(ValueError) as error:
            reconcile(ours(), theirs(end=TO + 1))
        self.assertEqual("The statements cover different windows", str(error.exception))


if __name__ == "__main__":
    unittest.main()
