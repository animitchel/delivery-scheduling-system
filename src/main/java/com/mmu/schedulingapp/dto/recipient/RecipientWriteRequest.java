package com.mmu.schedulingapp.dto.recipient;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.Set;

/** Full create/replace payload: identity, coordinates and timestamps belong to the server. */
public final class RecipientWriteRequest {
    private final Set<String> seen = new HashSet<>();
    @NotBlank @Size(max=50) private String firstName;
    @NotBlank @Size(max=50) private String lastName;
    @NotBlank @Size(max=1000) private String address;
    @Size(max=20) private String contactNumber;
    @NotNull private RecipientRequestDTO.PriorityLevel priorityLevel;
    private void accept(String field) { if (!seen.add(field)) throw new IllegalArgumentException("Duplicate recipient field."); }
    @JsonAnySetter public void reject(String field, Object value) { throw new IllegalArgumentException("Unexpected recipient field."); }
    @JsonProperty @JsonDeserialize(using=RecipientWriteValues.Text.class) public void setFirstName(String value) { accept("firstName"); firstName=value; }
    @JsonProperty @JsonDeserialize(using=RecipientWriteValues.Text.class) public void setLastName(String value) { accept("lastName"); lastName=value; }
    @JsonProperty @JsonDeserialize(using=RecipientWriteValues.Text.class) public void setAddress(String value) { accept("address"); address=value; }
    @JsonProperty @JsonDeserialize(using=RecipientWriteValues.Text.class) public void setContactNumber(String value) { accept("contactNumber"); contactNumber=value; }
    @JsonProperty @JsonDeserialize(using=RecipientWriteValues.Priority.class) public void setPriorityLevel(RecipientRequestDTO.PriorityLevel value) { accept("priorityLevel"); priorityLevel=value; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getAddress() { return address; }
    public String getContactNumber() { return contactNumber; }
    public RecipientRequestDTO.PriorityLevel getPriorityLevel() { return priorityLevel; }
    @Override public String toString() { return "RecipientWriteRequest[REDACTED]"; }
}
