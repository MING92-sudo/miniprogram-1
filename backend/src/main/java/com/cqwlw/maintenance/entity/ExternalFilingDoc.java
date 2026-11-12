package com.cqwlw.maintenance.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 外接建档资料（Task 7）：doc_type 见 V29 迁移注释；missing_flag=1 表示缺件待补办，不阻断建档 */
@TableName("external_filing_doc")
@Data
public class ExternalFilingDoc {
    @TableId
    public String id;
    public String filingId;
    public String docType;
    public String fileId;
    public Boolean missingFlag;
    public String remark;
}
