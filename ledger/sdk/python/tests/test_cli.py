import hashlib
import io
import json
import os
import tempfile
import unittest
import zipfile

from nexusphere_ledger.cli import INVALID, USAGE, VALID, run
from nexusphere_ledger.keys import PrivateKey
from nexusphere_ledger.mandate import present_bound

from .support import ISSUER, LEDGER_KEY, entries, package, report, vector, witness_note_key


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

    def test_package_with_prompts(self):
        prompt = "Pay approved invoices only."
        prompt_hash = hashlib.sha256(prompt.encode("utf-8")).hexdigest()
        pkg = package(entries(3, models={2: {"name": "model-a", "promptHash": prompt_hash}}), {2})
        with tempfile.TemporaryDirectory() as directory:
            path = self.write(directory, "package.json", json.dumps(pkg))
            code, out, _ = self.run_cli(path)
            self.assertEqual(VALID, code, out)
            self.assertNotIn("Prompts", out)
            pkg["prompts"] = [{"hash": prompt_hash, "text": prompt}]
            path = self.write(directory, "package.json", json.dumps(pkg))
            code, out, _ = self.run_cli(path)
            self.assertEqual(VALID, code, out)
            self.assertIn("  Disclosed   : 1 entries\n"
                          "  Prompts     : 1 prompt texts match the hashes their entries name\n"
                          "  Log         : ", out)

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

    def test_report(self):
        pkg = package(entries(4), {2})
        signed = report(pkg)
        with tempfile.TemporaryDirectory() as directory:
            pkg_path = self.write(directory, "package.json", json.dumps(pkg))
            path = self.write(directory, "report.json", json.dumps(signed))
            code, out, _ = self.run_cli("report", "--public-key", LEDGER_KEY.public_key.encoded, "--package", pkg_path,
                                        path)
            self.assertEqual(VALID, code, out)
            self.assertIn("Nexusphere Ledger report\n  Report      : dispute (Dispute package)\n"
                          "  Signed by   : %s\n  Evidence    : checkpoint 4, 1 entries\n"
                          "  Sections    : 1 shown\nResult: VALID\n" % LEDGER_KEY.key_id, out)
            signed["sections"][0]["findings"] = []
            path = self.write(directory, "report.json", json.dumps(signed))
            code, out, _ = self.run_cli("report", "--json", "--package", pkg_path, path)
            self.assertEqual(INVALID, code)
            result = json.loads(out)
            self.assertEqual(["valid", "package", "report", "takenFromArchive"], list(result))
            self.assertTrue(result["package"]["valid"])
            self.assertEqual(["the signature of the report is not valid"], result["report"]["problems"])
            code, _, err = self.run_cli("report", "--public-key", LEDGER_KEY.public_key.encoded, path)
            self.assertEqual(USAGE, code)
            self.assertIn("usage: nexusphere-ledger-verify report [package options] --package <package.json> "
                          "<report.json>", err)
            missing = os.path.join(directory, "missing.json")
            self.assertEqual(USAGE, self.run_cli("report", "--package", missing, path)[0])
            self.assertEqual(USAGE, self.run_cli("--package", pkg_path, path)[0])

    def test_dispute(self):
        pkg = package(entries(4), {2}, witness=True)
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, "dispute.zip")
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("README.txt", "Nexusphere Ledger dispute package\n")
                archive.writestr("package.json", json.dumps(pkg))
                archive.writestr("report.json", json.dumps(report(pkg)))
                archive.writestr("keys.json", json.dumps(pkg["keys"]))
                archive.writestr("rekor.json", json.dumps({"key": vector("rekor.json")["rekorVerifierKey"]}))
                archive.writestr("witnesses.txt", witness_note_key().vkey + "\n")
            code, out, _ = self.run_cli("dispute", "--public-key", LEDGER_KEY.public_key.encoded, path)
            self.assertEqual(VALID, code, out)
            self.assertIn("Witnesses   : witness.example", out)
            self.assertIn("  Archive     : taken from the archive itself: the Rekor log key, the witness keys\n"
                          "Result: VALID\n", out)
            code, out, _ = self.run_cli("dispute", "--json", path)
            self.assertEqual(VALID, code, out)
            result = json.loads(out)
            self.assertEqual(["the Rekor log key", "the witness keys", "no ledger key; pass --public-key"],
                             result["takenFromArchive"])
            self.assertEqual(1, result["report"]["entries"])
            code, out, _ = self.run_cli("dispute", "--witness", witness_note_key().vkey, "--witnesses-required", "2",
                                        path)
            self.assertEqual(INVALID, code)
            self.assertIn("the log checkpoint is cosigned by 1 of the 2 required witnesses", out)
            empty = os.path.join(directory, "empty.zip")
            with zipfile.ZipFile(empty, "w") as archive:
                archive.writestr("package.json", json.dumps(pkg))
            code, _, err = self.run_cli("dispute", empty)
            self.assertEqual(USAGE, code)
            self.assertEqual("Cannot read %s: it has no package.json and report.json\n" % empty, err)
            code, _, err = self.run_cli("dispute")
            self.assertEqual(USAGE, code)
            self.assertIn("usage: nexusphere-ledger-verify dispute [package options] <dispute.zip>", err)

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
