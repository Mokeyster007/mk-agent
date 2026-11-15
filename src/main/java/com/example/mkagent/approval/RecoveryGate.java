package com.example.mkagent.approval;

import com.example.mkagent.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 启动收尾期间的执行入口门禁（批次 4B）。
 *
 * <h2>为什么必须有它</h2>
 *
 * 启动收尾要做的事情是"把上一进程遗留的 CLAIMED / RUNNING 记录收掉"。
 * 如果一边收、一边允许新请求创建或推进任务，就会出现两类真实危险：
 *
 * <ol>
 *   <li>刚被创建的任务恰好落在扫描窗口里，被当成"遗留记录"收掉；</li>
 *   <li>新执行与扫描器争同一条 run，出现"扫描判定中断、执行随后写成功"的错位。</li>
 * </ol>
 *
 * 因此本类提供**一个**语义：收尾没有确定结束之前，不允许任何会创建或推进执行的入口通过。
 * 它不是"查询时看本地注册表有没有句柄"的替代品，而是那条判断的**前提**：
 * 门禁关着，本进程就不可能存在正在执行的任务，注册表为空才有意义。
 *
 * <h2>边界（不要说过头）</h2>
 *
 * <ul>
 *   <li>它只约束**本进程**的入口。它不能证明其他实例已经退出——</li>
 *   <li>它不提供分布式租约，也不是数据库级的全局锁；</li>
 *   <li>它不能代替部署约定：单实例独占同一个业务库仍是启用扫描的前提。</li>
 * </ul>
 *
 * <h2>状态</h2>
 *
 * 启用扫描时，本 Bean 在**容器启动期**就是 {@code CLOSED}（构造即关闭），
 * 因此 Web 端口开始接收请求时门禁已经生效，不存在"先放进来再关"的窗口。
 * 收尾成功才 {@link #open(String)}；收尾未完成或失败则保持关闭，
 * 只有查询类接口不受影响。
 *
 * 扫描关闭（默认）时构造即 {@code OPEN}，本类不改变任何现有行为。
 */
@Component
public class RecoveryGate {

    private static final Logger log = LoggerFactory.getLogger(RecoveryGate.class);

    /**
     * 门禁关闭时对外返回的稳定原因码。
     */
    public static final String CODE = "RECOVERY_IN_PROGRESS";

    private static final String MESSAGE = "服务正在启动收尾，请稍后重试";

    private final AtomicReference<State> state = new AtomicReference<>();

    private volatile String reason;

    public RecoveryGate(ApprovalProperties properties) {
        boolean enabled = properties != null && properties.isRecoveryEnabled();
        this.state.set(enabled ? State.CLOSED : State.OPEN);
        this.reason = enabled ? "STARTUP_SWEEP_PENDING" : "DISABLED";
        if (enabled) {
            log.info("启动收尾门禁已生效：执行入口在收尾完成前保持关闭");
        }
    }

    /**
     * 执行入口统一调用点：关闭时抛 503，绝不"先放进来再判断"。
     */
    public void requireOpen() {
        if (state.get() == State.CLOSED) {
            throw new BusinessException(503, CODE, MESSAGE);
        }
    }

    public boolean isOpen() {
        return state.get() == State.OPEN;
    }

    public String reason() {
        return reason;
    }

    /**
     * 关闭门禁。启动期由构造器完成；测试用它复现"收尾进行中"。
     */
    public void close(String reason) {
        this.reason = reason;
        state.set(State.CLOSED);
        log.warn("执行入口已关闭：reason={}", reason);
    }

    /**
     * 打开门禁。**只允许**在启动收尾确认完成后调用。
     */
    public void open(String reason) {
        this.reason = reason;
        state.set(State.OPEN);
        log.info("执行入口已开放：reason={}", reason);
    }

    public enum State {
        CLOSED,
        OPEN
    }
}
