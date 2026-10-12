package com.mmu.schedulingapp.account;

public record AccountSummary(Long id, String username, AccountRole role, Integer operatorId, boolean enabled) {
    static AccountSummary from(UserAccount account) {
        return new AccountSummary(account.getId(), account.getUsername(), account.getRole(),
                account.getOperator() == null ? null : account.getOperator().getOperatorId(), account.isEnabled());
    }
}
