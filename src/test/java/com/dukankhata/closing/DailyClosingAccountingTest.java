package com.dukankhata.closing;

import com.dukankhata.auth.User;
import com.dukankhata.auth.UserRepository;
import com.dukankhata.common.security.CurrentUserService;
import com.dukankhata.customer.Customer;
import com.dukankhata.customer.CustomerLedgerEntry;
import com.dukankhata.customer.LedgerEntryType;
import com.dukankhata.customer.CustomerRepository;
import com.dukankhata.customer.CustomerService;
import com.dukankhata.customer.dto.LedgerEntryRequest;
import com.dukankhata.customer.CustomerLedgerRepository;
import com.dukankhata.finance.FinancialService;
import com.dukankhata.report.ReportService;
import com.dukankhata.report.ReportPdfService;
import com.dukankhata.notification.automation.AutomatedNotificationService;
import com.dukankhata.pos.PaymentMethod;
import com.dukankhata.pos.Sale;
import com.dukankhata.pos.SaleRepository;
import com.dukankhata.product.Product;
import com.dukankhata.product.ProductRepository;
import com.dukankhata.shop.Shop;
import com.dukankhata.shop.ShopRepository;
import com.dukankhata.closing.dto.ClosingItemRequest;
import com.dukankhata.closing.dto.ClosingRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:daily-closing-accounting;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Import({DailyClosingService.class, CustomerService.class, FinancialService.class, ReportService.class, ReportPdfService.class})
class DailyClosingAccountingTest {
    @Autowired DailyClosingService closingService;
    @Autowired CustomerService customerService;
    @Autowired FinancialService financialService;
    @Autowired ReportService reportService;
    @Autowired DailyClosingRepository closings;
    @Autowired CustomerLedgerRepository ledger;
    @Autowired CustomerRepository customers;
    @Autowired SaleRepository sales;
    @Autowired ProductRepository products;
    @Autowired ShopRepository shops;
    @Autowired UserRepository users;
    @MockBean CurrentUserService current;
    @MockBean PosClosingAggregationService posAggregation;
    @MockBean AutomatedNotificationService notifications;

    private Shop shopA;
    private User ownerA;
    private Product productA;
    private Product productB;
    private Customer customerA;
    private LocalDate businessDate;

    @BeforeEach
    void setUp() {
        shopA = shop("Shop A");
        Shop shopB = shop("Shop B");
        ownerA = owner("9876543210", shopA);
        productA = product("Rice", shopA, "100.00");
        productB = product("Tea", shopB, "25.00");
        customerA = customer("Customer A", shopA, 1);
        Customer customerB = customer("Customer B", shopB, 1);
        businessDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        when(current.shopId()).thenReturn(shopA.getId());
        when(current.requireUser()).thenReturn(ownerA);

        customerService.payment(customerA.getId(), new LedgerEntryRequest(
                new BigDecimal("10.00"), "Cash repayment", PaymentMethod.CASH));
        customerService.payment(customerA.getId(), new LedgerEntryRequest(
                new BigDecimal("5.00"), "UPI repayment", PaymentMethod.UPI));
        CustomerLedgerEntry otherShopRepayment = new CustomerLedgerEntry();
        otherShopRepayment.setShopId(shopB.getId());
        otherShopRepayment.setCustomer(customerB);
        otherShopRepayment.setType(LedgerEntryType.PAYMENT);
        otherShopRepayment.setPaymentMethod(PaymentMethod.CASH);
        otherShopRepayment.setAmount(new BigDecimal("500.00"));
        otherShopRepayment.setBalanceAfter(new BigDecimal("500.00"));
        otherShopRepayment.setDescription("Other shop repayment");
        ledger.saveAndFlush(otherShopRepayment);
    }

    @Test
    void manualClosingIncludesUdharAndShopRepaymentsWithExpectedMinusActualVariance() {
        var response = closingService.save(manualRequest());

        assertThat(response.expectedSales()).isEqualByComparingTo("100.00");
        assertThat(response.cash()).isEqualByComparingTo("20.00");
        assertThat(response.upi()).isEqualByComparingTo("30.00");
        assertThat(response.udhar()).isEqualByComparingTo("50.00");
        assertThat(response.customerRepaymentCash()).isEqualByComparingTo("10.00");
        assertThat(response.customerRepaymentUpi()).isEqualByComparingTo("5.00");
        assertThat(response.actualCollection()).isEqualByComparingTo("115.00");
        assertThat(response.variance()).isEqualByComparingTo("-15.00");
        assertThat(response.netProfit()).isEqualByComparingTo("100.00");
    }

    @Test
    void posSyncPreservesManualFieldsAndStoresSeparatePosPaymentBreakdownIdempotently() {
        var saved = closingService.save(manualRequest());
        when(posAggregation.aggregate(businessDate)).thenReturn(new PosClosingAggregationService.PosTotals(
                new BigDecimal("200.00"), new BigDecimal("100.00"),
                new BigDecimal("70.00"), new BigDecimal("30.00"), 3));

        var firstSync = closingService.syncPos(saved.id());
        var secondSync = closingService.syncPos(saved.id());

        assertThat(secondSync.cash()).isEqualByComparingTo("20.00");
        assertThat(secondSync.upi()).isEqualByComparingTo("30.00");
        assertThat(secondSync.udhar()).isEqualByComparingTo("50.00");
        assertThat(secondSync.customerRepaymentCash()).isEqualByComparingTo("10.00");
        assertThat(secondSync.customerRepaymentUpi()).isEqualByComparingTo("5.00");
        assertThat(secondSync.expectedSales()).isEqualByComparingTo("200.00");
        assertThat(secondSync.posSalesTotal()).isEqualByComparingTo("200.00");
        assertThat(secondSync.posCash()).isEqualByComparingTo("100.00");
        assertThat(secondSync.posUpi()).isEqualByComparingTo("70.00");
        assertThat(secondSync.posUdhar()).isEqualByComparingTo("30.00");
        assertThat(secondSync.actualCollection()).isEqualByComparingTo("115.00");
        assertThat(secondSync.variance()).isEqualByComparingTo("85.00");
        assertThat(closings.findById(saved.id()).orElseThrow().getCustomerRepaymentCash())
                .isEqualByComparingTo(firstSync.customerRepaymentCash());
        assertThat(ledger.findAllByCustomerIdAndShopIdOrderByCreatedAtDesc(customerA.getId(), shopA.getId()))
                .hasSize(2);

        DailyClosing closed = closings.findById(saved.id()).orElseThrow();
        closed.setStatus(ClosingStatus.CLOSED);
        closings.saveAndFlush(closed);
        var report = reportService.report(businessDate, businessDate);
        assertThat(report.summary().customerRepaymentCash()).isEqualByComparingTo("10.00");
        assertThat(report.summary().customerRepaymentUpi()).isEqualByComparingTo("5.00");
        assertThat(report.days().getFirst().posUdhar()).isEqualByComparingTo("30.00");
        assertThat(reportService.pdf(businessDate, businessDate)).isNotEmpty();
    }

    @Test
    void financialClosingAggregationExcludesPosSalesAlreadyCountedFromSales() {
        DailyClosing posClosing = new DailyClosing();
        posClosing.setShop(shopA);
        posClosing.setBusinessDate(businessDate);
        posClosing.setStatus(ClosingStatus.CLOSED);
        posClosing.setExpectedSales(new BigDecimal("200.00"));
        posClosing.setPosSalesTotal(new BigDecimal("200.00"));
        posClosing.setPosSaleCount(3L);
        posClosing.setPosAutoAggregated(true);
        closings.saveAndFlush(posClosing);

        DailyClosing manualClosing = new DailyClosing();
        manualClosing.setShop(shopA);
        manualClosing.setBusinessDate(businessDate.minusDays(1));
        manualClosing.setStatus(ClosingStatus.CLOSED);
        manualClosing.setExpectedSales(new BigDecimal("50.00"));
        closings.saveAndFlush(manualClosing);

        List<Object[]> summary = closings.summarizeByShopAndDateRange(
                shopA.getId(), businessDate.minusDays(1), businessDate, ClosingStatus.CLOSED);

        assertThat((BigDecimal) summary.getFirst()[0]).isEqualByComparingTo("50.00");
    }

    @Test
    void financialSummaryCountsPosRevenueAndManualClosingOnlyOnce() {
        Sale sale = new Sale();
        sale.setShop(shopA);
        sale.setInvoiceNumber("INV-TEST-001");
        sale.setTotal(new BigDecimal("200.00"));
        sales.saveAndFlush(sale);

        DailyClosing posClosing = new DailyClosing();
        posClosing.setShop(shopA);
        posClosing.setBusinessDate(businessDate);
        posClosing.setStatus(ClosingStatus.CLOSED);
        posClosing.setExpectedSales(new BigDecimal("200.00"));
        posClosing.setPosSalesTotal(new BigDecimal("200.00"));
        posClosing.setPosSaleCount(1L);
        posClosing.setPosAutoAggregated(true);
        closings.saveAndFlush(posClosing);

        DailyClosing manualClosing = new DailyClosing();
        manualClosing.setShop(shopA);
        manualClosing.setBusinessDate(businessDate.minusDays(1));
        manualClosing.setStatus(ClosingStatus.CLOSED);
        manualClosing.setExpectedSales(new BigDecimal("50.00"));
        closings.saveAndFlush(manualClosing);

        var result = financialService.summary(businessDate.minusDays(1), businessDate);

        assertThat(result.revenue()).isEqualByComparingTo("250.00");
        assertThat(result.salesCount()).isEqualTo(1);
    }

    @Test
    void customerRepaymentsRequireCashOrUpiTender() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> customerService.payment(customerA.getId(),
                new LedgerEntryRequest(new BigDecimal("1.00"), "invalid tender", PaymentMethod.UDHAR)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closingCannotBeCreatedWithAnotherShopsProduct() {
        ClosingRequest request = new ClosingRequest(businessDate,
                List.of(new ClosingItemRequest(productB.getId(), BigDecimal.ONE)),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> closingService.save(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Product not found");
        assertThat(closings.findByShopIdAndBusinessDate(shopA.getId(), businessDate)).isEmpty();
    }

    @Test
    void closingCannotBeReadByAnotherShop() {
        var saved = closingService.save(manualRequest());
        when(current.shopId()).thenReturn(productB.getShop().getId());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> closingService.get(saved.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Daily closing not found");
    }

    private ClosingRequest manualRequest() {
        return new ClosingRequest(businessDate,
                List.of(new ClosingItemRequest(productA.getId(), BigDecimal.ONE)),
                new BigDecimal("20.00"), new BigDecimal("30.00"), new BigDecimal("50.00"), null, null);
    }

    private Shop shop(String name) {
        Shop shop = new Shop();
        shop.setName(name);
        return shops.saveAndFlush(shop);
    }

    private User owner(String mobile, Shop shop) {
        User user = new User();
        user.setName("Owner " + mobile);
        user.setMobile(mobile);
        user.setPasswordHash("test-hash-only");
        user.setRole("OWNER");
        user.setShop(shop);
        return users.saveAndFlush(user);
    }

    private Product product(String name, Shop shop, String price) {
        Product product = new Product();
        product.setName(name);
        product.setShop(shop);
        product.setSellingPrice(new BigDecimal(price));
        product.setAverageCostPrice(new BigDecimal("60.00"));
        return products.saveAndFlush(product);
    }

    private Customer customer(String name, Shop shop, int number) {
        Customer customer = new Customer();
        customer.setName(name);
        customer.setShop(shop);
        customer.setCustomerNumber(number);
        return customers.saveAndFlush(customer);
    }
}
