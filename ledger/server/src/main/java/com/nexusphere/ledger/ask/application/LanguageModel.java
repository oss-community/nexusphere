package com.nexusphere.ledger.ask.application;

import com.nexusphere.ledger.chain.EvidenceEntry;

import java.util.List;

public interface LanguageModel {

    String name();

    QueryPlan plan(String question, PlanContext context);

    String answer(String question, QueryPlan plan, List<EvidenceEntry> evidence);
}
