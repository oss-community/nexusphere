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

    def test_index(self):
        proofs = vector("index-proof.json")["proofs"]
        head = proofs[0]["index"]["headHash"]
        with tempfile.TemporaryDirectory() as directory:
            present = self.write(directory, "present.json", json.dumps(proofs[0]))
            absent = self.write(directory, "absent.json", json.dumps(proofs[2]))
            pkg_path = self.write(directory, "package.json", json.dumps({"checkpoint": {"sequence": 3,
                                                                                         "headHash": head}}))
            code, out, _ = self.run_cli("index", "--public-key", LEDGER_KEY.public_key.encoded, present)
            self.assertEqual(VALID, code, out)
            self.assertEqual("Nexusphere Ledger index proof\n  Term        : agent invoice-agent\n"
                             "  Index       : checkpoint 3, head %s\n  Signed by   : %s\n"
                             "  Finding     : 2 entries, sequences 1 to 2\nResult: VALID\n"
                             % (head, LEDGER_KEY.key_id), out)
            code, out, _ = self.run_cli("index", "--package", pkg_path, absent)
            self.assertEqual(VALID, code, out)
            self.assertEqual("Nexusphere Ledger index proof\n  Term        : grant grant-never-used\n"
                             "  Index       : checkpoint 3, head %s\n  Signed by   : %s (taken from the proof; pass "
                             "--public-key to pin the ledger's key)\n  Finding     : no entry up to sequence 3\n"
                             "  Package     : checkpoint 3\nResult: VALID\n" % (head, LEDGER_KEY.key_id), out)
            changed = dict(proofs[0], leaf=dict(proofs[0]["leaf"], count=1))
            path = self.write(directory, "changed.json", json.dumps(changed))
            code, out, _ = self.run_cli("index", "--public-key", LEDGER_KEY.public_key.encoded, path)
            self.assertEqual(INVALID, code)
            self.assertIn("Result: INVALID\n  - the proof does not lead to the signed root\n", out)
            code, out, _ = self.run_cli("index", "--json", path)
            self.assertEqual(INVALID, code)
            result = json.loads(out)
            self.assertEqual(["valid", "term", "present", "count", "firstSequence", "lastSequence", "sequence",
                              "headHash", "keyId", "problems"], list(result))
            self.assertEqual(["the proof does not lead to the signed root"], result["problems"])
            self.assertEqual(USAGE, self.run_cli("index")[0])
            self.assertEqual(USAGE, self.run_cli("index", os.path.join(directory, "missing.json"))[0])
            code, _, err = self.run_cli("index", "--package", os.path.join(directory, "missing.json"), present)
            self.assertEqual(USAGE, code)
            self.assertIn("Cannot read %s" % os.path.join(directory, "missing.json"), err)

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


    def test_track_record(self):
        v = vector("agent-track-record.json")
        with tempfile.TemporaryDirectory() as directory:
            proof = self.write(directory, "proof.json", json.dumps(v["indexProof"]))
            common = ("track-record", "--issuer", ISSUER, "--public-key", LEDGER_KEY.public_key.encoded)
            code, out, _ = self.run_cli(*common, "--index-proof", proof, v["presentedWithOutcomesOnly"])
            self.assertEqual(VALID, code, out)
            self.assertIn("Agent       : invoice-agent", out)
            self.assertIn("Outcomes    : DENIED 1, SUCCEEDED 1", out)
            self.assertIn("Actions     : withheld", out)
            self.assertIn("Entries     : 2, sequences 1 to 2, 2026-10-01T08:00:00.128456Z to "
                          "2026-10-01T08:00:01.128456Z", out)
            self.assertIn("Index proof : 2 entries at checkpoint 3", out)
            other = dict(v["indexProof"], term={"type": "agent", "agentId": "sales-agent"})
            code, out, _ = self.run_cli(*common, "--json", "--index-proof",
                                        self.write(directory, "other.json", json.dumps(other)), v["token"])
            self.assertEqual(INVALID, code)
            report = json.loads(out)
            self.assertIn("INDEX_MISMATCH: The index proof is not about the agent invoice-agent", report["problems"])
            self.assertEqual(v["claims"], report["claims"])
            code, out, _ = self.run_cli(*common, "--require-key-binding", v["token"])
            self.assertEqual(INVALID, code)
            self.assertIn("KEY_NOT_BOUND", out)
            self.assertEqual(USAGE, self.run_cli("track-record", v["token"])[0])
            self.assertEqual(USAGE, self.run_cli(*common, "--index-proof", os.path.join(directory, "missing.json"),
                                                 v["token"])[0])

    def test_reconcile(self):
        v = vector("a2a-reconciliation.json")
        with tempfile.TemporaryDirectory() as directory:
            theirs = self.write(directory, "theirs.jwt", v["theirs"])
            pinned = ("reconcile", "--public-key", v["oursPublicKey"], "--peer-public-key", v["theirsPublicKey"])
            code, out, _ = self.run_cli(*pinned, v["ours"], theirs)
            self.assertEqual(INVALID, code, out)
            self.assertEqual("Nexusphere Ledger A2A reconciliation\n"
                             "  Ours        : https://ledger.example, 2 exchanges\n"
                             "  Theirs      : https://supplier.example, 2 exchanges\n"
                             "  Window      : 2026-10-01T08:00:00Z to 2026-10-02T08:00:00Z\n"
                             "  Matched     : 1\n"
                             "  Undelivered : 1\n"
                             "Result: NOT RECONCILED\n"
                             "  - NOT_SENT 00000000-0000-4000-8000-000000000103 at 2026-10-01T08:00:30Z: received as "
                             "from https://ledger.example, which never sent it\n", out)
            code, out, _ = self.run_cli(*pinned, "--json", v["ours"], theirs)
            self.assertEqual(INVALID, code)
            report = json.loads(out)
            self.assertEqual([], report["problems"])
            self.assertEqual(v["reconciliation"], report["reconciliation"])
            key_file = self.write(directory, "ours.pem", v["theirsPublicKey"])
            code, out, _ = self.run_cli("reconcile", "--public-key-file", key_file, "--peer-public-key",
                                        v["theirsPublicKey"], v["ours"], theirs)
            self.assertEqual(INVALID, code)
            self.assertIn("  - The ours statement cannot be trusted: The token is signed with an unknown key", out)
            self.assertNotIn("Window", out)
            code, out, _ = self.run_cli(*pinned, theirs, v["ours"])
            self.assertEqual(INVALID, code)
            self.assertIn("The ours statement cannot be trusted", out)
            self.assertEqual(USAGE, self.run_cli("reconcile", v["ours"])[0])
            self.assertEqual(USAGE, self.run_cli("reconcile", "--unknown", v["ours"], theirs)[0])
            self.assertEqual(USAGE, self.run_cli("reconcile", "--public-key")[0])


if __name__ == "__main__":
    unittest.main()
