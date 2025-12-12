package one.demo.challenge.level12;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * MappedFile 队列管理器
 *
 * 核心职责：
 * 1. 管理多个 MappedFile，按文件起始偏移量排序
 * 2. 自动创建新文件（当前文件写满时）
 * 3. 根据全局偏移量定位到具体的 MappedFile
 */
@Slf4j
public class MappedFileQueue {

    // 存储路径
    private final String storePath;

    // 单个文件大小
    private final int mappedFileSize; // 1GB

    // MappedFile 列表（按起始偏移量排序）
    private final CopyOnWriteArrayList<MappedFile> mappedFiles = new CopyOnWriteArrayList<>();

    // 读写锁
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();

    // 当前写入的 MappedFile
    private volatile MappedFile currentWriteMappedFile;

    /**
     * 构造函数
     *
     * @param storePath 存储路径
     * @param mappedFileSize 单个文件大小
     */
    public MappedFileQueue(String storePath, int mappedFileSize) {
        this.storePath = storePath;
        this.mappedFileSize = mappedFileSize;

        // 确保目录存在
        File dir = new File(storePath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // 加载已有文件
        load();

        log.info("✅ MappedFileQueue 初始化完成: path={}, fileSize={}", storePath, mappedFileSize);
    }

    /**
     * 加载已有的 MappedFile
     */
    private void load() {
        File dir = new File(storePath);
        File[] files = dir.listFiles();

        if (files != null && files.length > 0) {
            // 按文件名排序（文件名是起始偏移量）
            Arrays.sort(files, (f1, f2) -> {
                long offset1 = Long.parseLong(f1.getName());
                long offset2 = Long.parseLong(f2.getName());
                return Long.compare(offset1, offset2);
            });

            // 加载每个文件
            for (File file : files) {
                if (file.length() == mappedFileSize) {
                    try {
                        MappedFile mappedFile = new MappedFile(file.getPath(), mappedFileSize);
                        mappedFiles.add(mappedFile);
                        log.info("📂 加载 MappedFile: {}", file.getName());
                    } catch (IOException e) {
                        log.error("❌ 加载 MappedFile 失败: {}", file.getName(), e);
                    }
                } else {
                    log.warn("⚠️ 跳过不完整的文件: {}, size: {}, expected: {}",
                            file.getName(), file.length(), mappedFileSize);
                    // 可以选择删除或重命名这个文件
                }
            }

            // 设置当前写入文件
            if (!mappedFiles.isEmpty()) {
                currentWriteMappedFile = mappedFiles.get(mappedFiles.size() - 1);
            }
        }
    }

    /**
     * 获取最后一个 MappedFile（用于写入）
     */
    public MappedFile getLastMappedFile() {
        return getLastMappedFile(0);
    }

    /**
     * 获取最后一个 MappedFile，如果不存在或已满则创建新的
     *
     * @param startOffset 起始偏移量（用于创建新文件）
     * @return MappedFile
     */
    public MappedFile getLastMappedFile(long startOffset) {
        MappedFile mappedFile = currentWriteMappedFile;

        // 如果当前文件为空或已满，创建新文件
        if (mappedFile == null || mappedFile.isFull()) {
            readWriteLock.writeLock().lock();
            try {
                // 双重检查
                if (currentWriteMappedFile == null || currentWriteMappedFile.isFull()) {
                    // 计算新文件的起始偏移量
                    long newOffset;
                    if (currentWriteMappedFile == null) {
                        newOffset = startOffset;
                    } else {
                        newOffset = currentWriteMappedFile.getFileFromOffset() + mappedFileSize;
                    }

                    // 创建新文件
                    String fileName = storePath + File.separator + String.format("%020d", newOffset);
                    MappedFile newMappedFile = new MappedFile(fileName, mappedFileSize);

                    mappedFiles.add(newMappedFile);
                    currentWriteMappedFile = newMappedFile;

                    log.info("📝 创建新 MappedFile: offset={}", newOffset);
                }

                mappedFile = currentWriteMappedFile;

            } catch (IOException e) {
                log.error("❌ 创建 MappedFile 失败", e);
                throw new RuntimeException(e);
            } finally {
                readWriteLock.writeLock().unlock();
            }
        }

        return mappedFile;
    }

    /**
     * 根据全局偏移量查找 MappedFile
     *
     * @param offset 全局物理偏移量
     * @return MappedFile，如果找不到返回 null
     */
    public MappedFile findMappedFileByOffset(long offset) {
        readWriteLock.readLock().lock();
        try {
            // 二分查找
            MappedFile firstMappedFile = getFirstMappedFile();
            MappedFile lastMappedFile = getLastMappedFile();

            if (firstMappedFile == null || lastMappedFile == null) {
                return null;
            }

            // 检查范围
            if (offset < firstMappedFile.getFileFromOffset() ||
                offset >= lastMappedFile.getFileFromOffset() + mappedFileSize) {
                log.warn("⚠️ 偏移量超出范围: offset={}, first={}, last={}",
                    offset, firstMappedFile.getFileFromOffset(),
                    lastMappedFile.getFileFromOffset() + mappedFileSize);
                return null;
            }

            // 计算文件索引
            int index = (int) ((offset - firstMappedFile.getFileFromOffset()) / mappedFileSize);

            if (index >= 0 && index < mappedFiles.size()) {
                MappedFile targetFile = mappedFiles.get(index);
                if (offset >= targetFile.getFileFromOffset() &&
                    offset < targetFile.getFileFromOffset() + mappedFileSize) {
                    return targetFile;
                }
            }

            // 如果计算的索引不对，遍历查找
            for (MappedFile mappedFile : mappedFiles) {
                if (offset >= mappedFile.getFileFromOffset() &&
                    offset < mappedFile.getFileFromOffset() + mappedFileSize) {
                    return mappedFile;
                }
            }

            return null;

        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * 追加消息
     *
     * @param data 消息数据
     * @return 全局物理偏移量，-1 表示失败
     */
    public long append(byte[] data) {
        MappedFile mappedFile = getLastMappedFile(0);
        if (mappedFile == null) {
            log.error("❌ 获取 MappedFile 失败");
            return -1;
        }

        return mappedFile.appendMessage(data);
    }

    /**
     * 追加消息（ByteBuffer 版本）
     *
     * @param buffer 消息缓冲区
     * @return 全局物理偏移量，-1 表示失败
     */
    public long append(ByteBuffer buffer) {
        MappedFile mappedFile = getLastMappedFile(0);
        if (mappedFile == null) {
            log.error("❌ 获取 MappedFile 失败");
            return -1;
        }

        long offset = mappedFile.appendMessage(buffer);

        // 如果当前文件已满，尝试创建新文件
        if (offset == -1) {
            mappedFile = getLastMappedFile(0);
            if (mappedFile != null) {
                offset = mappedFile.appendMessage(buffer);
            }
        }

        return offset;
    }

    /**
     * 读取数据
     *
     * @param offset 全局物理偏移量
     * @param size 读取大小
     * @return 数据缓冲区
     */
    public ByteBuffer getData(long offset, int size) {
        MappedFile mappedFile = findMappedFileByOffset(offset);
        if (mappedFile == null) {
            log.error("❌ 找不到 MappedFile: offset={}", offset);
            return null;
        }

        return mappedFile.selectMappedBuffer(offset, size);
    }

    /**
     * 刷盘
     */
    public void flush() {
        readWriteLock.readLock().lock();
        try {
            for (MappedFile mappedFile : mappedFiles) {
                mappedFile.flush();
            }
        } finally {
            readWriteLock.readLock().unlock();
        }
    }

    /**
     * 关闭所有文件
     */
    public void shutdown() {
        readWriteLock.writeLock().lock();
        try {
            for (MappedFile mappedFile : mappedFiles) {
                mappedFile.shutdown();
            }
            mappedFiles.clear();
            currentWriteMappedFile = null;

            log.info("✅ MappedFileQueue 已关闭: {}", storePath);

        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    /**
     * 获取第一个 MappedFile
     */
    public MappedFile getFirstMappedFile() {
        return mappedFiles.isEmpty() ? null : mappedFiles.get(0);
    }

    /**
     * 获取最大偏移量
     */
    public long getMaxOffset() {
        MappedFile lastMappedFile = getLastMappedFile();
        if (lastMappedFile == null) {
            return 0;
        }
        return lastMappedFile.getFileFromOffset() + lastMappedFile.getWrotePosition();
    }

    /**
     * 获取最小偏移量
     */
    public long getMinOffset() {
        MappedFile firstMappedFile = getFirstMappedFile();
        if (firstMappedFile == null) {
            return 0;
        }
        return firstMappedFile.getFileFromOffset();
    }

    // Getters

    public String getStorePath() {
        return storePath;
    }

    public int getMappedFileSize() {
        return mappedFileSize;
    }

    public int getMappedFileCount() {
        return mappedFiles.size();
    }
}
