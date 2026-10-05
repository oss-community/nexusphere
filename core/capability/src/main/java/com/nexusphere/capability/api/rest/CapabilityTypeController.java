package com.nexusphere.capability.api.rest;

import com.nexusphere.capability.application.CapabilityTypeService;
import com.nexusphere.capability.domain.model.CapabilityType;
import com.nexusphere.shared.error.ValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/capability-types")
class CapabilityTypeController {

    record CapabilityTypeRequest(@NotBlank @Size(max = CapabilityType.CODE_MAX_LENGTH) String code,
                                 @NotBlank @Size(max = CapabilityType.NAME_MAX_LENGTH) String name,
                                 String description, @NotNull Map<String, Object> schema) {
    }

    record CapabilityTypeResponse(String id, String code, String name, String description, int version,
                                  Map<String, Object> schema, Instant createdAt) {

        static CapabilityTypeResponse of(CapabilityType type) {
            return new CapabilityTypeResponse(type.id().toString(), type.code(), type.name(), type.description(),
                    type.version(), type.schema().document(), type.createdAt());
        }
    }

    private final CapabilityTypeService types;

    CapabilityTypeController(CapabilityTypeService types) {
        this.types = types;
    }

    @PostMapping
    ResponseEntity<CapabilityTypeResponse> register(@Valid @RequestBody CapabilityTypeRequest request) {
        CapabilityType type = types.register(request.code(), request.name(), request.description(), request.schema());
        return ResponseEntity.created(URI.create("/api/v1/capability-types/" + type.id()))
                .body(CapabilityTypeResponse.of(type));
    }

    @GetMapping
    List<CapabilityTypeResponse> list(@RequestParam(required = false) String code) {
        return types.list(code).stream().map(CapabilityTypeResponse::of).toList();
    }

    @GetMapping("/{typeId}")
    CapabilityTypeResponse get(@PathVariable String typeId) {
        return CapabilityTypeResponse.of(types.get(typeId(typeId)));
    }

    private static UUID typeId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ValidationException("INVALID_IDENTIFIER", "Invalid CapabilityTypeId: " + value);
        }
    }
}
