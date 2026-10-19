package com.dukankhata.customer.dto;
import com.dukankhata.customer.*; import com.dukankhata.pos.PaymentMethod; import java.math.BigDecimal; import java.time.Instant; import java.util.UUID;
public record LedgerEntryResponse(UUID id,LedgerEntryType type,BigDecimal amount,BigDecimal balanceAfter,String description,String referenceType,UUID referenceId,Instant createdAt,PaymentMethod paymentMethod){
 public static LedgerEntryResponse from(CustomerLedgerEntry e){return new LedgerEntryResponse(e.getId(),e.getType(),e.getAmount(),e.getBalanceAfter(),e.getDescription(),e.getReferenceType(),e.getReferenceId(),e.getCreatedAt(),e.getPaymentMethod());}
}
