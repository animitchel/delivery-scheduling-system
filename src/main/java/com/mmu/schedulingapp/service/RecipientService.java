package com.mmu.schedulingapp.service;


import com.mmu.schedulingapp.dto.recipient.RecipientRequestDTO;
import com.mmu.schedulingapp.dto.recipient.RecipientResponseDTO;
import com.mmu.schedulingapp.mapper.RecipientMapper;
import com.mmu.schedulingapp.repository.RecipientRepository;
import com.mmu.schedulingapp.utility.GeocodingService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

@Service
public class RecipientService {

    private final RecipientRepository recipientRepository;
    private final Function<String, Optional<GeocodingService.Coordinate>> coordinateLookup;

    private final RecipientTransactions transactions;
    @Autowired
    public RecipientService(RecipientRepository recipientRepository, com.mmu.schedulingapp.utility.MapsProvider provider, RecipientTransactions transactions) {
        this.recipientRepository=recipientRepository; this.coordinateLookup=provider::coordinates; this.transactions=transactions;
    }

    public RecipientService(RecipientRepository recipientRepository) {
        this(recipientRepository, GeocodingService::getCoordinates);
    }

    RecipientService(RecipientRepository recipientRepository,
                     Function<String, Optional<GeocodingService.Coordinate>> coordinateLookup) {
        this.recipientRepository = recipientRepository;
        this.coordinateLookup = coordinateLookup;
        this.transactions = new RecipientTransactions(recipientRepository,java.time.Clock.systemUTC());
    }

    public List<RecipientResponseDTO> getAllRecipientDTOs() {
        return recipientRepository.findAll()
                .stream()
                .map(RecipientMapper::toResponseDTO)
                .toList();
    }

    public List<RecipientResponseDTO> findByIds(List<Integer> ids) {
        return recipientRepository.findAllById(ids)
                .stream()
                .map(RecipientMapper::toResponseDTO)
                .toList();
    }

    public Optional<RecipientResponseDTO> getRecipient(int id) {
        return recipientRepository.findById(id)
                .map(RecipientMapper::toResponseDTO);
    }

    @Transactional(propagation=Propagation.NEVER)
    public RecipientResponseDTO createRecipient(RecipientRequestDTO dto) {
        return create(RecipientInput.from(dto)).response();
    }
    @Transactional(propagation=Propagation.NEVER)
    public RecipientState createRecipient(com.mmu.schedulingapp.dto.recipient.RecipientWriteRequest request) {
        return create(RecipientInput.from(request));
    }
    private RecipientState create(RecipientInput input) {
        var coordinates=coordinates(input.address());
        return transactions.create(input,coordinates[0],coordinates[1]);
    }
    public Optional<RecipientState> versionedRecipient(int id) { return transactions.snapshot(id); }
    public com.mmu.schedulingapp.dto.recipient.RecipientDirectory directory(int page,int size,String search) { return transactions.directory(page,size,search); }
    @Transactional(propagation=Propagation.NEVER)
    public Optional<RecipientState> replaceRecipient(int id,com.mmu.schedulingapp.dto.recipient.RecipientWriteRequest request,String etag) {
        if(etag==null || etag.isBlank()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PRECONDITION_REQUIRED,"Read the recipient and provide its ETag in If-Match.");
        if(!etag.matches("\"[a-f0-9]{64}\"")) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Provide one strong recipient ETag in If-Match.");
        return replace(id,RecipientInput.from(request),etag);
    }
    @Transactional(propagation=Propagation.NEVER)
    public Optional<RecipientResponseDTO> updateRecipient(int id,RecipientRequestDTO dto) {
        return replace(id,RecipientInput.from(dto),null).map(RecipientState::response);
    }
    private Optional<RecipientState> replace(int id,RecipientInput input,String etag) {
        var before=transactions.snapshot(id);
        if(before.isEmpty()) return Optional.empty();
        var snapshot=before.get();
        if(etag!=null && !etag.equals(snapshot.etag())) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.PRECONDITION_FAILED,"Recipient changed. Reload before retrying.");
        var coordinates=input.address().equals(snapshot.address()==null?null:snapshot.address().strip()) ? new BigDecimal[]{snapshot.latitude(),snapshot.longitude()} : coordinates(input.address());
        return transactions.replace(snapshot,input,coordinates[0],coordinates[1]);
    }
    private BigDecimal[] coordinates(String address) {
        Optional<GeocodingService.Coordinate> result;
        try { result=coordinateLookup.apply(address); }
        catch(RuntimeException failure) { throw unavailable(); }
        if(result==null || result.isEmpty()) throw unavailable();
        double latitude=result.get().getLatitude(),longitude=result.get().getLongitude();
        if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||latitude<-90||latitude>90||longitude<-180||longitude>180) throw unavailable();
        return new BigDecimal[]{BigDecimal.valueOf(latitude).setScale(6,java.math.RoundingMode.HALF_UP),BigDecimal.valueOf(longitude).setScale(6,java.math.RoundingMode.HALF_UP)};
    }
    private org.springframework.web.server.ResponseStatusException unavailable() {
        return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Address lookup is unavailable. No recipient change was saved.");
    }

    @Transactional
    public Optional<RecipientResponseDTO> deleteRecipient(int id) {
        return recipientRepository.findById(id).map(recipient -> {
            try {
                recipientRepository.delete(recipient);
                // Resolve FK conflicts here, including a reference committed after the read.
                recipientRepository.flush();
            } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "Recipient is still referenced and cannot be deleted.");
            }
            return RecipientMapper.toResponseDTO(recipient);
        });
    }
}
