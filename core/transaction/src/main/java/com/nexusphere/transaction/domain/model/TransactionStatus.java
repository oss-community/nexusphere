package com.nexusphere.transaction.domain.model;

public enum TransactionStatus {
    REQUESTED,
    AUTHORIZED,
    EXECUTING,
    COMPLETED,
    REJECTED,
    FAILED,
    CANCELLED
}
