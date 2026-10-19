package com.dukankhata.subscription;

import com.dukankhata.auth.User;
import com.dukankhata.common.cache.CacheService;
import com.dukankhata.common.exception.ResourceNotFoundException;
import com.dukankhata.shop.Shop;
import com.dukankhata.shop.ShopRepository;
import com.dukankhata.subscription.dto.VerifyPaymentRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:subscription-transition;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Import(SubscriptionService.class)
@TestPropertySource(properties = {"razorpay.webhook-secret=test-webhook-secret", "subscription.trial-days=30"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SubscriptionPaymentTransitionTest {
    @Autowired SubscriptionService service;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired SubscriptionPaymentRepository payments;
    @Autowired SubscriptionPlanRepository plans;
    @Autowired ShopRepository shops;
    @MockBean WebhookEventRepository webhooks;
    @MockBean RazorpayClient razorpay;
    @MockBean CacheService cache;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void configureProvider() {
        when(razorpay.verifySignature(anyString(), anyString(), anyString())).thenReturn(true);
        when(razorpay.verifyWebhookSignature(anyString(), anyString(), anyString())).thenReturn(true);
        when(webhooks.claimEvent(any(), anyString(), anyString(), any())).thenReturn(1);
    }

    @Test
    void repeatedCheckoutVerificationDoesNotChangeAppliedPeriod() {
        Fixture f = fixture();
        stubProvider(f);

        service.verify(f.user, f.request);
        Subscription activated = subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow();
        var start = activated.getStartsAt();
        var end = activated.getEndsAt();
        service.verify(f.user, f.request);

        Subscription replayed = subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow();
        assertThat(replayed.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(replayed.getStartsAt()).isEqualTo(start);
        assertThat(replayed.getEndsAt()).isEqualTo(end);
        assertThat(payments.findByRazorpayOrderId(f.orderId).orElseThrow().getStatus()).isEqualTo("CAPTURED");
    }

    @Test
    void paymentCapturedAndOrderPaidWebhooksForSamePaymentAreIdempotent() {
        Fixture f = fixture();
        stubProvider(f);

        service.processWebhook(webhook("evt_capture", "payment.captured", f.orderId, f.paymentId), "signed");
        Subscription activated = subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow();
        var start = activated.getStartsAt();
        var end = activated.getEndsAt();
        service.processWebhook(webhookOrderPaid("evt_order_paid", f.orderId, f.paymentId), "signed");

        Subscription replayed = subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow();
        assertThat(replayed.getStartsAt()).isEqualTo(start);
        assertThat(replayed.getEndsAt()).isEqualTo(end);
    }

    @Test
    void providerAmountMismatchCannotActivateSubscription() {
        Fixture f = fixture();
        stubProvider(f);
        when(razorpay.fetchPayment(f.paymentId)).thenReturn(paymentResponse(f, 799));

        assertThatThrownBy(() -> service.verify(f.user, f.request)).isInstanceOf(IllegalArgumentException.class);
        assertThat(subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow().getStatus()).isEqualTo(SubscriptionStatus.PAYMENT_PENDING);
        assertThat(payments.findByRazorpayOrderId(f.orderId).orElseThrow().getStatus()).isEqualTo("CREATED");
    }

    @Test
    void providerOrderAmountMismatchCannotActivateSubscription() {
        Fixture f = fixture();
        when(razorpay.fetchPayment(f.paymentId)).thenReturn(paymentResponse(f, 800));
        when(razorpay.fetchOrder(f.orderId)).thenReturn(orderResponse(f).put("amount_paid", 799));

        assertThatThrownBy(() -> service.verify(f.user, f.request)).isInstanceOf(IllegalArgumentException.class);
        assertThat(subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.PAYMENT_PENDING);
    }

    @Test
    void anotherShopCannotVerifyPayment() {
        Fixture f = fixture();
        stubProvider(f);
        Shop otherShop = new Shop();
        otherShop.setName("Other shop");
        otherShop = shops.saveAndFlush(otherShop);
        User otherUser = mock(User.class);
        when(otherUser.getShop()).thenReturn(otherShop);

        assertThatThrownBy(() -> service.verify(otherUser, f.request)).isInstanceOf(IllegalStateException.class);
        assertThat(subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.PAYMENT_PENDING);
    }

    @Test
    void invalidSignatureCannotActivateSubscription() {
        Fixture f = fixture();
        stubProvider(f);
        when(razorpay.verifySignature(f.orderId, f.paymentId, "valid-signature")).thenReturn(false);

        assertThatThrownBy(() -> service.verify(f.user, f.request)).isInstanceOf(IllegalStateException.class);
        assertThat(subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.PAYMENT_PENDING);
    }

    @Test
    void unknownOrderAndMismatchedProviderIdsCannotActivateSubscription() {
        Fixture f = fixture();
        stubProvider(f);
        assertThatThrownBy(() -> service.verify(f.user,
                new VerifyPaymentRequest("order_unknown", f.paymentId, "valid-signature")))
                .isInstanceOf(ResourceNotFoundException.class);

        when(razorpay.fetchPayment("pay_wrong")).thenReturn(paymentResponse(f, 800));
        when(razorpay.fetchOrder(f.orderId)).thenReturn(orderResponse(f));
        assertThatThrownBy(() -> service.verify(f.user,
                new VerifyPaymentRequest(f.orderId, "pay_wrong", "valid-signature")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.PAYMENT_PENDING);
    }

    @Test
    void wrongProviderOrderCurrencyOrUncapturedPaymentCannotActivate() {
        Fixture f = fixture();
        ObjectNode payment = paymentResponse(f, 800).put("currency", "USD");
        when(razorpay.fetchPayment(f.paymentId)).thenReturn(payment);
        when(razorpay.fetchOrder(f.orderId)).thenReturn(orderResponse(f));
        assertThatThrownBy(() -> service.verify(f.user, f.request)).isInstanceOf(IllegalArgumentException.class);

        payment = paymentResponse(f, 800).put("status", "authorized").put("captured", false);
        when(razorpay.fetchPayment(f.paymentId)).thenReturn(payment);
        assertThatThrownBy(() -> service.verify(f.user, f.request)).isInstanceOf(IllegalArgumentException.class);

        when(razorpay.fetchPayment(f.paymentId)).thenReturn(paymentResponse(f, 800));
        when(razorpay.fetchOrder(f.orderId)).thenReturn(orderResponse(f).put("id", "order_wrong"));
        assertThatThrownBy(() -> service.verify(f.user, f.request)).isInstanceOf(IllegalArgumentException.class);
        assertThat(subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.PAYMENT_PENDING);
    }

    @Test
    void sameWebhookEventIsIgnoredOnReplay() {
        Fixture f = fixture();
        stubProvider(f);
        when(webhooks.claimEvent(any(), eq("evt_same"), anyString(), any())).thenReturn(1, 0);
        String event = webhook("evt_same", "payment.captured", f.orderId, f.paymentId);

        service.processWebhook(event, "signed");
        Subscription activated = subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow();
        var start = activated.getStartsAt();
        var end = activated.getEndsAt();
        service.processWebhook(event, "signed");

        Subscription replayed = subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow();
        assertThat(replayed.getStartsAt()).isEqualTo(start);
        assertThat(replayed.getEndsAt()).isEqualTo(end);
    }

    @Test
    void webhookCanBeRetriedAfterLocalOrderBecomesAvailable() {
        String orderId = "order_delayed_local";
        String event = webhook("evt_delayed", "payment.captured", orderId, "pay_delayed");
        assertThatThrownBy(() -> service.processWebhook(event, "signed"))
                .isInstanceOf(ResourceNotFoundException.class);

        Fixture f = fixture(orderId, "pay_delayed");
        stubProvider(f);
        service.processWebhook(event, "signed");

        assertThat(subscriptions.findByRazorpayOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void paymentFailureEventDoesNotPreventLaterValidCapture() {
        Fixture f = fixture();
        stubProvider(f);
        service.processWebhook(webhook("evt_failed", "payment.failed", f.orderId, f.paymentId), "signed");
        assertThat(subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.PAYMENT_PENDING);

        service.processWebhook(webhook("evt_success", "payment.captured", f.orderId, f.paymentId), "signed");
        assertThat(subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow().getStatus())
                .isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void renewalBeginsAfterCurrentPaidPeriod() {
        Fixture f = fixture();
        stubProvider(f);
        service.verify(f.user, f.request);
        Instant priorEnd = subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow().getEndsAt();

        SubscriptionPayment previousPayment = payments.findByRazorpayOrderId(f.orderId).orElseThrow();
        String nextOrder = "order_renewal";
        SubscriptionPayment nextPayment = new SubscriptionPayment();
        nextPayment.setShop(previousPayment.getShop());
        nextPayment.setPlan(previousPayment.getPlan());
        nextPayment.setRazorpayOrderId(nextOrder);
        nextPayment.setAmount(previousPayment.getAmount());
        nextPayment.setCurrency(previousPayment.getCurrency());
        nextPayment.setStatus("CREATED");
        payments.saveAndFlush(nextPayment);
        Subscription nextSubscription = new Subscription();
        nextSubscription.setShop(previousPayment.getShop());
        nextSubscription.setPlan(previousPayment.getPlan());
        nextSubscription.setRazorpayOrderId(nextOrder);
        nextSubscription.setStatus(SubscriptionStatus.PAYMENT_PENDING);
        subscriptions.saveAndFlush(nextSubscription);
        String nextPaymentId = "pay_renewal";
        when(razorpay.fetchPayment(nextPaymentId)).thenReturn(mapper.createObjectNode().put("id", nextPaymentId)
                .put("order_id", nextOrder).put("status", "captured").put("captured", true)
                .put("amount", 800).put("currency", "INR"));
        when(razorpay.fetchOrder(nextOrder)).thenReturn(mapper.createObjectNode().put("id", nextOrder)
                .put("status", "paid").put("amount", 800).put("amount_paid", 800).put("currency", "INR"));

        service.verify(f.user, new VerifyPaymentRequest(nextOrder, nextPaymentId, "valid-signature"));

        Subscription renewed = subscriptions.findByRazorpayOrderId(nextOrder).orElseThrow();
        assertThat(renewed.getStartsAt()).isEqualTo(priorEnd);
        assertThat(renewed.getEndsAt()).isEqualTo(priorEnd.plusSeconds(30L * 24 * 60 * 60));
    }

    @Test
    void expiredSubscriptionIsNotReportedAsActiveAndTrialDoesNotRestart() {
        Fixture f = fixture();
        Subscription expired = subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow();
        expired.setStatus(SubscriptionStatus.ACTIVE);
        expired.setStartsAt(java.time.Instant.now().minusSeconds(40L * 24 * 60 * 60));
        expired.setEndsAt(java.time.Instant.now().minusSeconds(10));
        expired.setRazorpayPaymentId(f.paymentId);
        subscriptions.saveAndFlush(expired);

        assertThat(service.current(f.user).status()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(service.current(f.user).status()).isEqualTo(SubscriptionStatus.EXPIRED);
    }

    @Test
    void pendingCheckoutDoesNotBlockAnActiveSubscription() throws Exception {
        Fixture f = fixture();
        stubProvider(f);
        service.verify(f.user, f.request);
        addPendingSubscription(f);

        assertFilterAllows(f.user);
    }

    @Test
    void pendingCheckoutDoesNotBlockAnUnexpiredTrial() throws Exception {
        Fixture f = fixture();
        addPendingSubscription(f);

        assertFilterAllows(f.user);
    }

    @Test
    void smallShopPlanCannotBePurchasedByLargeModeShop() {
        Shop shop = new Shop();
        shop.setName("Large mode shop");
        shop.setOperatingMode("LARGE");
        shop = shops.saveAndFlush(shop);
        SubscriptionPlan smallPlan = new SubscriptionPlan();
        smallPlan.setCode("SMALL_MONTHLY");
        smallPlan.setName("Small monthly");
        smallPlan.setAmount(new BigDecimal("8.00"));
        smallPlan.setCurrency("INR");
        smallPlan.setDurationDays(30);
        plans.saveAndFlush(smallPlan);
        User user = mock(User.class);
        when(user.getShop()).thenReturn(shop);

        assertThatThrownBy(() -> service.createOrder(user, "SMALL_MONTHLY"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(razorpay);
    }

    @Test
    void orderUsesServerStoredPlanPrice() {
        Shop shop = new Shop();
        shop.setName("Small mode shop");
        shop.setOperatingMode("SMALL");
        shop = shops.saveAndFlush(shop);
        String planCode = ("SMALL_MONTHLY_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8))
                .toUpperCase(java.util.Locale.ROOT);
        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setCode(planCode);
        plan.setName("Small monthly");
        plan.setAmount(new BigDecimal("150.00"));
        plan.setCurrency("INR");
        plan.setDurationDays(30);
        plans.saveAndFlush(plan);
        User user = mock(User.class);
        when(user.getShop()).thenReturn(shop);
        when(razorpay.createOrder(eq(15000L), eq("INR"), anyString())).thenAnswer(invocation ->
                mapper.createObjectNode().put("id", "order_server_price")
                        .put("amount", invocation.getArgument(0, Long.class))
                        .put("currency", invocation.getArgument(1, String.class))
                        .put("receipt", invocation.getArgument(2, String.class)));

        var response = service.createOrder(user, planCode);

        assertThat(response.amount()).isEqualByComparingTo("150.00");
        verify(razorpay).createOrder(eq(15000L), eq("INR"), anyString());
    }

    @Test
    void simultaneousVerificationRequestsApplyOnlyOnePeriod() throws Exception {
        Fixture f = fixture();
        stubProvider(f);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> verifyAtSameTime(f, ready, start));
            var second = executor.submit(() -> verifyAtSameTime(f, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        Subscription result = subscriptions.findByRazorpayOrderId(f.orderId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(result.getStartsAt()).isNotNull();
        assertThat(result.getEndsAt()).isEqualTo(result.getStartsAt().plusSeconds(30L * 24 * 60 * 60));
    }

    private void verifyAtSameTime(Fixture fixture, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test start timed out");
            service.verify(fixture.user, fixture.request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void addPendingSubscription(Fixture f) {
        Subscription pending = new Subscription();
        pending.setShop(f.user.getShop());
        pending.setPlan(plans.findAll().get(0));
        pending.setStatus(SubscriptionStatus.PAYMENT_PENDING);
        pending.setRazorpayOrderId("order_pending_" + UUID.randomUUID());
        subscriptions.saveAndFlush(pending);
    }

    private void assertFilterAllows(User user) throws Exception {
        var filter = new SubscriptionAccessFilter(subscriptions, 30);
        var request = new MockHttpServletRequest("GET", "/api/v1/products");
        var response = new MockHttpServletResponse();
        var chainCalled = new AtomicBoolean();
        FilterChain chain = (req, res) -> chainCalled.set(true);
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                user, null, java.util.List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            filter.doFilter(request, response, chain);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private Fixture fixture() {
        return fixture(null, null);
    }

    private Fixture fixture(String requestedOrderId) {
        return fixture(requestedOrderId, null);
    }

    private Fixture fixture(String requestedOrderId, String requestedPaymentId) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Shop shop = new Shop();
        shop.setName("Test shop " + suffix);
        shop = shops.saveAndFlush(shop);
        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setCode("TEST" + suffix.substring(0, 8));
        plan.setName("Test Plan");
        plan.setAmount(new BigDecimal("8.00"));
        plan.setCurrency("INR");
        plan.setDurationDays(30);
        plan = plans.saveAndFlush(plan);
        String orderId = requestedOrderId == null ? "order_" + suffix : requestedOrderId;
        String paymentId = requestedPaymentId == null ? "pay_" + suffix : requestedPaymentId;
        SubscriptionPayment payment = new SubscriptionPayment();
        payment.setShop(shop);
        payment.setPlan(plan);
        payment.setRazorpayOrderId(orderId);
        payment.setAmount(new BigDecimal("8.00"));
        payment.setCurrency("INR");
        payment.setStatus("CREATED");
        payments.saveAndFlush(payment);
        Subscription subscription = new Subscription();
        subscription.setShop(shop);
        subscription.setPlan(plan);
        subscription.setStatus(SubscriptionStatus.PAYMENT_PENDING);
        subscription.setRazorpayOrderId(orderId);
        subscriptions.saveAndFlush(subscription);
        User user = mock(User.class);
        when(user.getShop()).thenReturn(shop);
        return new Fixture(user, orderId, paymentId, new VerifyPaymentRequest(orderId, paymentId, "valid-signature"));
    }

    private void stubProvider(Fixture f) {
        when(razorpay.fetchPayment(f.paymentId)).thenReturn(paymentResponse(f, 800));
        when(razorpay.fetchOrder(f.orderId)).thenReturn(orderResponse(f));
    }

    private ObjectNode paymentResponse(Fixture f, long amount) {
        return mapper.createObjectNode().put("id", f.paymentId).put("order_id", f.orderId)
                .put("status", "captured").put("captured", true).put("amount", amount).put("currency", "INR");
    }

    private ObjectNode orderResponse(Fixture f) {
        return mapper.createObjectNode().put("id", f.orderId).put("status", "paid")
                .put("amount", 800).put("amount_paid", 800).put("currency", "INR");
    }

    private String webhook(String eventId, String event, String orderId, String paymentId) {
        return """
                {"id":"%s","event":"%s","payload":{"payment":{"entity":{"id":"%s","order_id":"%s"}}}}
                """.formatted(eventId, event, paymentId, orderId);
    }

    private String webhookOrderPaid(String eventId, String orderId, String paymentId) {
        return """
                {"id":"%s","event":"order.paid","payload":{"order":{"entity":{"id":"%s","payment_id":"%s"}}}}
                """.formatted(eventId, orderId, paymentId);
    }

    private record Fixture(User user, String orderId, String paymentId, VerifyPaymentRequest request) { }
}
