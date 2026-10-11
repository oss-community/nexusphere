import argparse
import base64
import json
import os
import sys
import zipfile
from dataclasses import asdict

from .cose import EvidenceStatement, LogReceipt
from .index import verify_index_proof
from .keys import PublicKey
from .mandate import JwksKeys, Jws, MandateVerifier, StaticKeys, http_fetcher
from .note import NoteKey
from .package import verify_package
from .reconcile import NOT_RECEIVED, NOT_SENT, reconcile, verify_statement
from .report import verify_report
from .timestamp import read_certificates
from .timestamps import epoch_text
from .trackrecord import TrackRecordVerifier, match_track_record

VALID = 0
INVALID = 1
USAGE = 2
MAX_FILE = 256 * 1024 * 1024


def main(argv=None) -> int:
    sys.exit(run(sys.argv[1:] if argv is None else argv, sys.stdout, sys.stderr))


def run(args, out, err, stdin=None) -> int:
    if args and args[0] == "mandate":
        return _mandate(args[1:], out, err, stdin or sys.stdin)
    if args and args[0] == "statement":
        return _statement(args[1:], out, err)
    if args and args[0] == "report":
        return _report(args[1:], out, err)
    if args and args[0] == "index":
        return _index(args[1:], out, err)
    if args and args[0] == "track-record":
        return _track_record(args[1:], out, err, stdin or sys.stdin)
    if args and args[0] == "dispute":
        return _dispute(args[1:], out, err)
    if args and args[0] == "reconcile":
        return _reconcile(args[1:], out, err, stdin or sys.stdin)
    return _package(args[1:] if args and args[0] == "package" else args, out, err)


class _Parser(argparse.ArgumentParser):

    def error(self, message):
        raise _UsageError(message)


class _UsageError(Exception):
    pass


def _parse(parser, args, err):
    try:
        return parser.parse_args(args)
    except _UsageError as e:
        err.write(parser.format_usage() + str(e) + "\n")
        return None


def _public_key(options):
    if options.public_key_file:
        with open(options.public_key_file, encoding="utf-8") as file:
            return file.read().strip()
    return options.public_key.strip() if options.public_key else None


def _package_parser(prog, usage=None):
    parser = _Parser(prog=prog, usage=usage, add_help=False)
    parser.add_argument("--public-key")
    parser.add_argument("--public-key-file")
    parser.add_argument("--keys")
    parser.add_argument("--witness", action="append", default=[])
    parser.add_argument("--witnesses-required", type=int)
    parser.add_argument("--timestamp-certificates")
    parser.add_argument("--require-timestamp", action="store_true")
    parser.add_argument("--rekor-key", action="append", default=[])
    parser.add_argument("--require-rekor", action="store_true")
    parser.add_argument("--json", action="store_true")
    return parser


def _package(args, out, err) -> int:
    parser = _package_parser("nexusphere-ledger-verify")
    parser.add_argument("package")
    options = _parse(parser, args, err)
    if options is None:
        return USAGE
    reading = options.package
    try:
        public_key = _public_key(options)
        with open(options.package, encoding="utf-8") as file:
            pkg = json.load(file)
        key_list = None
        if options.keys:
            reading = options.keys
            with open(options.keys, encoding="utf-8") as file:
                key_list = json.load(file)
        certificates = []
        if options.timestamp_certificates:
            reading = options.timestamp_certificates
            with open(options.timestamp_certificates, "rb") as file:
                certificates = read_certificates(file.read())
    except (OSError, ValueError) as e:
        err.write("Cannot read %s: %s\n" % (reading, e))
        return USAGE
    try:
        report = verify_package(pkg, public_key, options.witness, options.witnesses_required, key_list,
                                certificates, options.require_timestamp, options.rekor_key,
                                options.require_rekor)
    except ValueError as e:
        err.write("Invalid argument: %s\n" % e)
        return USAGE
    if options.json:
        out.write(json.dumps(report.to_dict(), indent=2) + "\n")
    else:
        _print_package(report, bool(certificates), bool(options.rekor_key), out)
    return VALID if report.valid else INVALID


def _report(args, out, err) -> int:
    parser = _package_parser("nexusphere-ledger-verify report",
                             "%(prog)s [package options] --package <package.json> <report.json>")
    parser.add_argument("--package", required=True)
    parser.add_argument("report")
    options = _parse(parser, args, err)
    if options is None:
        return USAGE
    reading = options.report
    try:
        public_key = _public_key(options)
        with open(options.report, encoding="utf-8") as file:
            report = json.load(file)
        reading = options.package
        with open(options.package, encoding="utf-8") as file:
            pkg = json.load(file)
        key_list = None
        if options.keys:
            reading = options.keys
            with open(options.keys, encoding="utf-8") as file:
                key_list = json.load(file)
        certificates = []
        if options.timestamp_certificates:
            reading = options.timestamp_certificates
            with open(options.timestamp_certificates, "rb") as file:
                certificates = read_certificates(file.read())
    except (OSError, ValueError) as e:
        err.write("Cannot read %s: %s\n" % (reading, e))
        return USAGE
    return _finish(pkg, report, public_key, key_list, certificates, options.witness, options.witnesses_required,
                   options.rekor_key, options, [], out, err)


def _index(args, out, err) -> int:
    parser = _package_parser("nexusphere-ledger-verify index",
                             "%(prog)s [--public-key <key> | --public-key-file <file>] [--keys <keys.json>] "
                             "[--package <package.json>] [--json] <proof.json>")
    parser.add_argument("--package")
    parser.add_argument("proof")
    options = _parse(parser, args, err)
    if options is None:
        return USAGE
    reading = options.proof
    try:
        public_key = _public_key(options)
        with open(options.proof, encoding="utf-8") as file:
            proof = json.load(file)
        pkg = None
        if options.package:
            reading = options.package
            with open(options.package, encoding="utf-8") as file:
                pkg = json.load(file)
        key_list = None
        if options.keys:
            reading = options.keys
            with open(options.keys, encoding="utf-8") as file:
                key_list = json.load(file)
    except (OSError, ValueError) as e:
        err.write("Cannot read %s: %s\n" % (reading, e))
        return USAGE
    check = verify_index_proof(proof, public_key, key_list, pkg)
    if options.json:
        out.write(json.dumps(check.to_dict(), indent=2) + "\n")
        return VALID if check.valid else INVALID
    out.write("Nexusphere Ledger index proof\n")
    out.write("  Term        : %s\n" % " ".join(_show(value) for value in check.term.values()))
    out.write("  Index       : checkpoint %s, head %s\n" % (_show(check.sequence), _show(check.head_hash)))
    note = " (taken from the proof; pass --public-key to pin the ledger's key)" if public_key is None else ""
    out.write("  Signed by   : %s%s\n" % (_show(check.key_id), note))
    if check.present:
        out.write("  Finding     : %s entries, sequences %s to %s\n"
                  % (_show(check.count), _show(check.first_sequence), _show(check.last_sequence)))
    else:
        out.write("  Finding     : no entry up to sequence %s\n" % _show(check.sequence))
    if pkg is not None:
        checkpoint = pkg.get("checkpoint") if isinstance(pkg, dict) else None
        sequence = checkpoint.get("sequence") if isinstance(checkpoint, dict) else None
        out.write("  Package     : checkpoint %d\n" % (sequence if isinstance(sequence, int)
                                                       and not isinstance(sequence, bool) else 0))
    if check.valid:
        out.write("Result: VALID\n")
    else:
        out.write("Result: INVALID\n")
        for problem in check.problems:
            out.write("  - %s\n" % problem)
    return VALID if check.valid else INVALID


def _dispute(args, out, err) -> int:
    parser = _package_parser("nexusphere-ledger-verify dispute", "%(prog)s [package options] <dispute.zip>")
    parser.add_argument("dispute")
    options = _parse(parser, args, err)
    if options is None:
        return USAGE
    witnesses = list(options.witness)
    rekor_keys = list(options.rekor_key)
    notes = []
    try:
        public_key = _public_key(options)
        files = {}
        with zipfile.ZipFile(options.dispute) as archive:
            for info in archive.infolist():
                with archive.open(info) as file:
                    files[info.filename] = file.read(MAX_FILE + 1)
                if len(files[info.filename]) > MAX_FILE:
                    raise ValueError("%s is larger than %d bytes" % (info.filename, MAX_FILE))
        if "package.json" not in files or "report.json" not in files:
            raise ValueError("it has no package.json and report.json")
        pkg = json.loads(files["package.json"])
        report = json.loads(files["report.json"])
        if options.keys:
            with open(options.keys, encoding="utf-8") as file:
                key_list = json.load(file)
        else:
            key_list = json.loads(files["keys.json"]) if "keys.json" in files else None
        if options.timestamp_certificates:
            with open(options.timestamp_certificates, "rb") as file:
                certificates = read_certificates(file.read())
        elif "timestamp-authority.pem" in files:
            certificates = read_certificates(files["timestamp-authority.pem"])
            notes.append("the timestamp authority chain")
        else:
            certificates = []
        if not rekor_keys and "rekor.json" in files:
            log = json.loads(files["rekor.json"])
            key = log.get("key") if isinstance(log, dict) else None
            if isinstance(key, str):
                rekor_keys.append(NoteKey.parse(key))
                notes.append("the Rekor log key")
        if not witnesses and "witnesses.txt" in files:
            for line in files["witnesses.txt"].decode("utf-8").split("\n"):
                if line.strip():
                    witnesses.append(NoteKey.parse(line.strip()))
            notes.append("the witness keys")
    except (OSError, ValueError, zipfile.BadZipFile) as e:
        err.write("Cannot read %s: %s\n" % (options.dispute, e))
        return USAGE
    required = 0 if options.witnesses_required is None and not options.witness else options.witnesses_required
    if public_key is None:
        notes.append("no ledger key; pass --public-key")
    return _finish(pkg, report, public_key, key_list, certificates, witnesses, required, rekor_keys, options, notes,
                   out, err)


def _finish(pkg, report, public_key, key_list, certificates, witnesses, required, rekor_keys, options, notes, out,
            err) -> int:
    try:
        package_report = verify_package(pkg, public_key, witnesses, required, key_list, certificates,
                                        options.require_timestamp, rekor_keys, options.require_rekor)
    except ValueError as e:
        err.write("Invalid argument: %s\n" % e)
        return USAGE
    check = verify_report(report, pkg, public_key, key_list)
    valid = package_report.valid and check.valid
    if options.json:
        out.write(json.dumps({"valid": valid, "package": package_report.to_dict(), "report": check.to_dict(),
                              "takenFromArchive": notes}, indent=2) + "\n")
        return VALID if valid else INVALID
    _print_package(package_report, bool(certificates), bool(rekor_keys), out)
    out.write("Nexusphere Ledger report\n")
    out.write("  Report      : %s (%s)\n" % (_show(check.report), _show(check.title)))
    out.write("  Signed by   : %s\n" % _show(check.key_id))
    out.write("  Evidence    : checkpoint %s, %s entries\n" % (_show(check.checkpoint_sequence), _show(check.entries)))
    sections = ["%d %s" % (count, status.replace("_", " ").lower()) for status, count in check.statuses.items()]
    out.write("  Sections    : %s\n" % ", ".join(sections))
    if notes:
        out.write("  Archive     : taken from the archive itself: %s\n" % ", ".join(notes))
    if check.valid:
        out.write("Result: VALID\n")
    else:
        out.write("Result: INVALID\n")
        for problem in check.problems:
            out.write("  - %s\n" % problem)
    return VALID if valid else INVALID


def _show(value) -> str:
    return "null" if value is None else str(value)


def _print_package(r, timestamp_pinned, rekor_pinned, out):
    out.write("Nexusphere Ledger evidence package\n")
    if r.pinned_key_id is None:
        note = " (taken from the package; pass --public-key to pin the ledger's key)"
    elif r.pinned_key_id == r.key_id:
        note = " (pinned)"
    else:
        note = " (reached from pinned key %s through signed key rotations)" % r.pinned_key_id
    out.write("  Signing key : %s%s\n" % (r.key_id, note))
    out.write("  Checkpoint  : sequence %d, signed at %s\n" % (r.checkpoint_sequence, r.checkpoint_created_at))
    if r.compliance_profiles:
        out.write("  Compliance  : %s, signed in the checkpoint\n"
                  % ", ".join("%s (%s)" % (p["id"], p["digest"][:12]) for p in r.compliance_profiles))
    if r.timestamped_at is not None:
        out.write("  Timestamp   : %s by %s%s\n" % (r.timestamped_at, r.timestamp_authority, " (pinned)"
                  if timestamp_pinned else " (pass --timestamp-certificates to pin the timestamp authority)"))
    if r.rekor_log_index is not None:
        out.write("  Rekor       : entry %d in %s%s\n" % (r.rekor_log_index, r.rekor_origin, " (pinned)"
                  if rekor_pinned else " (pass --rekor-key to pin the Rekor log)"))
    out.write("  Anchor      : %s\n" % ("genesis" if r.anchor_sequence is None
                                       else "checkpoint %d" % r.anchor_sequence))
    out.write("  Chain       : %d links from sequence %d\n" % (r.checked_links, r.first_sequence))
    out.write("  Disclosed   : %d entries%s%s\n" % (r.disclosed_entries,
                                                    "" if r.agent_id is None else ", agent " + r.agent_id,
                                                    "" if r.principal_id is None else ", principal " + r.principal_id))
    if r.disclosed_prompts > 0:
        out.write("  Prompts     : %d prompt texts match the hashes their entries name\n" % r.disclosed_prompts)
    if r.disclosed_policies > 0:
        out.write("  Policies    : %d policy set texts match the hashes their entries name\n" % r.disclosed_policies)
    if r.log_tree_size is not None:
        out.write("  Log         : %s, %d entries, %d disclosed entries proven\n"
                  % (r.log_origin, r.log_tree_size, r.proven_entries))
        out.write("  Receipts    : %d SCITT statements with receipts\n" % r.receipted_entries)
        out.write("  Witnesses   : %s\n" % (", ".join(r.witnesses) if r.witnesses else "none"))
    if r.revoked_keys:
        out.write("  Revoked     : %s%s\n" % (", ".join(r.revoked_keys), " (witnesses prove the log checkpoint "
                                             "predates the compromise)" if r.valid else ""))
    if r.valid:
        out.write("Result: VALID\n")
    else:
        out.write("Result: INVALID\n")
        for problem in r.problems:
            out.write("  - %s\n" % problem)


def _statement(args, out, err) -> int:
    parser = _Parser(prog="nexusphere-ledger-verify statement", add_help=False)
    parser.add_argument("--public-key")
    parser.add_argument("--public-key-file")
    parser.add_argument("--receipt")
    parser.add_argument("statement")
    options = _parse(parser, args, err)
    if options is None:
        return USAGE
    try:
        public_key = _public_key(options)
        if public_key is None:
            raise _UsageError("--public-key or --public-key-file is required")
        key = PublicKey.from_base64(public_key)
        with open(options.statement, "rb") as file:
            statement = EvidenceStatement.parse(file.read())
        receipt = None
        if options.receipt:
            with open(options.receipt, "rb") as file:
                receipt = LogReceipt.parse(file.read())
    except _UsageError as e:
        err.write(parser.format_usage() + str(e) + "\n")
        return USAGE
    except (OSError, ValueError) as e:
        err.write("Cannot read the statement or receipt: %s\n" % e)
        return USAGE
    signed = statement.verify(key)
    out.write("Nexusphere Ledger evidence statement\n")
    out.write("  Issuer      : %s\n" % statement.issuer)
    out.write("  Subject     : %s\n" % statement.subject)
    out.write("  Sequence    : %d\n" % statement.sequence)
    out.write("  Content hash: %s\n" % statement.content_hash)
    out.write("  Entry hash  : %s\n" % statement.entry_hash)
    out.write("  Signature   : %s\n" % ("valid, key " + statement.key_id if signed else "INVALID"))
    proven = True
    if receipt is not None:
        proven = receipt.verify(statement.leaf_hash, key)
        out.write("  Receipt     : %s\n" % (
            "valid, log %s at %d entries, root %s" % (receipt.issuer, receipt.tree_size, base64.b64encode(
                receipt.root(statement.leaf_hash)).decode("ascii")) if proven else "INVALID"))
    valid = signed and proven
    out.write("Result: VALID\n" if valid else "Result: INVALID\n")
    return VALID if valid else INVALID


def _mandate(args, out, err, stdin) -> int:
    parser = _Parser(prog="nexusphere-ledger-verify mandate", add_help=False)
    parser.add_argument("--issuer", action="append", default=[])
    parser.add_argument("--audience")
    parser.add_argument("--action")
    parser.add_argument("--target")
    parser.add_argument("--public-key")
    parser.add_argument("--public-key-file")
    parser.add_argument("--skip-status", action="store_true")
    parser.add_argument("--nonce")
    parser.add_argument("--require-key-binding", action="store_true")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("token")
    options = _parse(parser, args, err)
    if options is None:
        return USAGE
    if not options.issuer or (options.action is None) != (options.target is None):
        err.write(parser.format_usage() + "--issuer is required, and --action and --target go together\n")
        return USAGE
    try:
        token = _read_token(options.token, stdin)
        public_key = _public_key(options)
    except OSError as e:
        err.write("Cannot read %s: %s\n" % (options.token, e))
        return USAGE
    keys = None
    if public_key is not None:
        try:
            key = PublicKey.from_base64(public_key)
        except ValueError:
            err.write("The public key is not a base64 X.509 Ed25519 key\n")
            return USAGE
        keys = StaticKeys({issuer.rstrip("/"): {key.key_id: key} for issuer in options.issuer})
    verifier = MandateVerifier(options.issuer, keys=keys, skip_status=options.skip_status,
                               audience=options.audience, require_key_binding=options.require_key_binding)
    check = verifier.verify_bound(token, options.nonce, options.action, options.target)
    if options.json:
        out.write(json.dumps({"valid": check.valid, "problems": [asdict(p) for p in check.problems],
                              "claims": None if check.claims is None else check.claims.to_payload()},
                             indent=2) + "\n")
    else:
        _print_mandate(check, options, out)
    return VALID if check.valid else INVALID


def _read_token(source, stdin) -> str:
    if source == "-":
        return stdin.read().strip()
    if os.path.isfile(source):
        with open(source, encoding="ascii") as file:
            return file.read().strip()
    return source.strip()


def _print_mandate(check, options, out):
    out.write("Nexusphere Ledger mandate\n")
    c = check.claims
    if c is not None:
        out.write("  Mandate     : %s from grant %s\n" % (c.mandate_id, c.grant_id))
        out.write("  Issuer      : %s\n" % c.issuer)
        out.write("  Agent       : %s for principal %s\n" % (c.agent_id, c.principal_id))
        out.write("  Audience    : %s\n" % ("any" if c.audience is None else c.audience))
        out.write("  Allows      : %s on %s%s\n" % (", ".join(c.actions), ", ".join(c.targets),
                                                     "" if c.max_uses is None else ", at most %d uses" % c.max_uses))
        out.write("  Valid       : %s to %s\n" % (epoch_text(c.not_before), epoch_text(c.expires_at)))
        out.write("  Agent key   : %s\n" % ("none" if c.holder_key is None else c.holder_key.key_id))
        out.write("  Status      : %s\n" % ("not checked" if options.skip_status
                                           else "index %d in %s" % (c.status_index, c.status_list_url)))
    if options.action is not None:
        out.write("  Checked     : %s on %s\n" % (options.action, options.target))
    if check.valid:
        out.write("Result: VALID\n")
    else:
        out.write("Result: INVALID\n")
        for problem in check.problems:
            out.write("  - %s: %s\n" % (problem.code, problem.message))


def _track_record(args, out, err, stdin) -> int:
    parser = _Parser(prog="nexusphere-ledger-verify track-record", add_help=False)
    parser.add_argument("--issuer", action="append", default=[])
    parser.add_argument("--audience")
    parser.add_argument("--public-key")
    parser.add_argument("--public-key-file")
    parser.add_argument("--nonce")
    parser.add_argument("--require-key-binding", action="store_true")
    parser.add_argument("--index-proof")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("token")
    options = _parse(parser, args, err)
    if options is None:
        return USAGE
    if not options.issuer:
        err.write(parser.format_usage() + "--issuer is required\n")
        return USAGE
    reading = options.token
    try:
        token = _read_token(options.token, stdin)
        public_key = _public_key(options)
        proof = None
        if options.index_proof:
            reading = options.index_proof
            with open(options.index_proof, encoding="utf-8") as file:
                proof = json.load(file)
    except (OSError, ValueError) as e:
        err.write("Cannot read %s: %s\n" % (reading, e))
        return USAGE
    keys = None
    if public_key is not None:
        try:
            key = PublicKey.from_base64(public_key)
        except ValueError:
            err.write("The public key is not a base64 X.509 Ed25519 key\n")
            return USAGE
        keys = StaticKeys({issuer.rstrip("/"): {key.key_id: key} for issuer in options.issuer})
    verifier = TrackRecordVerifier(options.issuer, keys=keys, audience=options.audience,
                                   require_key_binding=options.require_key_binding)
    check = verifier.verify_bound(token, options.nonce)
    problems = ["%s: %s" % (p.code, p.message) for p in check.problems]
    index = None
    if proof is not None:
        index = verify_index_proof(proof, public_key, None)
        problems.extend("INDEX_PROOF: " + p for p in index.problems)
        if check.claims is not None:
            problems.extend("INDEX_MISMATCH: " + p for p in match_track_record(check.claims, proof, index))
    valid = not problems
    if options.json:
        out.write(json.dumps({"valid": valid, "problems": problems,
                              "claims": None if check.claims is None else check.claims.to_payload(),
                              "indexProof": None if index is None else index.to_dict()}, indent=2) + "\n")
    else:
        _print_track_record(check.claims, index, valid, problems, out)
    return VALID if valid else INVALID


def _counts(counts) -> str:
    if counts is None:
        return "withheld"
    return ", ".join("%s %d" % item for item in sorted(counts.items())) or "none"


def _print_track_record(c, index, valid, problems, out):
    out.write("Nexusphere Ledger agent track record\n")
    if c is not None:
        out.write("  Credential  : %s\n" % c.record_id)
        out.write("  Issuer      : %s\n" % c.issuer)
        out.write("  Agent       : %s\n" % c.agent_id)
        out.write("  Audience    : %s\n" % ("any" if c.audience is None else c.audience))
        out.write("  Valid       : %s to %s\n" % (epoch_text(c.issued_at), epoch_text(c.expires_at)))
        out.write("  Checkpoint  : %d, index root %s\n" % (c.checkpoint_sequence, c.index_root))
        span = "" if c.first_sequence is None else ", sequences %d to %d, %s to %s" % (
            c.first_sequence, c.last_sequence, c.first_at, c.last_at)
        out.write("  Entries     : %d%s\n" % (c.total, span))
        out.write("  Outcomes    : %s\n" % _counts(c.outcomes))
        out.write("  Principals  : %s\n" % ("withheld" if c.principals is None else c.principals))
        out.write("  Actions     : %s\n" % _counts(c.actions))
        out.write("  Agent key   : %s\n" % ("none" if c.holder_key is None else c.holder_key.key_id))
    if index is not None:
        out.write("  Index proof : %s entries at checkpoint %s\n"
                  % (_show(index.count) if index.present else 0, _show(index.sequence)))
    if valid:
        out.write("Result: VALID\n")
    else:
        out.write("Result: INVALID\n")
        for problem in problems:
            out.write("  - %s\n" % problem)


def _reconcile(args, out, err, stdin) -> int:
    parser = _Parser(prog="nexusphere-ledger-verify reconcile", add_help=False)
    parser.add_argument("--public-key")
    parser.add_argument("--public-key-file")
    parser.add_argument("--peer-public-key")
    parser.add_argument("--peer-public-key-file")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("ours")
    parser.add_argument("theirs")
    options = _parse(parser, args, err)
    if options is None:
        return USAGE
    try:
        our_key = _public_key(options)
        if options.peer_public_key_file:
            with open(options.peer_public_key_file, encoding="utf-8") as file:
                peer_key = file.read().strip()
        else:
            peer_key = options.peer_public_key.strip() if options.peer_public_key else None
    except OSError as e:
        err.write(parser.format_usage() + str(e) + "\n")
        return USAGE
    tokens = []
    for source in (options.ours, options.theirs):
        try:
            tokens.append(_read_token(source, stdin))
        except OSError as e:
            err.write("Cannot read %s: %s\n" % (source, e))
            return USAGE
    jwks = JwksKeys(http_fetcher(10), 0)
    problems = []
    ours = _verified_statement(tokens[0], our_key, jwks, "ours", problems)
    theirs = _verified_statement(tokens[1], peer_key, jwks, "theirs", problems)
    reconciliation = None
    if ours is not None and theirs is not None:
        try:
            reconciliation = reconcile(ours, theirs)
        except ValueError as e:
            problems.append(str(e))
    valid = not problems and reconciliation is not None and reconciliation.reconciled
    if options.json:
        out.write(json.dumps({"valid": valid, "problems": problems,
                              "reconciliation": None if reconciliation is None else reconciliation.to_dict()},
                             indent=2) + "\n")
    else:
        _print_reconciliation(ours, theirs, reconciliation, problems, our_key is None or peer_key is None, valid,
                              out)
    return VALID if valid else INVALID


def _verified_statement(token, pinned, jwks, name, problems):
    try:
        payload = Jws.parse(token).payload
        issuer = payload.get("iss") if isinstance(payload.get("iss"), str) else ""
        keys = jwks
        if pinned is not None:
            key = PublicKey.from_base64(pinned)
            keys = StaticKeys({issuer: {key.key_id: key}})
        return verify_statement(token, keys, issuer)
    except Exception as e:
        problems.append("The %s statement cannot be trusted: %s" % (name, e))
        return None


def _print_reconciliation(ours, theirs, r, problems, fetched, valid, out):
    out.write("Nexusphere Ledger A2A reconciliation\n")
    if ours is not None:
        out.write("  Ours        : %s, %d exchanges\n" % (ours.issuer, len(ours.exchanges)))
    if theirs is not None:
        out.write("  Theirs      : %s, %d exchanges\n" % (theirs.issuer, len(theirs.exchanges)))
    if fetched:
        out.write("  Keys        : fetched from the issuers; pass --public-key and --peer-public-key to pin them\n")
    if r is not None:
        out.write("  Window      : %s to %s\n" % (epoch_text(r.start), epoch_text(r.end)))
        out.write("  Matched     : %d\n" % r.matched)
        out.write("  Undelivered : %d\n" % r.undelivered)
    if valid:
        out.write("Result: RECONCILED\n")
        return
    out.write("Result: NOT RECONCILED\n")
    for problem in problems:
        out.write("  - %s\n" % problem)
    if r is not None:
        for d in r.discrepancies:
            if d.kind == NOT_SENT:
                detail = "received as from %s, which never sent it" % d.sender
            elif d.kind == NOT_RECEIVED:
                detail = "sent by %s, never recorded as received" % d.sender
            else:
                detail = "sent by %s, differs in %s" % (d.sender, ", ".join(d.fields))
            out.write("  - %s %s at %s: %s\n" % (d.kind, d.request_id, epoch_text(d.requested_at), detail))


if __name__ == "__main__":
    main()
