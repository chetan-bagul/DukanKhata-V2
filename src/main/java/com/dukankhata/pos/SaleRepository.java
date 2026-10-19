package com.dukankhata.pos;
import org.springframework.data.domain.Pageable; import org.springframework.data.domain.Slice; import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param; import java.math.BigDecimal; import java.time.Instant; import java.util.*;
public interface SaleRepository extends JpaRepository<Sale,UUID> {
 Optional<Sale> findByIdAndShopId(UUID id, UUID shopId);
 @EntityGraph(attributePaths={"customer","seller"})
 Slice<Sale> findAllByShopIdOrderByCreatedAtDescIdDesc(UUID shopId, Pageable pageable);
 @Query("select coalesce(sum(s.total),0) from Sale s where s.shop.id=:shopId and s.status='COMPLETED' and s.createdAt>=:from and s.createdAt<:to") BigDecimal sumCompletedTotal(@Param("shopId") UUID shopId,@Param("from") Instant from,@Param("to") Instant to);
 @Query(value="select coalesce(sum(round(i.cost_price * i.quantity, 2)), 0) from pos_sale_items i join pos_sales s on s.id=i.sale_id where s.shop_id=:shopId and s.status='COMPLETED' and s.created_at>=:from and s.created_at<:to",nativeQuery=true) BigDecimal sumCompletedCostOfGoods(@Param("shopId") UUID shopId,@Param("from") Instant from,@Param("to") Instant to);
 @Query("select coalesce(sum(s.total),0) from Sale s where s.shop.id=:shopId and s.status='COMPLETED' and s.createdAt>=:from and s.createdAt<:to") BigDecimal sumTotal(@Param("shopId") UUID shopId,@Param("from") Instant from,@Param("to") Instant to);
 @Query("select coalesce(sum(p.amount),0) from SalePayment p where p.sale.shop.id=:shopId and p.sale.status='COMPLETED' and p.method=:method and p.sale.createdAt>=:from and p.sale.createdAt<:to") BigDecimal sumPayment(@Param("shopId") UUID shopId,@Param("method") PaymentMethod method,@Param("from") Instant from,@Param("to") Instant to);
 @Query("select max(cast(substring(s.invoiceNumber, :suffixStart) as integer)) from Sale s where s.shop.id=:shopId and s.invoiceNumber like concat(:prefix,'%')") Integer findMaxInvoiceSuffix(@Param("shopId") UUID shopId,@Param("prefix") String prefix,@Param("suffixStart") int suffixStart);
 @Query("select count(s) from Sale s where s.shop.id=:shopId and s.status='COMPLETED' and s.createdAt>=:from and s.createdAt<:to") long countCompletedByShopIdAndCreatedAtBetween(@Param("shopId") UUID shopId,@Param("from") Instant from,@Param("to") Instant to);
}
