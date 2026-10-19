package com.dukankhata.security;

import com.dukankhata.auth.User;
import com.dukankhata.auth.UserRepository;
import com.dukankhata.common.exception.ResourceNotFoundException;
import com.dukankhata.common.security.CurrentUserService;
import com.dukankhata.customer.Customer;
import com.dukankhata.customer.CustomerRepository;
import com.dukankhata.customer.CustomerService;
import com.dukankhata.customer.dto.CustomerRequest;
import com.dukankhata.notification.automation.AutomatedNotificationService;
import com.dukankhata.shop.Shop;
import com.dukankhata.shop.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tenant-isolation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Import(CustomerService.class)
class CustomerTenantIsolationTest {
    @Autowired CustomerService service;
    @Autowired CustomerRepository customers;
    @Autowired ShopRepository shops;
    @Autowired UserRepository users;
    @MockBean CurrentUserService current;
    @MockBean AutomatedNotificationService notifications;

    private User ownerA;
    private User ownerB;
    private Customer customerA;
    private Customer customerB;

    @BeforeEach
    void createTwoIndependentShopsAndOwners() {
        Shop shopA = shop("Shop A");
        Shop shopB = shop("Shop B");
        ownerA = owner("9876543210", shopA);
        ownerB = owner("9876543211", shopB);
        customerA = customer("Customer A", shopA, 1);
        customerB = customer("Customer B", shopB, 1);

        when(current.shopId()).thenReturn(shopA.getId());
        when(current.requireUser()).thenReturn(ownerA);
    }

    @Test
    void ownerCanListOwnCustomersButCannotReadKnownOtherShopCustomer() {
        assertThat(service.list()).extracting("name").containsExactly("Customer A");
        assertThatThrownBy(() -> service.get(customerB.getId())).isInstanceOf(ResourceNotFoundException.class);
        assertThat(ownerB.getShop().getId()).isNotEqualTo(ownerA.getShop().getId());
    }

    @Test
    void customerCreationUsesAuthenticatedShopNotClientSuppliedShop() {
        var created = service.create(new CustomerRequest("Created by A", null, null, null, null));

        assertThat(customers.findById(created.id()).orElseThrow().getShop().getId())
                .isEqualTo(ownerA.getShop().getId());
    }

    @Test
    void ownerCannotUpdateOrDeactivateKnownOtherShopCustomer() {
        assertThatThrownBy(() -> service.update(customerB.getId(),
                new CustomerRequest("Hijacked", null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.deactivate(customerB.getId()))
                .isInstanceOf(ResourceNotFoundException.class);

        Customer unchanged = customers.findById(customerB.getId()).orElseThrow();
        assertThat(unchanged.getName()).isEqualTo("Customer B");
        assertThat(unchanged.isActive()).isTrue();
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

    private Customer customer(String name, Shop shop, int number) {
        Customer customer = new Customer();
        customer.setName(name);
        customer.setShop(shop);
        customer.setCustomerNumber(number);
        return customers.saveAndFlush(customer);
    }
}
