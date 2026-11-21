package com.cqwlw.maintenance;

import com.cqwlw.maintenance.entity.MaintainRecord;
import com.cqwlw.maintenance.entity.Message;
import com.cqwlw.maintenance.mapper.MaintainRecordMapper;
import com.cqwlw.maintenance.mapper.MessageMapper;
import com.cqwlw.maintenance.service.ClientSignSlaService;
import com.cqwlw.maintenance.service.SignReminderService;
import com.cqwlw.maintenance.util.TimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 签字到期提醒（V56）：临期（剩余 ≤ SLA/3）与超期各至多一条（signRemindedAt 去重）；
 * 无单位快照跳过；消息写 use_unit_id 客户受众。
 */
class SignReminderServiceTest {

    private MaintainRecordMapper recordMapper;
    private MessageMapper messageMapper;
    private SignReminderService service;

    @BeforeEach
    void setUp() {
        // LambdaUpdateWrapper 需要 TableInfo 缓存（纯 Mockito 单测无 MyBatis 环境，手动初始化一次）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), MaintainRecord.class);
        recordMapper = mock(MaintainRecordMapper.class);
        messageMapper = mock(MessageMapper.class);
        // 守卫式最小更新（只盖 sign_reminded_at + 状态谓词）默认命中 1 行；
        // 「扫描间隙被签署」场景在并发测试里单独 stub 0
        when(recordMapper.update(any(), any())).thenReturn(1);
        service = new SignReminderService(recordMapper, messageMapper,
                new ClientSignSlaService(mock(com.cqwlw.maintenance.mapper.SysConfigMapper.class)));
    }

    private MaintainRecord pending(String id, String useUnitId, LocalDateTime signRequestedAt) {
        MaintainRecord r = new MaintainRecord();
        r.id = id;
        r.elevatorName = "世纪大厦 1# 客梯";
        r.workType = "半月维保";
        r.lifecycleStatus = "PENDING_CLIENT_SIGNATURE";
        r.confirmStatus = "PENDING";
        r.useUnitId = useUnitId;
        r.signRequestedAt = signRequestedAt;
        r.createdAt = signRequestedAt;
        return r;
    }

    private void givenPending(MaintainRecord... rows) {
        when(recordMapper.selectList(any())).thenReturn(List.of(rows));
    }

    @Test
    void nearDuePushesOnceAndDedups() {
        // SLA 72h：发起于 49 小时前 → 剩余 23h ≤ 24h → 临期档
        MaintainRecord r = pending("ur_1", "uu_1", TimeUtil.now().minusHours(49));
        givenPending(r);
        assertEquals(1, service.sweepOnce());
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(captor.capture());
        assertEquals("uu_1", captor.getValue().useUnitId);
        assertEquals("签字临期提醒", captor.getValue().title);
        // 第二轮：signRemindedAt 已落 → 不再重复推送
        assertEquals(0, service.sweepOnce());
        verify(messageMapper, times(1)).insert(any(Message.class));
    }

    @Test
    void overduePushesAgainAfterNearReminder() {
        // 已发临期提醒（提醒时刻早于截止），随后超期 → 再发一条超期提醒
        LocalDateTime requested = TimeUtil.now().minusHours(80);
        MaintainRecord r = pending("ur_1", "uu_1", requested);
        r.signRemindedAt = TimeUtil.now().minusHours(30); // 截止=72h 前 8 小时，提醒在截止之前
        givenPending(r);
        assertEquals(1, service.sweepOnce());
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageMapper).insert(captor.capture());
        assertEquals("签字超期提醒", captor.getValue().title);
        assertEquals(0, service.sweepOnce()); // 超期档也只发一次
        verify(messageMapper, times(1)).insert(any(Message.class));
    }

    @Test
    void skipsNotDueAndNoUnit() {
        // 刚发起 1 小时（远未到临期）+ 无单位快照的超期记录
        MaintainRecord fresh = pending("ur_1", "uu_1", TimeUtil.now().minusHours(1));
        MaintainRecord noUnit = pending("ur_2", null, TimeUtil.now().minusHours(100));
        givenPending(fresh, noUnit);
        assertEquals(0, service.sweepOnce());
        verify(messageMapper, times(0)).insert(any(Message.class));
    }

    @Test
    void skipsSignedOrOtherLifecycle() {
        givenPending(); // 查询谓词在 mapper 层，空列表即无推送
        assertEquals(0, service.sweepOnce());
        verify(messageMapper, times(0)).insert(any(Message.class));
        assertTrue(true);
    }

    /**
     * 2026-11-21 审查修复（状态撕裂）：扫描读出 PENDING 后、落提醒前客户完成签署——
     * 守卫式更新的状态谓词命中 0 行，此时既不推送消息，也绝不整实体回写
     * （旧 updateById 会把 CONFIRMED 覆盖回 PENDING）。
     */
    @Test
    void concurrentSignBetweenScanAndStampSkipsSilently() {
        MaintainRecord r = pending("ur_1", "uu_1", TimeUtil.now().minusHours(80));
        givenPending(r);
        when(recordMapper.update(any(), any())).thenReturn(0); // 签署已提交，谓词不再命中

        assertEquals(0, service.sweepOnce());

        verify(messageMapper, times(0)).insert(any(Message.class));
        verify(recordMapper, times(0)).updateById(any(MaintainRecord.class));
    }
}
