import unittest

from nexusphere_ledger.httpsig import (InvalidHttpSignature, authority, content_digest, sign_request,
                                       signature_base, verify_request)
from nexusphere_ledger.keys import PrivateKey

from .support import LEDGER_KEY, vector

URL = "https://ledger.supplier.test/a2a/in/sales"
BODY = b'{"jsonrpc":"2.0"}'
NOW = 1790841600
KEY = PrivateKey.generate()


def keys(key_id):
    return KEY.public_key if key_id == KEY.key_id else None


class HttpSignatureTest(unittest.TestCase):

    def test_signed_request_verifies(self):
        headers = sign_request("POST", URL, BODY, KEY.key_id, KEY, NOW, "n-1")
        verified = verify_request("POST", URL, headers, BODY, keys, NOW + 30)
        self.assertEqual(KEY.key_id, verified.key_id)
        self.assertEqual("n-1", verified.nonce)
        self.assertEqual(content_digest(BODY), headers["Content-Digest"])

    def test_request_without_body(self):
        headers = sign_request("DELETE", URL, None, KEY.key_id, KEY, NOW)
        self.assertNotIn("Content-Digest", headers)
        self.assertEqual(KEY.key_id, verify_request("DELETE", URL, headers, None, keys, NOW).key_id)

    def test_changes_are_rejected(self):
        headers = sign_request("POST", URL, BODY, KEY.key_id, KEY, NOW)
        cases = [("POST", URL, b"{}"), ("PUT", URL, BODY), ("POST", "https://evil.test/a2a/in/sales", BODY),
                 ("POST", "https://ledger.supplier.test/a2a/in/billing", BODY)]
        for method, url, body in cases:
            with self.assertRaises(InvalidHttpSignature):
                verify_request(method, url, headers, body, keys, NOW)

    def test_stale_unknown_or_missing(self):
        headers = sign_request("POST", URL, BODY, KEY.key_id, KEY, NOW)
        with self.assertRaises(InvalidHttpSignature):
            verify_request("POST", URL, headers, BODY, keys, NOW + 600)
        with self.assertRaises(InvalidHttpSignature):
            verify_request("POST", URL, headers, BODY, lambda key_id: None, NOW)
        with self.assertRaises(InvalidHttpSignature):
            verify_request("POST", URL, {}, BODY, keys, NOW)
        stripped = dict(headers, **{"Signature-Input": headers["Signature-Input"].replace(' "content-digest"', "")})
        with self.assertRaises(InvalidHttpSignature):
            verify_request("POST", URL, stripped, BODY, keys, NOW)

    def test_authority(self):
        self.assertEqual("ledger.test", authority("https://Ledger.Test:443/x"))
        self.assertEqual("localhost:8090", authority("http://localhost:8090/x"))


class HttpSignatureVector(unittest.TestCase):

    def test_vector(self):
        v = vector("http-signature.json")
        body = v["body"].encode("utf-8")
        headers = sign_request(v["method"], v["url"], body, v["keyId"], LEDGER_KEY, v["created"], v["nonce"])
        self.assertEqual(v["headers"], headers)
        lower = {name.lower(): value for name, value in headers.items()}
        params = headers["Signature-Input"].split("=", 1)[1]
        self.assertEqual(v["signatureBase"], signature_base(v["method"], v["url"],
                                                             ["@method", "@authority", "@path", "content-digest"],
                                                             lower.get, params))
        verified = verify_request(v["method"], v["url"], v["headers"], body,
                                  lambda key_id: LEDGER_KEY.public_key if key_id == v["keyId"] else None,
                                  v["created"] + 10)
        self.assertEqual(v["keyId"], verified.key_id)


if __name__ == "__main__":
    unittest.main()
