package one.demo.challenge.level9;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Level9Order {
    private String orderId;
    private String userId;
    private BigDecimal amount;
    private Level9ProcessingMode mode;
    private Level9OrderStatus status;
    private int consumedAttempts;
    private Instant createdAt;
    private Instant lastUpdatedAt;
    private List<String> errorHistory = new ArrayList<>();

    public void addError(String error) {
        if (error == null || error.isBlank()) {
            return;
        }
        errorHistory.add(Instant.now() + " - " + error);
    }
}
