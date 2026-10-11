package com.nexusphere.ledger.server.cluster;

import com.nexusphere.ledger.server.security.Caller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cluster")
class ClusterController {

    record ClusterResponse(String instanceId, boolean leader, List<Leadership.Member> members) {
    }

    private final Leadership leadership;

    ClusterController(Leadership leadership) {
        this.leadership = leadership;
    }

    @GetMapping
    ClusterResponse cluster(Caller caller) {
        caller.requireOperator();
        return new ClusterResponse(leadership.instanceId(), leadership.isLeader(), leadership.members());
    }
}
