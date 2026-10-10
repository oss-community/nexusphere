package com.nexusphere.ledger.chain;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimestampTokenTest {

    private static String vector;
    private static TimestampToken token;
    private static byte[] imprint;
    private static List<X509Certificate> chain;

    @BeforeAll
    static void read() throws IOException {
        vector = Files.readString(Path.of("../conformance/vectors/timestamp.json"));
        token = TimestampToken.parse(field("token"));
        imprint = HexFormat.of().parseHex(field("messageImprint"));
        List<X509Certificate> certificates = new ArrayList<>();
        Matcher pem = Pattern.compile("-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----").matcher(vector);
        while (pem.find()) {
            certificates.addAll(TimestampToken.readCertificates(Base64.getDecoder()
                    .decode(pem.group(1).replace("\\n", ""))));
        }
        chain = certificates;
    }

    private static String field(String name) {
        Matcher matcher = Pattern.compile("\"" + name
                + "\"\\s*:\\s*\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"")
                .matcher(vector);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    @Test
    void aTokenFromARealAuthorityVerifiesAgainstItsChain() {
        TimestampToken.Verification verification = token.verify(imprint, chain.subList(1, 3));
        assertThat(verification.valid()).as(verification.problem()).isTrue();
        assertThat(verification.pinned()).isTrue();
        assertThat(verification.genTime()).isEqualTo(Instant.parse(field("genTime")));
        assertThat(verification.authority()).isEqualTo(field("authority"));
    }

    @Test
    void theResponseCarriesTheSameToken() {
        TimestampToken fromResponse = TimestampToken.fromResponse(Base64.getDecoder().decode(field("response")));
        assertThat(fromResponse.base64()).isEqualTo(token.base64());
        assertThat(token.imprintHex()).isEqualTo(field("messageImprint"));
    }

    @Test
    void theImprintIsTheHashOfTheSignedCheckpointBytes() {
        String signedBytes = field("signedBytes").replace("\\\"", "\"");
        assertThat(Hashes.sha256(signedBytes.getBytes(StandardCharsets.UTF_8))).isEqualTo(field("messageImprint"));
    }

    @Test
    void aTokenForOtherDataIsRejected() {
        byte[] other = imprint.clone();
        other[0] ^= 1;
        assertThat(token.verify(other, chain).problem()).isEqualTo("the timestamp is for different data");
    }

    @Test
    void anAuthorityOutsideThePinnedCertificatesIsRejected() {
        assertThat(token.verify(imprint, List.of(chain.get(2))).problem())
                .isEqualTo("the timestamp authority is not trusted");
    }

    @Test
    void anUnpinnedTokenIsValidButNotPinned() {
        TimestampToken.Verification verification = token.verify(imprint, List.of());
        assertThat(verification.valid()).isTrue();
        assertThat(verification.pinned()).isFalse();
    }

    @Test
    void aChangedTokenNoLongerVerifies() {
        byte[] changed = token.encoded().clone();
        changed[changed.length - 5] ^= 1;
        assertThat(TimestampToken.parse(changed).verify(imprint, chain).valid()).isFalse();
    }

    @Test
    void aRequestCarriesTheImprintAndNonce() {
        byte[] request = TimestampToken.request(imprint, BigInteger.valueOf(42));
        List<Der.Node> fields = Der.read(request).children();
        assertThat(fields.get(1).child(1).content()).isEqualTo(imprint);
        assertThat(fields.get(2).integer()).isEqualTo(BigInteger.valueOf(42));
    }

    @Test
    void aRejectedResponseIsReported() {
        byte[] rejection = Der.encode(Der.SEQUENCE, Der.encode(Der.SEQUENCE, Der.integer(BigInteger.TWO)));
        assertThatThrownBy(() -> TimestampToken.fromResponse(rejection)).isInstanceOf(IllegalArgumentException.class);
    }
}
