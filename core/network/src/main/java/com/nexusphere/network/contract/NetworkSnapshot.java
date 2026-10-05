package com.nexusphere.network.contract;

import com.nexusphere.shared.id.NetworkId;

public record NetworkSnapshot(NetworkId id, String name, boolean active) {
}
