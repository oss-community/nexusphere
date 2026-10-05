package com.nexusphere.e2e.support;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

public final class WarehouseTransportRobot {

    private final ApiClient machine;
    private final String tasks;
    private final List<String> received = new ArrayList<>();

    public WarehouseTransportRobot(ApiClient machine, String networkId) {
        this.machine = machine;
        this.tasks = "/api/v1/networks/" + networkId + "/machine/tasks";
    }

    public List<String> received() {
        return List.copyOf(received);
    }

    public JsonNode pending() {
        ApiClient.Response response = machine.get(tasks);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    public List<JsonNode> work(Function<JsonNode, Optional<String>> failure) {
        List<JsonNode> reports = new ArrayList<>();
        pending().valueStream().filter(task -> task.path("status").asString().equals("AUTHORIZED")).forEach(task -> {
            String id = task.path("id").asString();
            received.add(id);
            ApiClient.Response started = machine.post(tasks + "/" + id + "/start", "");
            assertThat(started.status()).as(started.body()).isEqualTo(200);
            ApiClient.Response report = failure.apply(task)
                    .map(reason -> machine.post(tasks + "/" + id + "/fail", "{\"reason\":\"" + reason + "\"}"))
                    .orElseGet(() -> machine.post(tasks + "/" + id + "/complete",
                            "{\"result\":{\"delivered\":true,\"dock\":\"" + task.path("metadata").path("to")
                                    .asString() + "\"}}"));
            assertThat(report.status()).as(report.body()).isEqualTo(200);
            reports.add(report.json());
        });
        return reports;
    }

    public ApiClient.Response start(String taskId) {
        return machine.post(tasks + "/" + taskId + "/start", "");
    }
}
