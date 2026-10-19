package com.dukankhata.customer;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.dukankhata.pos.PaymentMethod;

@Entity
@Table(name="customer_ledger_entries", indexes={
        @Index(name="idx_ledger_customer_created", columnList="customer_id,created_at"),
        @Index(name="idx_ledger_shop_created", columnList="shop_id,created_at")
})
public class CustomerLedgerEntry {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name="shop_id", nullable=false) private UUID shopId;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="customer_id", nullable=false) private Customer customer;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) private LedgerEntryType type;
    @Enumerated(EnumType.STRING) @Column(name="payment_method", length=20) private PaymentMethod paymentMethod;
    @Column(nullable=false, precision=14, scale=2) private BigDecimal amount;
    @Column(name="balance_after", nullable=false, precision=14, scale=2) private BigDecimal balanceAfter;
    @Column(length=300) private String description;
    @Column(name="reference_type", length=40) private String referenceType;
    @Column(name="reference_id") private UUID referenceId;
    @Column(nullable=false, updatable=false) private Instant createdAt;

    @PrePersist void onCreate(){createdAt=Instant.now();}
    public UUID getId(){return id;} public UUID getShopId(){return shopId;} public Customer getCustomer(){return customer;}
    public LedgerEntryType getType(){return type;} public BigDecimal getAmount(){return amount;} public BigDecimal getBalanceAfter(){return balanceAfter;}
    public PaymentMethod getPaymentMethod(){return paymentMethod;}
    public String getDescription(){return description;} public String getReferenceType(){return referenceType;} public UUID getReferenceId(){return referenceId;} public Instant getCreatedAt(){return createdAt;}
    public void setShopId(UUID v){shopId=v;} public void setCustomer(Customer v){customer=v;} public void setType(LedgerEntryType v){type=v;}
    public void setPaymentMethod(PaymentMethod v){paymentMethod=v;}
    public void setAmount(BigDecimal v){amount=v;} public void setBalanceAfter(BigDecimal v){balanceAfter=v;} public void setDescription(String v){description=v;}
    public void setReferenceType(String v){referenceType=v;} public void setReferenceId(UUID v){referenceId=v;}
}
