package com.cqwlw.maintenance.service;

import com.cqwlw.maintenance.common.BizException;
import com.cqwlw.maintenance.entity.IdempotencyKey;
import com.cqwlw.maintenance.mapper.IdempotencyKeyMapper;
import com.cqwlw.maintenance.util.JsonUtil;
import com.cqwlw.maintenance.util.TimeUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 写接口幂等：所有写接口携带 X-Idempotency-Key（AGENTS §3）。
 * 同 key 重放直接返回首次响应（离线队列补传/网络重试语义）。
 */
@Service
public class IdempotencyService {

    private final IdempotencyKeyMapper mapper;

    public IdempotencyService(IdempotencyKeyMapper mapper) {
        this.mapper = mapper;
    }

    public Guard begin(String key, String path) {
        if (key == null || key.isEmpty()) {
            return new Guard(null, null);
        }
        IdempotencyKey exist = mapper.selectById(key);
        if (exist != null && exist.responseJson != null && !exist.responseJson.isEmpty()) {
            return new Guard(null, exist.responseJson);
        }
        if (exist != null) {
            // 首次请求仍在处理中：拒绝并发重复提交
            throw new BizException(422, "相同请求正在处理，请勿重复提交");
        }
        IdempotencyKey row = new IdempotencyKey();
        row.idemKey = key;
        row.path = path;
        row.createdAt = TimeUtil.now();
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BizException(422, "相同请求正在处理，请勿重复提交");
        }
        return new Guard(key, null);
    }

    public class Guard {
        private final String key;
        private final String replayJson;

        Guard(String key, String replayJson) {
            this.key = key;
            this.replayJson = replayJson;
        }

        public boolean replayed() {
            return replayJson != null;
        }

        public Map<String, Object> replayedResult() {
            return JsonUtil.readMap(replayJson);
        }

        public void commit(Object result) {
            if (key == null) {
                return;
            }
            IdempotencyKey row = new IdempotencyKey();
            row.idemKey = key;
            row.responseJson = JsonUtil.write(result);
            try {
                mapper.updateById(row);
            } catch (Exception ignored) {
                // 幂等落库失败不影响主流程
            }
        }
    }
}
