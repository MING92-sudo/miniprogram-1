package com.cqwlw.maintenance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cqwlw.maintenance.entity.Message;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
