package com.dukankhata.closing;

import com.dukankhata.shop.Shop;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "daily_closings", uniqueConstraints = @UniqueConstraint(
        name = "uk_closing_shop_date", columnNames = {"shop_id", "closing_date"}),
        indexes = @Index(name = "idx_closing_shop_date", columnList = "shop_id,closing_date"))
public class DailyClosing {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    @Column(name = "closing_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ClosingStatus status = ClosingStatus.OPEN;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal expectedSales = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal cash = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal upi = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal udhar = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal actualCollection = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal variance = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal expensesTotal = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal netProfit = BigDecimal.ZERO;

    @Column(nullable = false)
    private Boolean posAutoAggregated = false;

    @Column(nullable = false)
    private Long posSaleCount = 0L;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal posSalesTotal = BigDecimal.ZERO;

    @Column(name = "pos_cash", nullable = false, precision = 14, scale = 2)
    private BigDecimal posCash = BigDecimal.ZERO;

    @Column(name = "pos_upi", nullable = false, precision = 14, scale = 2)
    private BigDecimal posUpi = BigDecimal.ZERO;

    @Column(name = "pos_udhar", nullable = false, precision = 14, scale = 2)
    private BigDecimal posUdhar = BigDecimal.ZERO;

    @Column(name = "customer_repayment_cash", nullable = false, precision = 14, scale = 2)
    private BigDecimal customerRepaymentCash = BigDecimal.ZERO;

    @Column(name = "customer_repayment_upi", nullable = false, precision = 14, scale = 2)
    private BigDecimal customerRepaymentUpi = BigDecimal.ZERO;

    @Column(length = 500)
    private String notes;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant closedAt;

    @OneToMany(mappedBy = "closing", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DailyClosingItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "closing", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ClosingExpense> expenses = new ArrayList<>();

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Shop getShop() { return shop; }
    public LocalDate getBusinessDate() { return businessDate; }
    public ClosingStatus getStatus() { return status; }
    public BigDecimal getExpectedSales() { return expectedSales; }
    public BigDecimal getCash() { return cash; }
    public BigDecimal getUpi() { return upi; }
    public BigDecimal getUdhar() { return udhar; }
    public BigDecimal getActualCollection() { return actualCollection; }
    public BigDecimal getVariance() { return variance; }
    public BigDecimal getExpensesTotal() { return expensesTotal; }
    public BigDecimal getNetProfit() { return netProfit; }
    public Boolean getPosAutoAggregated() { return posAutoAggregated; }
    public Long getPosSaleCount() { return posSaleCount; }
    public BigDecimal getPosSalesTotal() { return posSalesTotal; }
    public BigDecimal getPosCash() { return posCash; }
    public BigDecimal getPosUpi() { return posUpi; }
    public BigDecimal getPosUdhar() { return posUdhar; }
    public BigDecimal getCustomerRepaymentCash() { return customerRepaymentCash; }
    public BigDecimal getCustomerRepaymentUpi() { return customerRepaymentUpi; }
    public String getNotes() { return notes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getClosedAt() { return closedAt; }
    public List<DailyClosingItem> getItems() { return items; }
    public List<ClosingExpense> getExpenses() { return expenses; }
    public void setShop(Shop value) { shop = value; }
    public void setBusinessDate(LocalDate value) { businessDate = value; }
    public void setStatus(ClosingStatus value) { status = value; }
    public void setExpectedSales(BigDecimal value) { expectedSales = value; }
    public void setCash(BigDecimal value) { cash = value; }
    public void setUpi(BigDecimal value) { upi = value; }
    public void setUdhar(BigDecimal value) { udhar = value; }
    public void setActualCollection(BigDecimal value) { actualCollection = value; }
    public void setVariance(BigDecimal value) { variance = value; }
    public void setExpensesTotal(BigDecimal value) { expensesTotal = value; }
    public void setNetProfit(BigDecimal value) { netProfit = value; }
    public void setPosAutoAggregated(Boolean value) { posAutoAggregated = value; }
    public void setPosSaleCount(Long value) { posSaleCount = value; }
    public void setPosSalesTotal(BigDecimal value) { posSalesTotal = value; }
    public void setPosCash(BigDecimal value) { posCash = value; }
    public void setPosUpi(BigDecimal value) { posUpi = value; }
    public void setPosUdhar(BigDecimal value) { posUdhar = value; }
    public void setCustomerRepaymentCash(BigDecimal value) { customerRepaymentCash = value; }
    public void setCustomerRepaymentUpi(BigDecimal value) { customerRepaymentUpi = value; }
    public void setNotes(String value) { notes = value; }
    public void setClosedAt(Instant value) { closedAt = value; }
}
