package com.mmu.schedulingapp.service;

import com.mmu.schedulingapp.dto.recipient.*;
import com.mmu.schedulingapp.model.Recipient;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record RecipientInput(String firstName, String lastName, String address, String contactNumber, Recipient.PriorityLevel priority) {
    public static RecipientInput from(RecipientWriteRequest request) {
        return create(request.getFirstName(),request.getLastName(),request.getAddress(),request.getContactNumber(),request.getPriorityLevel());
    }
    public static RecipientInput from(RecipientRequestDTO request) {
        return create(request.getFirstName(),request.getLastName(),request.getAddress(),request.getContactNumber(),request.getPriorityLevel());
    }
    private static RecipientInput create(String first,String last,String address,String contact,RecipientRequestDTO.PriorityLevel priority) {
        String name=required(first,50), surname=required(last,50), location=required(address,1000);
        if((contact!=null && contact.length()>20) || priority==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid recipient fields.");
        return new RecipientInput(name,surname,location,contact==null||contact.isBlank()?null:contact.strip(),Recipient.PriorityLevel.valueOf(priority.name()));
    }
    private static String required(String value,int limit) {
        if(value==null||value.isBlank()||value.length()>limit) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid recipient fields.");
        return value.strip();
    }
    public void apply(Recipient recipient) {
        recipient.setFirstName(firstName); recipient.setLastName(lastName); recipient.setAddress(address);
        recipient.setContactNumber(contactNumber); recipient.setPriorityLevel(priority);
    }
    @Override public String toString() { return "RecipientInput[REDACTED]"; }
}
