package com.nexusphere.capability.infrastructure.persistence;

import com.nexusphere.capability.domain.model.CapabilitySchema;
import com.nexusphere.capability.domain.model.CapabilityType;
import com.nexusphere.capability.domain.repository.CapabilityTypeRepository;
import com.nexusphere.shared.error.ConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaCapabilityTypeRepository implements CapabilityTypeRepository {

    private final CapabilityTypeJpaRepository jpa;
    private final JsonDocuments json;

    JpaCapabilityTypeRepository(CapabilityTypeJpaRepository jpa, JsonDocuments json) {
        this.jpa = jpa;
        this.json = json;
    }

    @Override
    public CapabilityType save(CapabilityType type) {
        try {
            return toDomain(jpa.saveAndFlush(new CapabilityTypeEntity(type.id(), type.code(), type.name(),
                    type.description(), type.version(), json.write(type.schema().document()), type.createdAt())));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("CAPABILITY_TYPE_VERSION_TAKEN",
                    "Capability type " + type.code() + " version " + type.version() + " already exists");
        }
    }

    @Override
    public Optional<CapabilityType> findById(UUID id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<CapabilityType> find(String code, int version) {
        return jpa.findByCodeAndTypeVersion(code, version).map(this::toDomain);
    }

    @Override
    public Optional<CapabilityType> findLatest(String code) {
        return jpa.findFirstByCodeOrderByTypeVersionDesc(code).map(this::toDomain);
    }

    @Override
    public List<CapabilityType> findAll() {
        return jpa.findAllByOrderByCodeAscTypeVersionAsc().stream().map(this::toDomain).toList();
    }

    @Override
    public List<CapabilityType> findByCode(String code) {
        return jpa.findByCodeOrderByTypeVersionAsc(code).stream().map(this::toDomain).toList();
    }

    private CapabilityType toDomain(CapabilityTypeEntity entity) {
        return CapabilityType.restore(entity.getId(), entity.getCode(), entity.getName(), entity.getDescription(),
                entity.getTypeVersion(), CapabilitySchema.of(json.read(entity.getSchema())), entity.getCreatedAt());
    }
}
