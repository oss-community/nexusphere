package com.nexusphere.ledger.server.cluster;

public record LeadershipChanged(String instanceId, boolean leader) {
}
