package one.demo.challenge.level6;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 库存服务（下游服务）
 */
@Slf4j
@Service
public class InventoryService {

    // 模拟库存数据库
    private final Map<String, Integer> inventoryDatabase = new ConcurrentHashMap<>();

    public InventoryService() {
        // 初始化库存
        inventoryDatabase.put("PRODUCT-001", 100);
        inventoryDatabase.put("PRODUCT-002", 50);
        inventoryDatabase.put("PRODUCT-003", 200);
    }

    /**
     * 扣减库存
     */
    public boolean deductInventory(String productId, Integer quantity) {
        Integer currentStock = inventoryDatabase.getOrDefault(productId, 0);

        if (currentStock >= quantity) {
            inventoryDatabase.put(productId, currentStock - quantity);
            log.info("📦 [库存] 扣减成功 - ProductId: {}, Quantity: {}, 剩余: {}",
                    productId, quantity, currentStock - quantity);
            return true;
        } else {
            log.warn("⚠️ [库存] 库存不足 - ProductId: {}, 需要: {}, 剩余: {}",
                    productId, quantity, currentStock);
            return false;
        }
    }

    /**
     * 恢复库存（订单取消时）
     */
    public void restoreInventory(String productId, Integer quantity) {
        Integer currentStock = inventoryDatabase.getOrDefault(productId, 0);
        inventoryDatabase.put(productId, currentStock + quantity);
        log.info("📦 [库存] 恢复成功 - ProductId: {}, Quantity: {}, 当前: {}",
                productId, quantity, currentStock + quantity);
    }

    /**
     * 查询库存
     */
    public Integer getInventory(String productId) {
        return inventoryDatabase.getOrDefault(productId, 0);
    }

    /**
     * 获取所有库存
     */
    public Map<String, Integer> getAllInventory() {
        return new ConcurrentHashMap<>(inventoryDatabase);
    }

    /**
     * 重置库存
     */
    public void reset() {
        inventoryDatabase.clear();
        inventoryDatabase.put("PRODUCT-001", 100);
        inventoryDatabase.put("PRODUCT-002", 50);
        inventoryDatabase.put("PRODUCT-003", 200);
        log.info("🔄 库存数据已重置");
    }
}
