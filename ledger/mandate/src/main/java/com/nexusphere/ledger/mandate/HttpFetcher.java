package com.nexusphere.ledger.mandate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@FunctionalInterface
public interface HttpFetcher {

    String get(URI uri, String accept);

    static HttpFetcher create(Duration timeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return (uri, accept) -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", accept).GET().build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException(uri + " answered " + response.statusCode());
                }
                return response.body();
            } catch (IOException e) {
                throw new IllegalStateException(uri + " is unreachable: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(uri + " was interrupted", e);
            }
        };
    }
}
