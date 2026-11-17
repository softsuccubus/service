package com.example.service.infrastructure.judge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 受控测试数据存储：测试点输入/输出保存在应用目录之外的可配置根目录，
 * 数据库只保存引用键。所有访问都做根目录约束，防止路径穿越读取任意文件。
 */
@Component
public class TestDataStorage {

    private final Path root;

    public TestDataStorage(@Value("${app.judge.test-data-root:./data/test-data}") String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public String write(String ref, String content) {
        Path target = resolve(ref);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
            return ref;
        } catch (IOException ex) {
            throw new UncheckedIOException("写入测试数据失败: " + ref, ex);
        }
    }

    public String read(String ref) {
        Path target = resolve(ref);
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("读取测试数据失败: " + ref, ex);
        }
    }

    public boolean exists(String ref) {
        return Files.isRegularFile(resolve(ref));
    }

    public void delete(String ref) {
        try {
            Files.deleteIfExists(resolve(ref));
        } catch (IOException ex) {
            throw new UncheckedIOException("删除测试数据失败: " + ref, ex);
        }
    }

    /** 解析并校验：结果必须仍位于根目录内。 */
    private Path resolve(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("测试数据引用不能为空");
        }
        Path target = root.resolve(ref).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法的测试数据引用: " + ref);
        }
        return target;
    }
}
