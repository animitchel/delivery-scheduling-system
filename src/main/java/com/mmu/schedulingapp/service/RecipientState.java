package com.mmu.schedulingapp.service;

import com.mmu.schedulingapp.dto.recipient.*;
import com.mmu.schedulingapp.model.Recipient;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.io.*;

/** Immutable snapshot shared by conditional HTTP reads and guarded commits. */
public record RecipientState(Integer id, String firstName, String lastName, String address, BigDecimal latitude,
                             BigDecimal longitude, String contactNumber, Recipient.PriorityLevel priority, LocalDateTime createdAt) {
    public static RecipientState from(Recipient recipient) {
        return new RecipientState(recipient.getRecipientId(),recipient.getFirstName(),recipient.getLastName(),recipient.getAddress(),
                recipient.getLatitude(),recipient.getLongitude(),recipient.getContactNumber(),recipient.getPriorityLevel(),recipient.getCreatedAt());
    }
    public RecipientResponseDTO response() {
        return RecipientResponseDTO.builder().recipientId(id).firstName(firstName).lastName(lastName).address(address)
                .latitude(latitude).longitude(longitude).contactNumber(contactNumber)
                .priorityLevel(RecipientResponseDTO.PriorityLevel.valueOf(priority.name())).createdAt(createdAt).build();
    }
    public String etag() {
        try {
            var bytes=new ByteArrayOutputStream(); var output=new DataOutputStream(bytes);
            for (String field:new String[]{"recipient-v1",String.valueOf(id),firstName,lastName,address,number(latitude),number(longitude),contactNumber,priority.name(),String.valueOf(createdAt)}) {
                output.writeBoolean(field!=null);
                if(field!=null) { var encoded=field.getBytes(StandardCharsets.UTF_8); output.writeInt(encoded.length); output.write(encoded); }
            }
            return "\""+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))+"\"";
        } catch (java.security.NoSuchAlgorithmException | IOException failure) { throw new IllegalStateException("Recipient version could not be computed."); }
    }
    @Override public String toString() { return "RecipientState[REDACTED]"; }
    private static String number(BigDecimal number) { return number==null?null:number.stripTrailingZeros().toPlainString(); }
}
