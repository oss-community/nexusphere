package com.nexusphere.membership.domain.model;

import com.nexusphere.shared.error.ValidationException;

import java.util.Arrays;

public enum MembershipRole {
    MEMBER,
    ADMINISTRATOR;

    public static MembershipRole parse(String value) {
        if (value == null) {
            return MEMBER;
        }
        return Arrays.stream(values()).filter(role -> role.name().equalsIgnoreCase(value.trim())).findFirst()
                .orElseThrow(() -> new ValidationException("INVALID_MEMBERSHIP_ROLE",
                        "The role must be one of " + Arrays.toString(values())));
    }
}
