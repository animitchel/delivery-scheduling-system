package com.mmu.schedulingapp.service;

import com.mmu.schedulingapp.dto.recipient.RecipientDirectory;
import com.mmu.schedulingapp.model.Recipient;
import com.mmu.schedulingapp.repository.RecipientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
public class RecipientTransactions {
    private final RecipientRepository recipients;
    private final Clock clock;
    private final jakarta.persistence.EntityManager entities;
    @org.springframework.beans.factory.annotation.Autowired
    public RecipientTransactions(RecipientRepository recipients, Clock clock, jakarta.persistence.EntityManager entities) {
        this.recipients=recipients; this.clock=clock; this.entities=entities;
    }
    // Non-Spring recording repositories used by focused unit tests have no persistence context.
    public RecipientTransactions(RecipientRepository recipients, Clock clock) { this(recipients,clock,null); }
    @Transactional(readOnly=true)
    public Optional<RecipientState> snapshot(int id) { return recipients.findById(id).map(RecipientState::from); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public RecipientDirectory directory(int page,int size,String search) {
        if(page<0 || page>10000 || size<1 || size>50 || search==null || search.length()>100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid recipient directory query.");
        String pattern="%"+search.strip().toLowerCase(java.util.Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        var result=recipients.directory(pattern,PageRequest.of(page,size,Sort.by("lastName","firstName","recipientId")));
        return new RecipientDirectory(result.getContent(),page,size,result.getTotalElements(),result.getTotalPages());
    }
    @Transactional
    public RecipientState create(RecipientInput input,BigDecimal latitude,BigDecimal longitude) {
        var recipient=new Recipient(); input.apply(recipient); recipient.setLatitude(latitude); recipient.setLongitude(longitude);
        recipient.setCreatedAt(LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS));
        return RecipientState.from(recipients.save(recipient));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Optional<RecipientState> replace(RecipientState before,RecipientInput input,BigDecimal latitude,BigDecimal longitude) {
        return recipients.lockForUpdate(before.id()).map(recipient -> {
            // OSIV may retain a pre-geocoding entity in the first-level cache. A SQL lock alone
            // does not replace that state. Refresh under the held lock before comparing/writing.
            if(entities!=null) {
                entities.unwrap(org.hibernate.Session.class).setReadOnly(recipient,false);
                entities.refresh(recipient,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
            }
            if(!RecipientState.from(recipient).etag().equals(before.etag())) throw new ResponseStatusException(HttpStatus.CONFLICT,"Recipient changed while the update was being prepared. Reload before retrying.");
            input.apply(recipient); recipient.setLatitude(latitude); recipient.setLongitude(longitude);
            return RecipientState.from(recipients.save(recipient));
        });
    }
}
