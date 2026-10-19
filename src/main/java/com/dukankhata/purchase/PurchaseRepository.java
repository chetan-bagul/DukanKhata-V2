package com.dukankhata.purchase;
import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Query; import org.springframework.data.repository.query.Param; import java.util.*;
public interface PurchaseRepository extends JpaRepository<Purchase,UUID>{
 List<Purchase> findTop100ByShopIdOrderByCreatedAtDesc(UUID shopId);
 Optional<Purchase> findByIdAndShopId(UUID id,UUID shopId);
 @Query("select max(cast(substring(p.purchaseNumber, :suffixStart) as integer)) from Purchase p where p.shop.id=:shopId and p.purchaseNumber like concat(:prefix,'%')")
 Integer findMaxPurchaseSuffix(@Param("shopId") UUID shopId,@Param("prefix") String prefix,@Param("suffixStart") int suffixStart);
}
