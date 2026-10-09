package com.nexusphere.ledger.verifier;

import com.nexusphere.ledger.chain.EvidenceStatement;
import com.nexusphere.ledger.chain.LogReceipt;
import com.nexusphere.ledger.chain.SigningKeys;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Base64;

final class StatementCli {

    private StatementCli() {
    }

    static int run(String[] args, PrintStream out, PrintStream err, String usage) {
        String publicKey = null;
        Path receiptFile = null;
        Path statementFile = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--public-key" -> publicKey = args[++i].trim();
                    case "--public-key-file" -> publicKey = Files.readString(Path.of(args[++i])).trim();
                    case "--receipt" -> receiptFile = Path.of(args[++i]);
                    default -> {
                        if (statementFile != null || args[i].startsWith("--")) {
                            err.println(usage);
                            return VerifierCli.USAGE;
                        }
                        statementFile = Path.of(args[i]);
                    }
                }
            }
        } catch (ArrayIndexOutOfBoundsException | IOException e) {
            err.println(usage);
            return VerifierCli.USAGE;
        }
        if (statementFile == null || publicKey == null) {
            err.println(usage);
            return VerifierCli.USAGE;
        }
        PublicKey key;
        EvidenceStatement statement;
        LogReceipt receipt;
        try {
            key = SigningKeys.decodePublic(publicKey);
            statement = EvidenceStatement.parse(Files.readAllBytes(statementFile));
            receipt = receiptFile == null ? null : LogReceipt.parse(Files.readAllBytes(receiptFile));
        } catch (IOException | IllegalArgumentException e) {
            err.println("Cannot read the statement or receipt: " + e.getMessage());
            return VerifierCli.USAGE;
        }
        boolean signed = statement.verify(key);
        out.println("Nexusphere Ledger evidence statement");
        out.println("  Issuer      : " + statement.issuer());
        out.println("  Subject     : " + statement.subject());
        out.println("  Sequence    : " + statement.sequence());
        out.println("  Content hash: " + statement.contentHash());
        out.println("  Entry hash  : " + statement.entryHash());
        out.println("  Signature   : " + (signed ? "valid, key " + statement.keyId() : "INVALID"));
        boolean proven = true;
        if (receipt != null) {
            proven = receipt.verify(statement.leafHash(), key);
            out.println("  Receipt     : " + (proven ? "valid, log " + receipt.issuer() + " at " + receipt.treeSize()
                    + " entries, root " + Base64.getEncoder().encodeToString(
                    receipt.root(statement.leafHash()).orElseThrow()) : "INVALID"));
        }
        boolean valid = signed && proven;
        out.println(valid ? "Result: VALID" : "Result: INVALID");
        return valid ? VerifierCli.VALID : VerifierCli.INVALID;
    }
}
