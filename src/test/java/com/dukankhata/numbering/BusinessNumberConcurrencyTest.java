package com.dukankhata.numbering;

import com.dukankhata.common.security.CurrentUserService;
import com.dukankhata.common.exception.BusinessException;
import com.dukankhata.customer.CustomerService;
import com.dukankhata.auth.User;
import com.dukankhata.auth.UserRepository;
import com.dukankhata.inventory.InventoryService;
import com.dukankhata.inventory.StockMovementRepository;
import com.dukankhata.inventory.dto.StockAdjustmentRequest;
import com.dukankhata.idempotency.IdempotencyService;
import com.dukankhata.idempotency.PostgresIdempotencyLockRepository;
import com.dukankhata.notification.automation.AutomatedNotificationService;
import com.dukankhata.pos.SaleRepository;
import com.dukankhata.pos.SaleService;
import com.dukankhata.pos.PaymentMethod;
import com.dukankhata.pos.dto.CreateSaleRequest;
import com.dukankhata.pos.dto.SaleItemRequest;
import com.dukankhata.pos.dto.SalePaymentRequest;
import com.dukankhata.purchase.PurchaseRepository;
import com.dukankhata.purchase.PurchaseService;
import com.dukankhata.purchase.dto.CreatePurchaseRequest;
import com.dukankhata.purchase.dto.PurchaseItemRequest;
import com.dukankhata.shop.Shop;
import com.dukankhata.shop.ShopRepository;
import com.dukankhata.staff.StaffRepository;
import com.dukankhata.staff.StaffService;
import com.dukankhata.product.Product;
import com.dukankhata.product.ProductRepository;
import com.dukankhata.product.ProductCacheService;
import com.dukankhata.supplier.Supplier;
import com.dukankhata.supplier.SupplierLedgerRepository;
import com.dukankhata.supplier.SupplierRepository;
import com.dukankhata.supplier.SupplierService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:business-numbering;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Import({SaleService.class, PurchaseService.class, InventoryService.class, SupplierService.class,
        IdempotencyService.class, JacksonAutoConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BusinessNumberConcurrencyTest {
    @Autowired SaleService sales;
    @Autowired PurchaseService purchases;
    @Autowired IdempotencyService idempotency;
    @Autowired InventoryService inventory;
    @Autowired SaleRepository saleRepository;
    @Autowired PurchaseRepository purchaseRepository;
    @Autowired ShopRepository shops;
    @Autowired ProductRepository products;
    @Autowired SupplierRepository suppliers;
    @Autowired SupplierLedgerRepository supplierLedger;
    @Autowired StockMovementRepository movements;
    @Autowired UserRepository users;
    @MockBean CurrentUserService current;
    @MockBean CustomerService customers;
    @MockBean StaffService staffService;
    @MockBean AutomatedNotificationService notifications;
    @MockBean ProductCacheService productCache;
    @MockBean PostgresIdempotencyLockRepository idempotencyLocks;
    @Autowired StaffRepository staff;

    private final ThreadLocal<UUID> activeShop = new ThreadLocal<>();

    @BeforeEach
    void resolveShopFromEachRequestThread() {
        when(current.shopId()).thenAnswer(invocation -> activeShop.get());
    }

    @Test
    void concurrentSalesInOneShopReceiveDistinctNumbers() throws Exception {
        Shop shop = shop("Sale concurrency");
        List<String> numbers = concurrently(
                () -> createSale(shop.getId()).invoiceNumber(),
                () -> createSale(shop.getId()).invoiceNumber());

        assertThat(numbers).containsExactlyInAnyOrder(todayPrefix("INV") + "0001", todayPrefix("INV") + "0002");
        assertThat(saleRepository.findAllByShopIdOrderByCreatedAtDescIdDesc(shop.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent()).hasSize(2);
    }

    @Test
    void concurrentSalesAcrossShopsHaveIndependentSequences() throws Exception {
        Shop shopA = shop("Sale A");
        Shop shopB = shop("Sale B");
        List<String> numbers = concurrently(
                () -> createSale(shopA.getId()).invoiceNumber(),
                () -> createSale(shopB.getId()).invoiceNumber());

        assertThat(numbers).containsExactly(todayPrefix("INV") + "0001", todayPrefix("INV") + "0001");
    }

    @Test
    void concurrentPurchasesInOneShopReceiveDistinctNumbers() throws Exception {
        Shop shop = shop("Purchase concurrency");
        Supplier supplier = supplier(shop);
        Product product = product(shop);
        List<String> numbers = concurrently(
                () -> createPurchase(shop.getId(), supplier.getId(), product.getId()).purchaseNumber(),
                () -> createPurchase(shop.getId(), supplier.getId(), product.getId()).purchaseNumber());

        assertThat(numbers).containsExactlyInAnyOrder(todayPrefix("PUR") + "0001", todayPrefix("PUR") + "0002");
        assertThat(purchaseRepository.findTop100ByShopIdOrderByCreatedAtDesc(shop.getId())).hasSize(2);
    }

    @Test
    void concurrentPurchasesAcrossShopsHaveIndependentSequences() throws Exception {
        Shop shopA = shop("Purchase A");
        Shop shopB = shop("Purchase B");
        Supplier supplierA = supplier(shopA);
        Supplier supplierB = supplier(shopB);
        Product productA = product(shopA);
        Product productB = product(shopB);
        List<String> numbers = concurrently(
                () -> createPurchase(shopA.getId(), supplierA.getId(), productA.getId()).purchaseNumber(),
                () -> createPurchase(shopB.getId(), supplierB.getId(), productB.getId()).purchaseNumber());

        assertThat(numbers).containsExactly(todayPrefix("PUR") + "0001", todayPrefix("PUR") + "0001");
    }

    @Test
    void concurrentStockOutAdjustmentsCannotOversell() throws Exception {
        Shop shop = shop("Concurrent inventory");
        Product product = product(shop);
        product.setCurrentStock(new BigDecimal("10.000"));
        products.saveAndFlush(product);

        List<String> results = concurrently(
                () -> adjustStockOut(shop.getId(), product.getId()),
                () -> adjustStockOut(shop.getId(), product.getId()));

        assertThat(results).containsExactlyInAnyOrder("success", "insufficient-stock");
        assertThat(products.findById(product.getId()).orElseThrow().getCurrentStock())
                .isEqualByComparingTo("3.000");
        assertThat(movements.findTop100ByShopIdAndProductIdOrderByCreatedAtDesc(shop.getId(), product.getId()))
                .hasSize(1);
    }

    @Test
    void failedSaleDoesNotConsumeAnInvoiceNumber() {
        Shop shop = shop("Failed sale");
        activeShop.set(shop.getId());
        try {
            var invalid = new CreateSaleRequest(null, null,
                    List.of(new SaleItemRequest(null, "Counter item", new BigDecimal("10.00"),
                            BigDecimal.ONE, null, BigDecimal.ZERO)),
                    List.of(new SalePaymentRequest(PaymentMethod.CASH, new BigDecimal("9.00"))));
            assertThatThrownBy(() -> sales.create(invalid)).isInstanceOf(BusinessException.class);
            assertThat(createSale(shop.getId()).invoiceNumber()).isEqualTo(todayPrefix("INV") + "0001");
        } finally {
            activeShop.remove();
        }
    }

    @Test
    void failedPurchaseDoesNotConsumeANumber() {
        Shop shop = shop("Failed purchase");
        Supplier supplier = supplier(shop);
        Product product = product(shop);
        activeShop.set(shop.getId());
        try {
            var invalid = new CreatePurchaseRequest(supplier.getId(), null,
                    List.of(new PurchaseItemRequest(product.getId(), BigDecimal.ONE, new BigDecimal("8.00"))),
                    new BigDecimal("9.00"), null);
            assertThatThrownBy(() -> purchases.create(invalid)).isInstanceOf(BusinessException.class);
            assertThat(createPurchase(shop.getId(), supplier.getId(), product.getId()).purchaseNumber())
                    .isEqualTo(todayPrefix("PUR") + "0001");
        } finally {
            activeShop.remove();
        }
    }

    @Test
    void replayedPurchaseRequestCreatesOnePurchaseStockMovementAndLedgerEntry() {
        Shop shop = shop("Idempotent purchase");
        Supplier supplier = supplier(shop);
        Product product = product(shop);
        User owner = new User();
        owner.setName("Test owner");
        owner.setMobile("9876543210");
        owner.setPasswordHash("test-hash-only");
        owner.setRole("OWNER");
        owner.setShop(shop);
        owner = users.saveAndFlush(owner);
        when(current.requireUser()).thenReturn(owner);
        activeShop.set(shop.getId());
        var request = new CreatePurchaseRequest(supplier.getId(), null,
                List.of(new PurchaseItemRequest(product.getId(), BigDecimal.ONE, new BigDecimal("8.00"))),
                BigDecimal.ZERO, null);
        try {
            var first = idempotency.execute("PURCHASE_CREATE", "purchase-retry-1", request,
                    com.dukankhata.purchase.dto.PurchaseResponse.class, () -> purchases.create(request));
            var replay = idempotency.execute("PURCHASE_CREATE", "purchase-retry-1", request,
                    com.dukankhata.purchase.dto.PurchaseResponse.class, () -> purchases.create(request));

            assertThat(replay.id()).isEqualTo(first.id());
            assertThat(purchaseRepository.findTop100ByShopIdOrderByCreatedAtDesc(shop.getId())).hasSize(1);
            assertThat(movements.findTop100ByShopIdAndProductIdOrderByCreatedAtDesc(shop.getId(), product.getId()))
                    .hasSize(1);
            assertThat(supplierLedger.findTop100BySupplierIdAndShopIdOrderByCreatedAtDesc(supplier.getId(), shop.getId()))
                    .hasSize(1);
            assertThat(products.findById(product.getId()).orElseThrow().getCurrentStock())
                    .isEqualByComparingTo("1.000");
            assertThat(suppliers.findById(supplier.getId()).orElseThrow().getBalancePayable())
                    .isEqualByComparingTo("8.00");
        } finally {
            activeShop.remove();
        }
    }

    @Test
    void replayedSaleRequestCreatesOneInvoice() {
        Shop shop = shop("Idempotent sale");
        User owner = owner(shop, "9876543212");
        when(current.requireUser()).thenReturn(owner);
        activeShop.set(shop.getId());
        var request = new CreateSaleRequest(null, null,
                List.of(new SaleItemRequest(null, "Counter item", new BigDecimal("10.00"),
                        BigDecimal.ONE, null, BigDecimal.ZERO)),
                List.of(new SalePaymentRequest(PaymentMethod.CASH, new BigDecimal("10.00"))));
        try {
            var first = idempotency.execute("POS_SALE_CREATE", "sale-retry-1", request,
                    com.dukankhata.pos.dto.SaleResponse.class, () -> sales.create(request));
            var replay = idempotency.execute("POS_SALE_CREATE", "sale-retry-1", request,
                    com.dukankhata.pos.dto.SaleResponse.class, () -> sales.create(request));

            assertThat(replay.id()).isEqualTo(first.id());
            assertThat(replay.invoiceNumber()).isEqualTo(first.invoiceNumber());
            assertThat(saleRepository.findAllByShopIdOrderByCreatedAtDescIdDesc(shop.getId(),
                    org.springframework.data.domain.PageRequest.of(0, 10)).getContent()).hasSize(1);
        } finally {
            activeShop.remove();
        }
    }

    private com.dukankhata.pos.dto.SaleResponse createSale(UUID shopId) {
        activeShop.set(shopId);
        try {
            var request = new CreateSaleRequest(null, null,
                    List.of(new SaleItemRequest(null, "Counter item", new BigDecimal("10.00"),
                            BigDecimal.ONE, null, BigDecimal.ZERO)),
                    List.of(new SalePaymentRequest(PaymentMethod.CASH, new BigDecimal("10.00"))));
            return sales.create(request);
        } finally {
            activeShop.remove();
        }
    }

    private String adjustStockOut(UUID shopId, UUID productId) {
        activeShop.set(shopId);
        try {
            inventory.adjust(productId, new StockAdjustmentRequest(new BigDecimal("7.000"),
                    StockAdjustmentRequest.AdjustmentDirection.OUT, "concurrency test"));
            return "success";
        } catch (BusinessException exception) {
            return "insufficient-stock";
        } finally {
            activeShop.remove();
        }
    }

    private com.dukankhata.purchase.dto.PurchaseResponse createPurchase(UUID shopId, UUID supplierId, UUID productId) {
        activeShop.set(shopId);
        try {
            var request = new CreatePurchaseRequest(supplierId, null,
                    List.of(new PurchaseItemRequest(productId, BigDecimal.ONE, new BigDecimal("8.00"))),
                    new BigDecimal("8.00"), null);
            return purchases.create(request);
        } finally {
            activeShop.remove();
        }
    }

    private List<String> concurrently(java.util.concurrent.Callable<String> first,
                                      java.util.concurrent.Callable<String> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> runTogether(first, ready, start));
            var b = executor.submit(() -> runTogether(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }

    private String runTogether(java.util.concurrent.Callable<String> action, CountDownLatch ready,
                               CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test start timed out");
        return action.call();
    }

    private Shop shop(String name) {
        Shop shop = new Shop();
        shop.setName(name);
        return shops.saveAndFlush(shop);
    }

    private Supplier supplier(Shop shop) {
        Supplier supplier = new Supplier();
        supplier.setShop(shop);
        supplier.setName("Supplier " + UUID.randomUUID());
        return suppliers.saveAndFlush(supplier);
    }

    private User owner(Shop shop, String mobile) {
        User owner = new User();
        owner.setName("Test owner");
        owner.setMobile(mobile);
        owner.setPasswordHash("test-hash-only");
        owner.setRole("OWNER");
        owner.setShop(shop);
        return users.saveAndFlush(owner);
    }

    private Product product(Shop shop) {
        Product product = new Product();
        product.setShop(shop);
        product.setName("Product " + UUID.randomUUID());
        product.setSellingPrice(new BigDecimal("10.00"));
        return products.saveAndFlush(product);
    }

    private String todayPrefix(String type) {
        return type + "-" + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
                .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + "-";
    }
}
