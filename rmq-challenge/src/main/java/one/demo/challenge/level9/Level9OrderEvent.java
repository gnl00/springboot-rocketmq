package one.demo.challenge.level9;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Level9OrderEvent {
    private String orderId;
    private String userId;
    private BigDecimal amount;
    private Level9ProcessingMode mode;
}
