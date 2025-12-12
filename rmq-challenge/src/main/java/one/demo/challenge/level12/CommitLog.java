package one.demo.challenge.level12;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * CommitLog - 所有消息统一存储
 *
 * 核心特性：
 * 1. 所有 Topic 的消息都写入同一个 CommitLog
 * 2. 顺序追加写入（Append Only），性能最优
 * 3. 使用 MappedFileQueue 管理多个 1GB 文件
 * 4. 消息格式：消息长度(4) + 消息体(JSON)
 */
@Slf4j
public class CommitLog {

    // 单个文件大小：1GB
    private static final int MAPPED_FILE_SIZE = 1024 * 1024 * 1024;

    // 存储路径
    private final String storePath;

    // MappedFile 队列
    private final MappedFileQueue mappedFileQueue;

    // JSON 序列化
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造函数
     *
     * @param storePath 存储路径
     */
    public CommitLog(String storePath) {
        this.storePath = storePath + "/commitlog";
        this.mappedFileQueue = new MappedFileQueue(this.storePath, MAPPED_FILE_SIZE);

        log.info("✅ CommitLog 初始化完成: {}", this.storePath);
    }

    /**
     * 追加消息
     *
     * @param message 消息
     * @return 追加结果
     */
    public AppendMessageResult appendMessage(Level12Message message) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. 序列化消息
            String json = objectMapper.writeValueAsString(message);
            byte[] bodyBytes = json.getBytes(StandardCharsets.UTF_8);

            // 2. 构建消息格式：消息长度(4) + 消息体
            int totalLength = 4 + bodyBytes.length;
            ByteBuffer buffer = ByteBuffer.allocate(totalLength);
            buffer.putInt(bodyBytes.length);
            buffer.put(bodyBytes);
            buffer.flip();

            // 3. 写入 CommitLog（顺序追加）
            long physicalOffset = mappedFileQueue.append(buffer);

            if (physicalOffset == -1) {
                log.error("❌ CommitLog 写入失败");
                return new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR);
            }

            // 4. 更新消息的物理偏移量
            message.setPhysicalOffset(physicalOffset);
            message.setStoreTime(System.currentTimeMillis());

            long costTime = System.currentTimeMillis() - startTime;

            log.debug("📝 CommitLog 写入成功: offset={}, size={}, cost={}ms",
                physicalOffset, totalLength, costTime);

            return new AppendMessageResult(
                AppendMessageStatus.PUT_OK,
                physicalOffset,
                totalLength,
                message.getMessageId()
            );

        } catch (Exception e) {
            log.error("❌ CommitLog 追加消息失败", e);
            return new AppendMessageResult(AppendMessageStatus.UNKNOWN_ERROR);
        }
    }

    /**
     * 读取消息
     *
     * @param offset 物理偏移量
     * @return 消息
     */
    public Level12Message getMessage(long offset) {
        try {
            // 1. 读取消息长度（4 字节）
            ByteBuffer lengthBuffer = mappedFileQueue.getData(offset, 4);
            if (lengthBuffer == null) {
                log.error("❌ 读取消息长度失败: offset={}", offset);
                return null;
            }

            int length = lengthBuffer.getInt();

            // 2. 读取消息体
            ByteBuffer bodyBuffer = mappedFileQueue.getData(offset + 4, length);
            if (bodyBuffer == null) {
                log.error("❌ 读取消息体失败: offset={}, length={}", offset, length);
                return null;
            }

            // 3. 反序列化
            byte[] bodyBytes = new byte[length];
            bodyBuffer.get(bodyBytes);
            String json = new String(bodyBytes, StandardCharsets.UTF_8);

            Level12Message message = objectMapper.readValue(json, Level12Message.class);

            log.debug("📖 CommitLog 读取成功: offset={}, messageId={}", offset, message.getMessageId());

            return message;

        } catch (Exception e) {
            log.error("❌ CommitLog 读取消息失败: offset={}", offset, e);
            return null;
        }
    }

    /**
     * 刷盘
     */
    public void flush() {
        mappedFileQueue.flush();
    }

    /**
     * 关闭
     */
    public void shutdown() {
        mappedFileQueue.shutdown();
        log.info("✅ CommitLog 已关闭");
    }

    /**
     * 获取最大偏移量
     */
    public long getMaxOffset() {
        return mappedFileQueue.getMaxOffset();
    }

    /**
     * 获取最小偏移量
     */
    public long getMinOffset() {
        return mappedFileQueue.getMinOffset();
    }

    // ==================== 内部类 ====================

    /**
     * 追加消息结果
     */
    @Data
    public static class AppendMessageResult {
        private AppendMessageStatus status;
        private long physicalOffset;
        private int wroteBytes;
        private String messageId;

        public AppendMessageResult(AppendMessageStatus status) {
            this.status = status;
        }

        public AppendMessageResult(AppendMessageStatus status, long physicalOffset,
                                   int wroteBytes, String messageId) {
            this.status = status;
            this.physicalOffset = physicalOffset;
            this.wroteBytes = wroteBytes;
            this.messageId = messageId;
        }

        public boolean isOk() {
            return status == AppendMessageStatus.PUT_OK;
        }
    }

    /**
     * 追加消息状态
     */
    public enum AppendMessageStatus {
        PUT_OK,
        END_OF_FILE,
        MESSAGE_SIZE_EXCEEDED,
        PROPERTIES_SIZE_EXCEEDED,
        UNKNOWN_ERROR
    }
}
