package com.nexusphere.ledger.a2a;

import com.nexusphere.ledger.server.web.EventStream;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
class A2aHttp {

    record Reply(int status, byte[] body, Map<String, String> headers, InputStream stream) {

        boolean streamed() {
            return stream != null;
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    Reply post(URI url, Duration timeout, Map<String, String> headers, byte[] body, String... wanted)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(url).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, " + EventStream.CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach((name, value) -> {
            if (value != null && !value.isBlank()) {
                request.header(name, value);
            }
        });
        HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        Map<String, String> found = new HashMap<>();
        for (String name : wanted) {
            response.headers().firstValue(name).ifPresent(value -> found.put(name, value));
        }
        if (EventStream.isEventStream(response.headers().firstValue("Content-Type").orElse(null))) {
            return new Reply(response.statusCode(), null, found, response.body());
        }
        try (InputStream stream = response.body()) {
            return new Reply(response.statusCode(), stream.readAllBytes(), found, null);
        }
    }
}
