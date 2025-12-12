package one.demo.challenge.level12;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存映射文件 - 零拷贝实现
 *
 * 核心特性：
 * 1. 使用 mmap 将文件映射到内存，避免用户态/内核态切换
 * 2. 利用操作系统 PageCache，提高读写性能
 * 3. 固定文件大小，便于管理和定位
 */
@Slf4j
public class MappedFile {

    // 文件名（通常是起始偏移量，如 00000000000000000000）
    private final String fileName;

    // 文件起始偏移量（全局偏移量）
    private final long fileFromOffset;

    // 文件大小（固定）
    private final int fileSize;

    // 文件通道
    private FileChannel fileChannel;

    // 内存映射缓冲区
    private MappedByteBuffer mappedByteBuffer;

    // 当前写入位置（相对于文件开头）
    private final AtomicInteger wrotePosition = new AtomicInteger(0);

    // 当前刷盘位置
    private final AtomicInteger committedPosition = new AtomicInteger(0);

    // 是否可用
    private volatile boolean available = true;

    /**
     * 构造函数
     *
     * @param fileName 文件路径
     * @param fileSize 文件大小
     */
    public MappedFile(String fileName, int fileSize) throws IOException {
        this.fileName = fileName;
        this.fileSize = fileSize;

        // 从文件名解析起始偏移量
        File file = new File(fileName);
        this.fileFromOffset = Long.parseLong(file.getName());

        // 确保目录存在
        ensureDirOK(file.getParent());

        // 创建文件并映射到内存
        init();

        log.info("✅ MappedFile 创建成功: {}, size: {} bytes", fileName, fileSize);
    }

    /**
     * 初始化文件映射
     */
    private void init() throws IOException {
        File file = new File(fileName);

        // 打开文件通道
        RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw");
        this.fileChannel = randomAccessFile.getChannel();

        // 内存映射（零拷贝）
        this.mappedByteBuffer = fileChannel.map(
            FileChannel.MapMode.READ_WRITE,
            0,
            fileSize
        );
    }

    /**
     * 追加数据
     *
     * @param data 数据
     * @return 全局物理偏移量，-1 表示文件已满
     */
    public long appendMessage(byte[] data) {
        int currentPos = wrotePosition.get();

        // 检查空间是否足够
        if (currentPos + data.length > fileSize) {
            log.warn("⚠️ MappedFile 空间不足: currentPos={}, dataLen={}, fileSize={}",
                currentPos, data.length, fileSize);
            return -1;
        }

        // 写入数据
        ByteBuffer buffer = mappedByteBuffer.slice();
        buffer.position(currentPos);
        buffer.put(data);

        // 更新写入位置
        wrotePosition.addAndGet(data.length);

        // 返回全局物理偏移量
        return fileFromOffset + currentPos;
    }

    /**
     * 追加数据（ByteBuffer 版本）
     *
     * @param buffer 数据缓冲区
     * @return 全局物理偏移量，-1 表示文件已满
     */
    public long appendMessage(ByteBuffer buffer) {
        int currentPos = wrotePosition.get();
        int remaining = buffer.remaining();

        // 检查空间是否足够
        if (currentPos + remaining > fileSize) {
            log.warn("⚠️ MappedFile 空间不足: currentPos={}, remaining={}, fileSize={}",
                currentPos, remaining, fileSize);
            return -1;
        }

        // 写入数据
        ByteBuffer slice = mappedByteBuffer.slice();
        slice.position(currentPos);
        slice.put(buffer);

        // 更新写入位置
        wrotePosition.addAndGet(remaining);

        // 返回全局物理偏移量
        return fileFromOffset + currentPos;
    }

    /**
     * 读取数据
     *
     * @param position 全局物理偏移量
     * @param size 读取大小
     * @return 数据缓冲区
     */
    public ByteBuffer selectMappedBuffer(long position, int size) {
        // 计算相对偏移量
        int pos = (int) (position - fileFromOffset);

        // 检查范围
        if (pos < 0 || pos + size > fileSize) {
            log.error("❌ 读取位置超出范围: position={}, size={}, fileFromOffset={}, fileSize={}",
                position, size, fileFromOffset, fileSize);
            return null;
        }

        // 检查是否已写入
        if (pos + size > wrotePosition.get()) {
            log.error("❌ 读取位置超出写入位置: position={}, size={}, wrotePosition={}",
                position, size, wrotePosition.get());
            return null;
        }

        // 创建切片
        ByteBuffer slice = mappedByteBuffer.slice();
        slice.position(pos);
        slice.limit(pos + size);

        return slice.slice();
    }

    /**
     * 读取数据（返回字节数组）
     *
     * @param position 全局物理偏移量
     * @param size 读取大小
     * @return 字节数组
     */
    public byte[] selectMappedBufferBytes(long position, int size) {
        ByteBuffer buffer = selectMappedBuffer(position, size);
        if (buffer == null) {
            return null;
        }

        byte[] data = new byte[size];
        buffer.get(data);
        return data;
    }

    /**
     * 强制刷盘
     */
    public void flush() {
        int writePos = wrotePosition.get();
        int lastCommittedPos = committedPosition.get();

        if (writePos > lastCommittedPos) {
            mappedByteBuffer.force();
            committedPosition.set(writePos);
            log.debug("💾 MappedFile 刷盘: {}, position: {}", fileName, writePos);
        }
    }

    /**
     * 关闭文件
     */
    public void shutdown() {
        try {
            // 刷盘
            flush();

            // 关闭文件通道
            if (fileChannel != null) {
                fileChannel.close();
            }

            available = false;
            log.info("✅ MappedFile 已关闭: {}", fileName);

        } catch (IOException e) {
            log.error("❌ 关闭 MappedFile 失败: {}", fileName, e);
        }
    }

    /**
     * 确保目录存在
     */
    private void ensureDirOK(String dirName) {
        if (dirName != null) {
            File dir = new File(dirName);
            if (!dir.exists()) {
                dir.mkdirs();
            }
        }
    }

    /**
     * 是否已满
     */
    public boolean isFull() {
        return wrotePosition.get() >= fileSize;
    }

    /**
     * 获取剩余空间
     */
    public int getAvailableSpace() {
        return fileSize - wrotePosition.get();
    }

    // Getters

    public String getFileName() {
        return fileName;
    }

    public long getFileFromOffset() {
        return fileFromOffset;
    }

    public int getFileSize() {
        return fileSize;
    }

    public int getWrotePosition() {
        return wrotePosition.get();
    }

    public boolean isAvailable() {
        return available;
    }
}
