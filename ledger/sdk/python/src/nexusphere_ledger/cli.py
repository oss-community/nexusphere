import argparse
import base64
import json
import os
import sys
from dataclasses import asdict

from .cose import EvidenceStatement, LogReceipt
from .keys import PublicKey
from .mandate import MandateVerifier, StaticKeys
from .package import verify_package
from .timestamps import epoch_text

VALID = 0
INVALID = 1
USAGE = 2


def main(argv=None) -> int:
    sys.exit(run(sys.argv[1:] if argv is None else argv, sys.stdout, sys.stderr))


def run(args, out, err, stdin=None) -> int:
    if args and args[0] == "mandate":
        return _mandate(args[1:], out, err, stdin or sys.stdin)
    if args and args[0] == "statement":
        return _statement(args[1:], out, err)
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


def _package(args, out, err) -> int:
    parser = _Parser(prog="nexusphere-ledger-verify", add_help=False)
    parser.add_argument("--public-key")
    parser.add_argument("--public-key-file")
    parser.add_argument("--keys")
    parser.add_argument("--witness", action="append", default=[])
    parser.add_argument("--witnesses-required", type=int)
    parser.add_argument("--json", action="store_true")
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
    except (OSError, ValueError) as e:
        err.write("Cannot read %s: %s\n" % (reading, e))
        return USAGE
    try:
        report = verify_package(pkg, public_key, options.witness, options.witnesses_required, key_list)
    except ValueError as e:
        err.write("Invalid argument: %s\n" % e)
        return USAGE
    if options.json:
        out.write(json.dumps(report.to_dict(), indent=2) + "\n")
    else:
        _print_package(report, out)
    return VALID if report.valid else INVALID


def _print_package(r, out):
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
    out.write("  Anchor      : %s\n" % ("genesis" if r.anchor_sequence is None
                                       else "checkpoint %d" % r.anchor_sequence))
    out.write("  Chain       : %d links from sequence %d\n" % (r.checked_links, r.first_sequence))
    out.write("  Disclosed   : %d entries%s%s\n" % (r.disclosed_entries,
                                                    "" if r.agent_id is None else ", agent " + r.agent_id,
                                                    "" if r.principal_id is None else ", principal " + r.principal_id))
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


if __name__ == "__main__":
    main()
