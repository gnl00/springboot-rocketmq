package one.demo.challenge.level3;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体类
 * 用于模拟订单支付业务场景
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    /**
     * 订单ID
     */
    private String orderId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 订单金额
     */
    private BigDecimal amount;

    /**
     * 订单状态：CREATED(已创建), PAID(已支付), CANCELLED(已取消)
     */
    private String status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 支付时间
     */
    private LocalDateTime payTime;
}