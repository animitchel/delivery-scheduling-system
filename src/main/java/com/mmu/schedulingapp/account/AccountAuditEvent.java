package com.mmu.schedulingapp.account;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "account_audit_events")
public class AccountAuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "actor_account_id", nullable = false)
    private Long actorAccountId;
    @Column(name = "target_account_id", nullable = false)
    private Long targetAccountId;
    @Column(nullable = false, length = 32)
    private String action;
    @Column(name = "occurred_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant occurredAt;

    protected AccountAuditEvent() { }
    AccountAuditEvent(Long actor, Long target, String action, Instant time) {
        actorAccountId = actor; targetAccountId = target; this.action = action; occurredAt = time;
    }
    public Long getId() { return id; }
    public Long getActorAccountId() { return actorAccountId; }
    public Long getTargetAccountId() { return targetAccountId; }
    public String getAction() { return action; }
    public Instant getOccurredAt() { return occurredAt; }
}
