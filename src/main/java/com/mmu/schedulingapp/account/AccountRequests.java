package com.mmu.schedulingapp.account;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.HashSet;
import java.util.Set;

public final class AccountRequests {
    private AccountRequests() { }
    public abstract static class Strict {
        private final Set<String> seen = new HashSet<>();
        protected void accept(String field) {
            if (!seen.add(field)) throw new IllegalArgumentException("Duplicate field.");
        }
        @JsonAnySetter public void rejectExtra(String field, Object value) { throw new IllegalArgumentException("Unexpected field."); }
        @Override public final String toString() { return getClass().getSimpleName() + "[REDACTED]"; }
    }
    public static class Reauthenticated extends Strict {
        @NotBlank private String currentPassword;
        @JsonProperty(value = "currentPassword", access = JsonProperty.Access.WRITE_ONLY)
        public void setCurrentPassword(String value) { accept("currentPassword"); currentPassword = value; }
        public String currentPassword() { return currentPassword; }
    }
    public static final class CreateDriver extends Reauthenticated {
        @NotBlank private String username;
        @NotNull private Integer operatorId;
        @JsonProperty public void setUsername(String value) { accept("username"); username = value; }
        @JsonProperty public void setOperatorId(Integer value) { accept("operatorId"); operatorId = value; }
        public String username() { return username; }
        public Integer operatorId() { return operatorId; }
    }
    public static final class Enabled extends Reauthenticated {
        @NotNull private Boolean enabled;
        @JsonProperty public void setEnabled(Boolean value) { accept("enabled"); enabled = value; }
        public boolean enabled() { return Boolean.TRUE.equals(enabled); }
    }
    public static final class ChangePassword extends Reauthenticated {
        @NotBlank private String newPassword;
        @JsonProperty(value = "newPassword", access = JsonProperty.Access.WRITE_ONLY)
        public void setNewPassword(String value) { accept("newPassword"); newPassword = value; }
        public String newPassword() { return newPassword; }
    }
}
