import io
import json
import os
import tempfile
import unittest

from nexusphere_ledger.cli import INVALID, USAGE, VALID, run
from nexusphere_ledger.keys import PrivateKey
from nexusphere_ledger.mandate import present_bound

from .support import ISSUER, LEDGER_KEY, entries, package, vector, witness_note_key


class Cli(unittest.TestCase):

    def run_cli(self, *args):
        out, err = io.StringIO(), io.StringIO()
        return run(list(args), out, err), out.getvalue(), err.getvalue()

    def write(self, directory, name, content):
        path = os.path.join(directory, name)
        with open(path, "wb" if isinstance(content, bytes) else "w") as file:
            file.write(content)
        return path

    def test_package(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write(directory, "package.json", json.dumps(package(entries(4), {2}, witness=True)))
            code, out, _ = self.run_cli("--public-key", LEDGER_KEY.public_key.encoded, "--witness",
                                        witness_note_key().vkey, path)
            self.assertEqual(VALID, code)
            self.assertIn("(pinned)", out)
            self.assertIn("Witnesses   : witness.example", out)
            code, out, _ = self.run_cli("--json", "--witnesses-required", "2", "--witness",
                                        witness_note_key().vkey, path)
            self.assertEqual(INVALID, code)
            self.assertFalse(json.loads(out)["valid"])
            self.assertEqual(USAGE, self.run_cli()[0])
            self.assertEqual(USAGE, self.run_cli(os.path.join(directory, "missing.json"))[0])

    def test_timestamped_package(self):
        v = vector("timestamp.json")
        pkg = package(entries(3), {1})
        with tempfile.TemporaryDirectory() as directory:
            path = self.write(directory, "package.json", json.dumps(pkg))
            code, out, _ = self.run_cli("--require-timestamp", path)
            self.assertEqual(INVALID, code)
            self.assertIn("checkpoint 3 has no timestamp", out)
            pkg["checkpoint"]["timestamp"] = v["token"]
            path = self.write(directory, "package.json", json.dumps(pkg))
            chain = self.write(directory, "chain.pem", "".join(v["certificates"][1:]))
            code, out, _ = self.run_cli("--timestamp-certificates", chain, path)
            self.assertEqual(INVALID, code)
            self.assertIn("the timestamp of checkpoint 3: the timestamp is for different data", out)
            missing = os.path.join(directory, "missing.pem")
            self.assertEqual(USAGE, self.run_cli("--timestamp-certificates", missing, path)[0])

    def test_rekor_flags(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write(directory, "package.json", json.dumps(package(entries(3), {1})))
            code, out, _ = self.run_cli("--require-rekor", path)
            self.assertEqual(INVALID, code)
            self.assertIn("checkpoint 3 is not anchored in Rekor", out)
            self.assertEqual(USAGE, self.run_cli("--rekor-key", "not a key", path)[0])

    def test_statement(self):
        item = vector("scitt.json")["items"][0]
        with tempfile.TemporaryDirectory() as directory:
            statement = self.write(directory, "statement.cose", bytes.fromhex(item["statement"]))
            receipt = self.write(directory, "receipt.cose", bytes.fromhex(item["receipt"]))
            code, out, _ = self.run_cli("statement", "--public-key", LEDGER_KEY.public_key.encoded, "--receipt",
                                        receipt, statement)
            self.assertEqual(VALID, code, out)
            self.assertIn("Sequence    : 1", out)

    def test_mandate(self):
        token = vector("mandate.json")["token"]
        code, out, _ = self.run_cli("mandate", "--issuer", ISSUER, "--public-key", LEDGER_KEY.public_key.encoded,
                                    "--skip-status", "--action", "a2a/send", "--target", "supplier/sales", token)
        self.assertEqual(VALID, code, out)
        self.assertIn("Agent       : sales-agent for principal globex", out)
        code, out, _ = self.run_cli("mandate", "--issuer", "https://other.example", "--public-key",
                                    LEDGER_KEY.public_key.encoded, "--skip-status", "--json", token)
        self.assertEqual(INVALID, code)
        self.assertEqual("UNTRUSTED_ISSUER", json.loads(out)["problems"][0]["code"])

    def test_key_bound_mandate(self):
        v = vector("mandate-key-binding.json")
        agent = PrivateKey.from_seed(bytes.fromhex(v["agentSeed"]))
        presented = present_bound(v["token"], agent, v["audience"], v["nonce"])
        common = ("mandate", "--issuer", ISSUER, "--public-key", LEDGER_KEY.public_key.encoded, "--skip-status")
        code, out, _ = self.run_cli(*common, "--nonce", v["nonce"], presented)
        self.assertEqual(VALID, code, out)
        self.assertIn("Agent key   : " + agent.key_id, out)
        code, out, _ = self.run_cli(*common, "--nonce", "00" * 32, presented)
        self.assertEqual(INVALID, code)
        self.assertIn("KEY_BINDING_INVALID", out)
        code, out, _ = self.run_cli(*common, "--require-key-binding", vector("mandate.json")["token"])
        self.assertEqual(INVALID, code)
        self.assertIn("KEY_NOT_BOUND", out)


if __name__ == "__main__":
    unittest.main()
