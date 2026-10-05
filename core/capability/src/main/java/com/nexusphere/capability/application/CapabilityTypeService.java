package com.nexusphere.capability.application;

import com.nexusphere.capability.domain.model.CapabilitySchema;
import com.nexusphere.capability.domain.model.CapabilityType;
import com.nexusphere.capability.domain.repository.CapabilityTypeRepository;
import com.nexusphere.shared.error.NotFoundException;
import com.nexusphere.shared.time.TimeProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class CapabilityTypeService {

    private final CapabilityTypeRepository types;
    private final TimeProvider time;

    CapabilityTypeService(CapabilityTypeRepository types, TimeProvider time) {
        this.types = types;
        this.time = time;
    }

    public CapabilityType register(String code, String name, String description, Map<String, Object> schema) {
        CapabilitySchema parsed = CapabilitySchema.of(schema);
        String normalizedCode = code == null ? null : code.trim();
        int version = normalizedCode == null ? 1
                : types.findLatest(normalizedCode).map(latest -> latest.version() + 1).orElse(1);
        return types.save(CapabilityType.register(UUID.randomUUID(), normalizedCode, name, description, version,
                parsed, time.now()));
    }

    @Transactional(readOnly = true)
    public CapabilityType get(UUID id) {
        return types.findById(id).orElseThrow(() -> new NotFoundException("CapabilityType", id));
    }

    @Transactional(readOnly = true)
    public List<CapabilityType> list(String code) {
        return code == null || code.isBlank() ? types.findAll() : types.findByCode(code.trim());
    }

    @Transactional(readOnly = true)
    public CapabilityType resolve(String code, Integer version) {
        String normalizedCode = code == null ? "" : code.trim();
        return (version == null ? types.findLatest(normalizedCode) : types.find(normalizedCode, version))
                .orElseThrow(() -> new NotFoundException("CapabilityType",
                        version == null ? normalizedCode : normalizedCode + " v" + version));
    }
}
