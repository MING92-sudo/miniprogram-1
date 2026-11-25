package com.cqwlw.maintenance.auth;

import com.cqwlw.maintenance.common.Ids;
import com.cqwlw.maintenance.entity.OpLog;
import com.cqwlw.maintenance.mapper.OpLogMapper;
import com.cqwlw.maintenance.util.TimeUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 不做失败拦截，异常由全局处理器兜底。 */
@Component
public class AuditInterceptor implements HandlerInterceptor {

    private final OpLogMapper opLogMapper;

    public AuditInterceptor(OpLogMapper opLogMapper) {
        this.opLogMapper = opLogMapper;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        String method = request.getMethod();
        if (!"POST".equals(method) && !"PUT".equals(method) && !"DELETE".equals(method)) {
            return;
        }
        try {
            OpLog log = new OpLog();
            log.id = Ids.next("op");
            log.operatorId = String.valueOf(request.getAttribute(AuthInterceptor.ATTR_EMP_ID));
            log.operatorName = null; // 列表侧按 id 反查姓名，避免拦截器额外查库
            log.method = method;
            String uri = request.getRequestURI();
            log.path = uri;
            log.action = uri.substring(0, Math.min(uri.length(), 64)); // 取前 64 字符，保住 /admin/ 等路由前缀
            log.result = ex == null ? "SUCCESS" : "FAILED";
            log.ip = request.getRemoteAddr();
            log.createdAt = TimeUtil.now();
            opLogMapper.insert(log);
        } catch (Exception ignored) {
            // 审计落库失败不影响主流程
        }
    }
}
