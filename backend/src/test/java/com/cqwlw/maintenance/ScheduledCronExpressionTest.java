package com.cqwlw.maintenance;

import com.cqwlw.maintenance.service.DispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归闸门：`@Scheduled` 的 cron 表达式必须能被 Spring 解析。
 *
 * <p>背景：曾出现 `cron = "17 4* * * ?"` 只有 5 段，Spring CronExpression 要求 6 段
 * （秒 分 时 日 月 周），该 Bean 解析失败会抛 UnsatisfiedDependencyException 导致整个
 * Spring 上下文无法启动；而默认 mvn test 中 DB 集成测试会跳过、从不加载上下文，
 * 使该缺陷被"全绿"完全掩盖。
 */
class ScheduledCronExpressionTest {

    private static final String BASE_PACKAGE = "com.cqwlw.maintenance";

    @Test
    void everyScheduledCronExpressionCanBeParsedBySpring() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));

        Collection<BeanDefinition> candidates = scanner.findCandidateComponents(BASE_PACKAGE);
        List<String> invalid = new ArrayList<>();
        int checked = 0;

        for (BeanDefinition def : candidates) {
            Class<?> type;
            try {
                type = Class.forName(def.getBeanClassName());
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                continue;
            }
            if (type.isInterface() || type.isEnum()) {
                continue;
            }
            for (Method m : type.getDeclaredMethods()) {
                Scheduled scheduled = m.getAnnotation(Scheduled.class);
                if (scheduled == null || scheduled.cron().isEmpty()) {
                    continue;
                }
                checked++;
                try {
                    CronExpression.parse(scheduled.cron());
                } catch (IllegalArgumentException e) {
                    invalid.add(type.getSimpleName() + "#" + m.getName()
                            + " cron=\"" + scheduled.cron() + "\" -> " + e.getMessage());
                }
            }
        }

        assertTrue(checked > 0, "未扫描到任何 @Scheduled cron，扫描包路径可能已变更：" + BASE_PACKAGE);
        assertTrue(invalid.isEmpty(),
                "以下 @Scheduled cron 无法被 Spring 解析，Bean 初始化失败将导致整个应用无法启动：\n"
                        + String.join("\n", invalid));
    }

    @Test
    void dispatchSchedulesUseExplicitShanghaiZone() throws Exception {
        // 容器 JVM 默认 UTC，若不显式指定 zone，09:00 会在北京时间 17:00 才触发
        for (String name : List.of("scheduledDispatch", "topUpDispatch")) {
            Scheduled scheduled = DispatchService.class.getMethod(name).getAnnotation(Scheduled.class);
            assertEquals("Asia/Shanghai", scheduled.zone(), name + " 必须使用 Asia/Shanghai");
        }
    }
}
