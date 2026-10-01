package com.cqwlw.maintenance;

import com.cqwlw.maintenance.entity.ChecklistTemplate;
import com.cqwlw.maintenance.mapper.ChecklistTemplateMapper;
import com.cqwlw.maintenance.service.ChecklistTemplateSeeder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模板落库：257 行官方模板扁平化播种
 * （A76/B66/C52/D63）；表非空时幂等跳过。
 */
class ChecklistTemplateSeederTest {

    private ChecklistTemplateMapper templateMapper;
    private ChecklistTemplateSeeder seeder;

    @BeforeEach
    void setUp() {
        templateMapper = mock(ChecklistTemplateMapper.class);
        seeder = new ChecklistTemplateSeeder(templateMapper);
    }

    @Test
    void parseOfficialRowsFlattens257Rows() throws Exception {
        List<ChecklistTemplate> rows = seeder.parseOfficialRows();
        assertEquals(257, rows.size());
        assertEquals(76, rows.stream().filter(r -> "A".equals(r.appendix)).count());
        assertEquals(66, rows.stream().filter(r -> "B".equals(r.appendix)).count());
        assertEquals(52, rows.stream().filter(r -> "C".equals(r.appendix)).count());
        assertEquals(63, rows.stream().filter(r -> "D".equals(r.appendix)).count());
        rows.forEach(r -> {
            assertEquals("OFFICIAL", r.templateType);
            assertTrue(r.enabled);
            assertTrue(r.payload != null && r.payload.startsWith("{"));
        });
    }

    @Test
    void seedIsIdempotentWhenTableNotEmpty() throws Exception {
        when(templateMapper.selectCount(any())).thenReturn(257L);
        seeder.run(null);
        org.mockito.Mockito.verify(templateMapper, org.mockito.Mockito.never())
                .insert(any(ChecklistTemplate.class));
    }

    @Test
    void seedInsertsWhenTableEmpty() throws Exception {
        when(templateMapper.selectCount(any())).thenReturn(0L);
        seeder.run(null);
        org.mockito.Mockito.verify(templateMapper, org.mockito.Mockito.times(257))
                .insert(any(ChecklistTemplate.class));
    }

    @Test
    void spotCheckRepresentativeRow() throws Exception {
        // A-1-29 层门锁紧元件啮合长度
        List<ChecklistTemplate> rows = seeder.parseOfficialRows();
        ChecklistTemplate row = rows.stream()
                .filter(r -> "A".equals(r.appendix) && "A-1-29".equals(r.itemCode))
                .findFirst().orElseThrow();
        assertEquals("层门锁紧元件啮合长度", row.name);
        Map<String, Object> payload = com.cqwlw.maintenance.util.JsonUtil.readMap(row.payload);
        assertEquals("NUMERIC", String.valueOf(payload.get("judgeType")));
        assertEquals(7, ((Number) payload.get("valueMin")).intValue());
        assertEquals("mm", String.valueOf(payload.get("valueUnit")));
    }
}
