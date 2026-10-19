package com.dukankhata.customer.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import com.dukankhata.pos.PaymentMethod;
public record LedgerEntryRequest(
    @NotNull(message="Amount is required") @DecimalMin(value="0.01",message="Amount must be greater than 0") @Digits(integer=12,fraction=2,message="Amount must have at most 2 decimal places") BigDecimal amount,
    @Size(max=300,message="Description must not exceed 300 characters") String description,
    PaymentMethod paymentMethod
) {
    public LedgerEntryRequest(BigDecimal amount, String description) {
        this(amount, description, null);
    }
}
