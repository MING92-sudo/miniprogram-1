package com.cqwlw.maintenance;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** 测试用无操作事务模板：mock mapper 场景下签退落库直接同步执行 */
public final class TxTestSupport {

    private TxTestSupport() {
    }

    public static TransactionTemplate noopTx() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        });
    }

    /** 记录 commit/rollback 的模板：用于断言"回调抛异常 → 必须走 rollback"（B3 原子边界） */
    public static final class CapturingTx {
        public final TransactionTemplate template = new TransactionTemplate(new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                committed = true;
            }

            @Override
            public void rollback(TransactionStatus status) {
                rolledBack = true;
            }
        });
        public boolean committed;
        public boolean rolledBack;
    }

    public static CapturingTx capturingTx() {
        return new CapturingTx();
    }
}
