package com.dukankhata.subscription;

import com.dukankhata.auth.User;
import com.dukankhata.common.cache.CacheService;
import com.dukankhata.common.exception.ResourceNotFoundException;
import com.dukankhata.shop.Shop;
import com.dukankhata.shop.ShopRepository;
import com.dukankhata.subscription.dto.OrderResponse;
import com.dukankhata.subscription.dto.PlanResponse;
import com.dukankhata.subscription.dto.SubscriptionResponse;
import com.dukankhata.subscription.dto.VerifyPaymentRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class SubscriptionService {
    private static final String PLAN_CACHE_KEY = "dk:cache:subscription-plans:v1";
    private static final Duration PLAN_CACHE_TTL = Duration.ofMinutes(15);
    private static final TypeReference<List<PlanResponse>> PLAN_LIST_TYPE = new TypeReference<>() { };

    private final SubscriptionPlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionPaymentRepository payments;
    private final WebhookEventRepository webhooks;
    private final ShopRepository shops;
    private final RazorpayClient razorpay;
    private final CacheService cache;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int trialDays;
    private final String webhookSecret;

    public SubscriptionService(SubscriptionPlanRepository plans, SubscriptionRepository subscriptions,
                               SubscriptionPaymentRepository payments, WebhookEventRepository webhooks,
                               ShopRepository shops, RazorpayClient razorpay, CacheService cache,
                               @Value("${subscription.trial-days:30}") int trialDays,
                               @Value("${razorpay.webhook-secret:}") String webhookSecret) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.payments = payments;
        this.webhooks = webhooks;
        this.shops = shops;
        this.razorpay = razorpay;
        this.cache = cache;
        this.trialDays = trialDays;
        this.webhookSecret = webhookSecret;
    }

    @Transactional(readOnly = true)
    public List<PlanResponse> plans() {
        var cached = cache.get(PLAN_CACHE_KEY, PLAN_LIST_TYPE);
        if (cached.isPresent()) return cached.get();
        List<PlanResponse> result = plans.findAllByActiveTrueOrderByAmountAsc().stream()
                .map(plan -> new PlanResponse(plan.getId(), plan.getCode(), plan.getName(), plan.getAmount(),
                        plan.getCurrency(), plan.getDurationDays()))
                .toList();
        cache.put(PLAN_CACHE_KEY, result, PLAN_CACHE_TTL);
        return result;
    }

    @Transactional
    public OrderResponse createOrder(User user, String code) {
        SubscriptionPlan plan = plans.findByCodeAndActiveTrue(code.trim().toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("Subscription plan not found"));
        if (plan.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Subscription plan price is invalid");
        }

        Shop shop = user.getShop();
        if (!plan.supportsOperatingMode(shop.getOperatingMode())) {
            throw new IllegalArgumentException("Subscription plan is not available for this shop mode");
        }

        Instant now = Instant.now();
        boolean accessAlreadyGranted = hasCurrentEntitlement(shop, now);
        String receipt = "DK-" + shop.getId().toString().substring(0, 8) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        long paise = plan.getAmount().movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        JsonNode order = razorpay.createOrder(paise, plan.getCurrency(), receipt);
        String orderId = order.path("id").asText();
        if (orderId.isBlank()
                || order.path("amount").asLong(-1) != paise
                || !order.path("currency").asText().equalsIgnoreCase(plan.getCurrency())
                || !order.path("receipt").asText().equals(receipt)) {
            throw new IllegalStateException("Razorpay returned an invalid order response");
        }

        SubscriptionPayment payment = new SubscriptionPayment();
        payment.setShop(shop);
        payment.setPlan(plan);
        payment.setRazorpayOrderId(orderId);
        payment.setAmount(plan.getAmount());
        payment.setCurrency(plan.getCurrency());
        payment.setStatus("CREATED");
        payments.save(payment);

        subscriptions.findTopByShopIdOrderByCreatedAtDesc(shop.getId()).ifPresent(subscription -> {
            if (subscription.getStatus() == SubscriptionStatus.PAYMENT_PENDING) {
                subscription.setStatus(SubscriptionStatus.CANCELLED);
            }
        });

        Subscription subscription = new Subscription();
        subscription.setShop(shop);
        subscription.setPlan(plan);
        subscription.setStatus(SubscriptionStatus.PAYMENT_PENDING);
        subscription.setRazorpayOrderId(payment.getRazorpayOrderId());
        subscriptions.save(subscription);
        if (!accessAlreadyGranted) {
            shop.setPlanStatus("PAYMENT_PENDING");
            shops.save(shop);
            cache.evict("dk:cache:shop-settings:" + shop.getId());
        }

        return new OrderResponse(razorpay.getKeyId(), payment.getRazorpayOrderId(), plan.getAmount(),
                plan.getCurrency(), plan.getCode(), plan.getName(), plan.getDurationDays(), payment.getId());
    }

    @Transactional
    public SubscriptionResponse verify(User user, VerifyPaymentRequest request) {
        SubscriptionPayment known = payments.findByRazorpayOrderId(request.razorpayOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment order not found"));
        if (!known.getShop().getId().equals(user.getShop().getId())) {
            throw new IllegalStateException("Payment does not belong to this shop");
        }
        if (!razorpay.verifySignature(request.razorpayOrderId(), request.razorpayPaymentId(),
                request.razorpaySignature())) {
            throw new IllegalStateException("Payment signature verification failed");
        }
        JsonNode payment = razorpay.fetchPayment(request.razorpayPaymentId());
        JsonNode order = razorpay.fetchOrder(request.razorpayOrderId());
        activate(known, request.razorpayPaymentId(), payment, order, user.getShop().getId());
        return current(user);
    }

    @Transactional
    public void processWebhook(String payload, String signature) {
        if (webhookSecret.isBlank()) {
            throw new IllegalStateException("Razorpay webhook secret is not configured");
        }
        if (!razorpay.verifyWebhookSignature(payload, signature, webhookSecret)) {
            throw new IllegalStateException("Invalid webhook signature");
        }

        final JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Razorpay webhook payload", e);
        }
        String eventId = root.path("id").asText();
        String event = root.path("event").asText();
        if (eventId.isBlank()) throw new IllegalArgumentException("Webhook event id is required");
        if (webhooks.claimEvent(UUID.randomUUID(), eventId, event, Instant.now()) == 0) return;
        if (!"payment.captured".equals(event) && !"order.paid".equals(event)) return;

        JsonNode eventPayment = root.at("/payload/payment/entity");
        String orderId = eventPayment.path("order_id").asText();
        String paymentId = eventPayment.path("id").asText();
        if (orderId.isBlank()) {
            JsonNode eventOrder = root.at("/payload/order/entity");
            orderId = eventOrder.path("id").asText();
            if (paymentId.isBlank()) paymentId = eventOrder.path("payment_id").asText();
        }
        if (orderId.isBlank() || paymentId.isBlank()) {
            throw new IllegalArgumentException("Webhook payment and order identifiers are required");
        }

        SubscriptionPayment known = payments.findByRazorpayOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment order not found"));
        JsonNode payment = razorpay.fetchPayment(paymentId);
        JsonNode order = razorpay.fetchOrder(orderId);
        if (!eventPayment.path("id").asText().isBlank()
                && !paymentId.equals(eventPayment.path("id").asText())) {
            throw new IllegalArgumentException("Webhook payment does not match its event");
        }
        activate(known, paymentId, payment, order, null);
    }

    @Transactional(readOnly = true)
    public SubscriptionResponse current(User user) {
        Instant now = Instant.now();
        var active = subscriptions.findTopByShopIdAndStatusAndEndsAtAfterOrderByCreatedAtDescIdDesc(
                user.getShop().getId(), SubscriptionStatus.ACTIVE, now);
        if (active.isPresent()) return response(active.get());

        Subscription latest = subscriptions.findTopByShopIdOrderByCreatedAtDesc(user.getShop().getId())
                .orElse(null);
        if (latest == null) {
            Instant start = user.getShop().getCreatedAt();
            Instant end = start.plus(trialDays, ChronoUnit.DAYS);
            return new SubscriptionResponse("TRIAL", "Free Trial",
                    now.isBefore(end) && !subscriptions.existsByShopIdAndRazorpayPaymentIdIsNotNull(user.getShop().getId())
                            ? SubscriptionStatus.TRIAL : SubscriptionStatus.EXPIRED,
                    start, end, null);
        }
        if (latest.getStatus() == SubscriptionStatus.ACTIVE && latest.getEndsAt() != null
                && !now.isBefore(latest.getEndsAt())) {
            return new SubscriptionResponse(latest.getPlan().getCode(), latest.getPlan().getName(),
                    SubscriptionStatus.EXPIRED, latest.getStartsAt(), latest.getEndsAt(),
                    latest.getRazorpayOrderId());
        }
        return response(latest);
    }

    private void activate(SubscriptionPayment known, String paymentId, JsonNode providerPayment,
                          JsonNode providerOrder, UUID expectedShopId) {
        UUID shopId = known.getShop().getId();
        Shop shop = shops.findByIdForUpdate(shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found"));
        SubscriptionPayment payment = payments.findByRazorpayOrderIdForUpdate(known.getRazorpayOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment order not found"));
        Subscription subscription = subscriptions.findByRazorpayOrderIdForUpdate(payment.getRazorpayOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Subscription order not found"));

        if (expectedShopId != null && !expectedShopId.equals(payment.getShop().getId())) {
            throw new IllegalStateException("Payment does not belong to this shop");
        }
        if (!shopId.equals(payment.getShop().getId())
                || !shopId.equals(subscription.getShop().getId())) {
            throw new IllegalStateException("Payment shop association is invalid");
        }
        if (!Objects.equals(providerPayment.path("id").asText(), paymentId)
                || !Objects.equals(providerPayment.path("order_id").asText(), payment.getRazorpayOrderId())
                || !Objects.equals(providerOrder.path("id").asText(), payment.getRazorpayOrderId())) {
            throw new IllegalArgumentException("Provider payment/order association is invalid");
        }

        long expectedPaise = payment.getAmount().movePointRight(2)
                .setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        if (!"captured".equals(providerPayment.path("status").asText())
                || !providerPayment.path("captured").asBoolean(false)
                || providerPayment.path("amount").asLong(-1) != expectedPaise
                || !providerPayment.path("currency").asText().equalsIgnoreCase(payment.getCurrency())
                || !"paid".equals(providerOrder.path("status").asText())
                || providerOrder.path("amount").asLong(-1) != expectedPaise
                || providerOrder.path("amount_paid").asLong(-1) != expectedPaise
                || !providerOrder.path("currency").asText().equalsIgnoreCase(payment.getCurrency())) {
            throw new IllegalArgumentException("Provider payment is not captured for the expected amount and currency");
        }
        if (!payment.getPlan().getId().equals(subscription.getPlan().getId())
                || !payment.getPlan().supportsOperatingMode(shop.getOperatingMode())) {
            throw new IllegalStateException("Payment shop or plan association is invalid");
        }
        if (!"CREATED".equals(payment.getStatus())
                && !("CAPTURED".equals(payment.getStatus()) && paymentId.equals(payment.getRazorpayPaymentId()))) {
            throw new IllegalStateException("Payment is not in a payable state");
        }
        if (subscription.getStatus() == SubscriptionStatus.ACTIVE
                && paymentId.equals(subscription.getRazorpayPaymentId())
                && subscription.getStartsAt() != null && subscription.getEndsAt() != null) {
            return;
        }
        if (subscription.getStatus() != SubscriptionStatus.PAYMENT_PENDING
                && subscription.getStatus() != SubscriptionStatus.CANCELLED) {
            throw new IllegalStateException("Subscription order is not pending");
        }

        Instant now = Instant.now();
        Instant start = subscriptions.findTopByShopIdAndStatusAndEndsAtAfterOrderByCreatedAtDescIdDesc(
                        shopId, SubscriptionStatus.ACTIVE, now)
                .map(Subscription::getEndsAt)
                .orElse(now);
        if (start.isBefore(now)) start = now;
        payment.setRazorpayPaymentId(paymentId);
        payment.setStatus("CAPTURED");
        payments.save(payment);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setStartsAt(start);
        subscription.setEndsAt(start.plus(payment.getPlan().getDurationDays(), ChronoUnit.DAYS));
        subscription.setRazorpayPaymentId(paymentId);
        subscription.setRazorpayOrderId(payment.getRazorpayOrderId());
        subscriptions.save(subscription);
        shop.setPlanStatus("ACTIVE");
        shops.save(shop);
        cache.evict("dk:cache:shop-settings:" + shop.getId());
    }

    private boolean hasCurrentEntitlement(Shop shop, Instant now) {
        var active = subscriptions.findTopByShopIdAndStatusAndEndsAtAfterOrderByCreatedAtDescIdDesc(
                shop.getId(), SubscriptionStatus.ACTIVE, now);
        if (active.isPresent() && active.get().getPlan().supportsOperatingMode(shop.getOperatingMode())) {
            return true;
        }
        return now.isBefore(shop.getCreatedAt().plus(trialDays, ChronoUnit.DAYS))
                && !subscriptions.existsByShopIdAndRazorpayPaymentIdIsNotNull(shop.getId());
    }

    private SubscriptionResponse response(Subscription subscription) {
        return new SubscriptionResponse(subscription.getPlan().getCode(), subscription.getPlan().getName(),
                subscription.getStatus(), subscription.getStartsAt(), subscription.getEndsAt(),
                subscription.getRazorpayOrderId());
    }
}
