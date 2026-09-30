package com.nium.virtualcard.audit.repository;

import com.nium.virtualcard.audit.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    long countByTransactionId(UUID transactionId);
}
