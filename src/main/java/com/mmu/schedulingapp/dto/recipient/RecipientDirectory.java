package com.mmu.schedulingapp.dto.recipient;

import com.mmu.schedulingapp.model.Recipient;
import java.util.List;

public record RecipientDirectory(List<Item> recipients, int page, int size, long totalElements, int totalPages) {
    public record Item(Integer recipientId, String firstName, String lastName, String address,
                       String contactNumber, Recipient.PriorityLevel priorityLevel) { }
}
