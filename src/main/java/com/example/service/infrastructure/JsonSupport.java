package com.example.service.infrastructure;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * JSON 读写辅助（Jackson 3）。仅用于配置类字段（准入规则、提交规则、语言白名单等），
 * 不用于源代码或隐藏测试数据。
 */
@Component
public class JsonSupport {

    private final JsonMapper mapper;

    public JsonSupport(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    public <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    public List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    public List<Long> readLongList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, Long.class));
    }

    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(json, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
    }
}
