package com.dukankhata.security;

import com.dukankhata.auth.User;
import com.dukankhata.auth.UserRepository;
import com.dukankhata.common.exception.ResourceNotFoundException;
import com.dukankhata.common.security.CurrentUserService;
import com.dukankhata.finance.ExpenseCategory;
import com.dukankhata.finance.FinancialExpense;
import com.dukankhata.finance.FinancialExpenseRepository;
import com.dukankhata.finance.FinancialService;
import com.dukankhata.finance.dto.ExpenseRequest;
import com.dukankhata.notification.automation.AutomatedNotificationService;
import com.dukankhata.shop.Shop;
import com.dukankhata.shop.ShopRepository;
import com.dukankhata.supplier.Supplier;
import com.dukankhata.supplier.SupplierRepository;
import com.dukankhata.supplier.SupplierService;
import com.dukankhata.supplier.dto.SupplierPaymentRequest;
import com.dukankhata.supplier.dto.SupplierRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:shop-owned-isolation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Import({SupplierService.class, FinancialService.class})
class ShopOwnedServicesIsolationTest {
    @Autowired SupplierService suppliersService;
    @Autowired FinancialService financialService;
    @Autowired SupplierRepository suppliers;
    @Autowired FinancialExpenseRepository expenses;
    @Autowired ShopRepository shops;
    @Autowired UserRepository users;
    @MockBean CurrentUserService current;
    @MockBean AutomatedNotificationService notifications;

    private User ownerA;
    private Supplier supplierB;
    private FinancialExpense expenseB;

    @BeforeEach
    void createIndependentShops() {
        Shop shopA = shop("Shop A");
        Shop shopB = shop("Shop B");
        ownerA = owner("9876543210", shopA);
        supplierB = supplier("Supplier B", shopB);
        expenseB = expense("Expense B", shopB);
        when(current.shopId()).thenReturn(shopA.getId());
        when(current.requireUser()).thenReturn(ownerA);
    }

    @Test
    void supplierReadsAndMutationsCannotCrossShopBoundary() {
        assertThat(suppliersService.list()).isEmpty();
        assertThatThrownBy(() -> suppliersService.get(supplierB.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> suppliersService.update(supplierB.getId(), new SupplierRequest("Changed", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> suppliersService.deactivate(supplierB.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> suppliersService.pay(supplierB.getId(),
                new SupplierPaymentRequest(BigDecimal.ONE, "attempt")))
                .isInstanceOf(ResourceNotFoundException.class);

        Supplier unchanged = suppliers.findById(supplierB.getId()).orElseThrow();
        assertThat(unchanged.getName()).isEqualTo("Supplier B");
        assertThat(unchanged.isActive()).isTrue();
    }

    @Test
    void supplierCreationAndExpenseWritesUseAuthenticatedShop() {
        var createdSupplier = suppliersService.create(new SupplierRequest("Supplier A", null, null));
        var createdExpense = financialService.addExpense(new ExpenseRequest(
                LocalDate.of(2026, 10, 18), ExpenseCategory.OTHER, "Expense A", new BigDecimal("12.50"), false));

        assertThat(suppliers.findById(createdSupplier.id()).orElseThrow().getShop().getId())
                .isEqualTo(ownerA.getShop().getId());
        assertThat(expenses.findById(createdExpense.id()).orElseThrow().getShop().getId())
                .isEqualTo(ownerA.getShop().getId());
        assertThat(financialService.listExpenses(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .extracting("description").containsExactly("Expense A");
    }

    @Test
    void expenseDeleteCannotCrossShopBoundary() {
        assertThatThrownBy(() -> financialService.deleteExpense(expenseB.getId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(expenses.existsById(expenseB.getId())).isTrue();
    }

    private Shop shop(String name) {
        Shop shop = new Shop();
        shop.setName(name);
        return shops.saveAndFlush(shop);
    }

    private User owner(String mobile, Shop shop) {
        User owner = new User();
        owner.setName("Owner " + mobile);
        owner.setMobile(mobile);
        owner.setPasswordHash("test-hash-only");
        owner.setRole("OWNER");
        owner.setShop(shop);
        return users.saveAndFlush(owner);
    }

    private Supplier supplier(String name, Shop shop) {
        Supplier supplier = new Supplier();
        supplier.setName(name);
        supplier.setShop(shop);
        return suppliers.saveAndFlush(supplier);
    }

    private FinancialExpense expense(String description, Shop shop) {
        FinancialExpense expense = new FinancialExpense();
        expense.setShop(shop);
        expense.setExpenseDate(LocalDate.of(2026, 10, 18));
        expense.setCategory(ExpenseCategory.OTHER);
        expense.setDescription(description);
        expense.setAmount(BigDecimal.TEN);
        return expenses.saveAndFlush(expense);
    }
}
