package com.nexusphere.network.api.rest;

import com.nexusphere.network.application.NetworkService;
import com.nexusphere.network.domain.model.Network;
import com.nexusphere.shared.context.ExecutionContext;
import com.nexusphere.shared.id.NetworkId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/networks")
class NetworkController {

    record CreateNetworkRequest(
            @NotBlank @Size(max = Network.NAME_MAX_LENGTH) String name,
            @Size(max = Network.DESCRIPTION_MAX_LENGTH) String description) {
    }

    record NetworkResponse(String id, String name, String description, String status,
                           Instant createdAt, Instant updatedAt) {

        static NetworkResponse of(Network network) {
            return new NetworkResponse(network.id().toString(), network.name(), network.description(),
                    network.status().name(), network.createdAt(), network.updatedAt());
        }
    }

    private final NetworkService networks;

    NetworkController(NetworkService networks) {
        this.networks = networks;
    }

    @PostMapping
    ResponseEntity<NetworkResponse> create(@Valid @RequestBody CreateNetworkRequest request, ExecutionContext context) {
        Network network = networks.create(request.name(), request.description(), context);
        return ResponseEntity.created(URI.create("/api/v1/networks/" + network.id()))
                .body(NetworkResponse.of(network));
    }

    @GetMapping
    List<NetworkResponse> list() {
        return networks.list().stream().map(NetworkResponse::of).toList();
    }

    @GetMapping("/{networkId}")
    NetworkResponse get(@PathVariable String networkId) {
        return NetworkResponse.of(networks.get(NetworkId.of(networkId)));
    }

    @PostMapping("/{networkId}/activate")
    NetworkResponse activate(@PathVariable String networkId, ExecutionContext context) {
        return NetworkResponse.of(networks.activate(NetworkId.of(networkId), context));
    }

    @PostMapping("/{networkId}/suspend")
    NetworkResponse suspend(@PathVariable String networkId, ExecutionContext context) {
        return NetworkResponse.of(networks.suspend(NetworkId.of(networkId), context));
    }

    @PostMapping("/{networkId}/archive")
    NetworkResponse archive(@PathVariable String networkId, ExecutionContext context) {
        return NetworkResponse.of(networks.archive(NetworkId.of(networkId), context));
    }
}
