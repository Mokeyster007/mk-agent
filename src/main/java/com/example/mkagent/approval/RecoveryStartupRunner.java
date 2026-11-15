package com.example.mkagent.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动收尾的执行入口（批次 4B）。
 *
 * <p>只在 {@code mkagent.approval.recovery-enabled=true} 时存在；
 * 默认关闭时这个 Bean 根本不会被创建，也不会改变任何现有行为。
 *
 * <p>顺序固定为：**扫描 → 判定 → 只有确认完成才开门**。
 * 未完成（预算/行数用尽、暂时失败）时门禁保持关闭，
 * 只打 ERROR 日志并给出摘要；本批没有周期扫描，因此不存在"稍后自动补完"，
 * 需要处理后重启。选择"保持关闭"而不是"启动失败"，是为了让查询类接口仍然可用。
 *
 * <p>不抛异常中断启动：ApplicationRunner 抛异常会让整个应用退出，
 * 而这里更希望运维能看到"入口关闭 + 摘要"并保留排查能力。
 */
@Component
@ConditionalOnProperty(
        prefix = "mkagent.approval",
        name = "recovery-enabled",
        havingValue = "true"
)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RecoveryStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecoveryStartupRunner.class);

    private final RecoverySweeper sweeper;

    private final RecoveryGate gate;

    public RecoveryStartupRunner(RecoverySweeper sweeper, RecoveryGate gate) {
        this.sweeper = sweeper;
        this.gate = gate;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("启动收尾开始：执行入口在完成前保持关闭");

        RecoverySweeper.Summary summary;
        try {
            summary = sweeper.sweepOnce(RecoverySweeper.Trigger.STARTUP);
        } catch (RuntimeException e) {
            // 扫描本身崩了：绝不能顺手开门
            log.error("启动收尾异常终止，执行入口保持关闭", e);
            return;
        }

        if (summary.completed()) {
            gate.open("STARTUP_SWEEP_COMPLETED");
            log.info("启动收尾完成，执行入口已开放：{}", summary);
            return;
        }

        log.error("启动收尾未完成，执行入口保持关闭（需处理后重启）：{}", summary);
    }
}
