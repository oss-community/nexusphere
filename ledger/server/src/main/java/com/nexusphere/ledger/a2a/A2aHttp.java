package com.nexusphere.ledger.a2a;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
class A2aHttp {

    record Reply(int status, byte[] body, Map<String, String> headers) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    Reply post(URI url, Duration timeout, Map<String, String> headers, byte[] body, String... wanted)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach((name, value) -> {
            if (value != null && !value.isBlank()) {
                request.header(name, value);
            }
        });
        HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        Map<String, String> found = new HashMap<>();
        for (String name : wanted) {
            response.headers().firstValue(name).ifPresent(value -> found.put(name, value));
        }
        return new Reply(response.statusCode(), response.body(), found);
    }
}
