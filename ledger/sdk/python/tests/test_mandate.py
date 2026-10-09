import unittest

from nexusphere_ledger.keys import PrivateKey
from nexusphere_ledger.mandate import (BAD_SIGNATURE, EXPIRED, MALFORMED, NOT_COVERED, NOT_YET_VALID, REVOKED,
                                       STATUS_UNAVAILABLE, TYPE, UNKNOWN_KEY, UNTRUSTED_ISSUER, VCT, WRONG_AUDIENCE,
                                       Disclosure, JwksKeys, MandateVerifier, SdJwt, StaticKeys, StatusList, jwk_of)

from .support import ISSUER, LEDGER_KEY, vector

NOW = 1790841600 + 3600
STATUS = ISSUER + "/public/v1/mandates/status"


def issue(key=LEDGER_KEY, issuer=ISSUER, index=3, exp=1822377600, nbf=1790841600, status=STATUS):
    disclosures = [Disclosure.of("principal", "globex"),
                   Disclosure.of("grant", "00000000-0000-4000-8000-0000000000bb")]
    payload = {"iss": issuer, "vct": VCT, "sub": "sales-agent", "aud": "https://supplier.example",
               "jti": "00000000-0000-4000-8000-0000000000aa", "iat": nbf, "nbf": nbf, "exp": exp,
               "mandate": {"actions": ["a2a/send"], "targets": ["supplier/*"], "maxUses": 5,
                           "_sd": sorted(d.digest for d in disclosures)},
               "status": {"status_list": {"idx": index, "uri": status}}, "_sd_alg": "sha-256"}
    return SdJwt.issue({"alg": "EdDSA", "typ": TYPE, "kid": key.key_id}, payload, disclosures, key)


class FakeIssuer:

    def __init__(self, revoked=(), key=LEDGER_KEY, exp=NOW + 600):
        self.key = key
        self.revoked = revoked
        self.exp = exp
        self.calls = []

    def __call__(self, url, accept):
        self.calls.append(url)
        if url == ISSUER + "/public/v1/keys":
            import json
            return json.dumps({"keys": [jwk_of(self.key.public_key)]})
        if url == STATUS:
            return StatusList.of(ISSUER, STATUS, NOW - 60, self.exp, 16, self.revoked).sign(self.key.key_id, self.key)
        raise IOError("404 " + url)


class MandateVerification(unittest.TestCase):

    def verifier(self, fetch=None, **options):
        fetch = fetch or FakeIssuer()
        return MandateVerifier(ISSUER, fetch=fetch, clock=lambda: NOW, **options)

    def test_valid_with_jwks_and_status_list(self):
        fetch = FakeIssuer(revoked=[2, 4])
        verifier = self.verifier(fetch, audience="https://supplier.example")
        check = verifier.verify_action(issue(), "a2a/send", "supplier/sales")
        self.assertTrue(check.valid, check.problems)
        self.assertEqual("globex", check.claims.principal_id)
        verifier.verify(issue())
        self.assertEqual(2, len(fetch.calls))

    def test_revoked(self):
        check = self.verifier(FakeIssuer(revoked=[3])).verify(issue())
        self.assertTrue(check.has(REVOKED))
        self.assertTrue(self.verifier().verify(issue(index=99)).has(REVOKED))

    def test_status_list_problems(self):
        self.assertTrue(self.verifier(FakeIssuer(exp=NOW - 1)).verify(issue()).has(STATUS_UNAVAILABLE))
        check = self.verifier().verify(issue(status="https://elsewhere.example/status"))
        self.assertTrue(check.has(STATUS_UNAVAILABLE))

    def test_time_audience_and_coverage(self):
        verifier = self.verifier(skip_status=True, audience="https://other.example")
        check = verifier.verify_action(issue(exp=NOW - 120, nbf=NOW - 7200), "a2a/send", "buyer/orders")
        self.assertEqual({EXPIRED, WRONG_AUDIENCE, NOT_COVERED}, {p.code for p in check.problems})
        self.assertTrue(self.verifier(skip_status=True).verify(issue(nbf=NOW + 120)).has(NOT_YET_VALID))
        self.assertTrue(self.verifier(skip_status=True).verify(issue(exp=NOW - 30, nbf=NOW - 7200)).valid)

    def test_issuer_key_and_signature(self):
        other = PrivateKey.generate()
        self.assertTrue(self.verifier().verify(issue(issuer="https://evil.example")).has(UNTRUSTED_ISSUER))
        self.assertTrue(self.verifier().verify(issue(key=other)).has(UNKNOWN_KEY))
        token = issue()
        head, payload, signature = token.split("~")[0].split(".")
        forged = ".".join([head, payload, signature[:-4] + ("AAAA" if not signature.endswith("AAAA") else "BBBB")])
        self.assertTrue(self.verifier().verify(forged + token[len(token.split("~")[0]):]).has(BAD_SIGNATURE))
        fixed = MandateVerifier(ISSUER, keys=StaticKeys.fixed(ISSUER, other.public_key), skip_status=True,
                                clock=lambda: NOW)
        self.assertTrue(fixed.verify(issue(key=other)).valid)

    def test_malformed(self):
        self.assertTrue(self.verifier().verify("not-a-token").has(MALFORMED))
        token = issue()
        self.assertTrue(self.verifier().verify(token + token.split("~")[1] + "~").has(MALFORMED))
        foreign = Disclosure.of("principal", "initech").encoded
        self.assertTrue(self.verifier().verify(token + foreign + "~").has(MALFORMED))

    def test_selective_presentation(self):
        presented = SdJwt.parse(issue()).present({"grant"})
        check = self.verifier(skip_status=True).verify(presented)
        self.assertTrue(check.valid, check.problems)
        self.assertIsNone(check.claims.principal_id)
        self.assertEqual("00000000-0000-4000-8000-0000000000bb", check.claims.grant_id)

    def test_mandate_vector_with_jwks(self):
        token = vector("mandate.json")["token"]
        keys = JwksKeys(FakeIssuer(), clock=lambda: NOW)
        check = MandateVerifier(ISSUER, keys=keys, skip_status=True, clock=lambda: NOW).verify(token)
        self.assertTrue(check.valid, check.problems)


if __name__ == "__main__":
    unittest.main()
