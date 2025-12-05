package one.demo.challenge.level8;

import lombok.Getter;

import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 记录每个消费者的消费情况，方便通过 HTTP 接口展示。
 */
@Getter
public class Level8ConsumerStats {

    private static final int MAX_RECENT_RECORDS = 10;

    private final String consumerName;
    private final Instant createdAt = Instant.now();
    private final AtomicInteger totalMessages = new AtomicInteger();
    private final Map<String, AtomicInteger> typeCounters = new ConcurrentHashMap<>();
    private final Deque<String> recentOrderIds = new ConcurrentLinkedDeque<>();
    private final Deque<String> recentNotes = new ConcurrentLinkedDeque<>();

    public Level8ConsumerStats(String consumerName) {
        this.consumerName = consumerName;
    }

    public void record(Level8OrderMessage message, String note) {
        totalMessages.incrementAndGet();
        typeCounters
                .computeIfAbsent(message.getOrderType().name(), key -> new AtomicInteger())
                .incrementAndGet();

        if (message.getOrderId() != null) {
            recentOrderIds.addFirst(message.getOrderId());
            trimDeque(recentOrderIds);
        }

        if (note != null && !note.isBlank()) {
            recentNotes.addFirst(note);
            trimDeque(recentNotes);
        }
    }

    public int getTotalMessages() {
        return totalMessages.get();
    }

    public Map<String, Integer> getTypeCountersSnapshot() {
        return typeCounters.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get()));
    }

    public String formatDetail() {
        StringBuilder builder = new StringBuilder();
        builder.append(String.format("👀 Consumer: %s%n", consumerName));
        builder.append(String.format("   Total Messages: %d%n", totalMessages.get()));
        builder.append("   Type Counters: ");
        if (typeCounters.isEmpty()) {
            builder.append("暂无记录");
        } else {
            builder.append(typeCounters.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue().get())
                    .collect(Collectors.joining(", ")));
        }
        builder.append(System.lineSeparator());
        builder.append("   Recent Orders: ");
        if (recentOrderIds.isEmpty()) {
            builder.append("暂无");
        } else {
            builder.append(String.join(", ", recentOrderIds));
        }
        builder.append(System.lineSeparator());
        if (!recentNotes.isEmpty()) {
            builder.append("   Notes: ");
            builder.append(String.join(" | ", recentNotes));
            builder.append(System.lineSeparator());
        }
        return builder.toString();
    }

    private void trimDeque(Deque<?> deque) {
        while (deque.size() > MAX_RECENT_RECORDS) {
            deque.removeLast();
        }
    }
}
