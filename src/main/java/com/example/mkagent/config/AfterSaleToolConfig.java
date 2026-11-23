package com.example.mkagent.config;

import com.example.mkagent.idempotency.IdempotencyReplayService;
import com.example.mkagent.idempotency.IdempotentWriteExecutor;
import com.example.mkagent.tools.AfterSaleWriteTool;
import com.example.mkagent.tools.IdempotentToolCallback;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * 售后写工具的幂等装配。
 *
 * <h2>为什么复用框架既有的写工具幂等机制</h2>
 *
 * {@code submit_return} 需要三件事：
 * <ol>
 *   <li><b>同一次确认重放只建一单</b>：确认卡的每次执行都带
 *       {@code runId + step + toolCallId}，检查点恢复时原样重建，
 *       因此幂等键稳定，重放直接命中已提交结果；</li>
 *   <li><b>旧代次不能写</b>：恢复后被再次暂停、或迟到的旧线程回调，
 *       必须被代次守卫挡住；</li>
 *   <li><b>并发确认只建一单</b>：唯一索引裁决，输的那一方回滚后回放赢家结果。</li>
 * </ol>
 *
 * 这三件事正是 {@link IdempotentToolCallback} + {@link IdempotentWriteExecutor}
 * 已经验证过的能力，另写一份只会引入第二套语义。
 *
 * <p><b>已知的命名遗留</b>：该执行器的记录表叫 {@code music_tool_idempotency}
 * （M3 时期只有音乐写工具）。它是**框架级写工具幂等存储**，不是音乐业务数据；
 * 本轮复用不改变其语义，也没有往里面写任何音乐内容。
 * 真正的业务数据（退货单、额度、事件）全部在 {@code shop_aftersale_*} 里。
 *
 * <h2>与普通表单的关系</h2>
 *
 * 表单走的是另一条身份派生（前端生成的 {@code clientRequestId}），
 * 记录在 {@code shop_aftersale_idempotency}。
 * 两条路径的身份来源不同，**不能共用一个键格式**——
 * 否则"同一次确认重放"与"两次独立申请"会变得无法区分。
 */
@Configuration
public class AfterSaleToolConfig {

    @Bean("afterSaleWriteToolCallbacks")
    public ToolCallback[] afterSaleWriteToolCallbacks(
            AfterSaleWriteTool afterSaleWriteTool,
            IdempotentWriteExecutor idempotentWriteExecutor,
            IdempotencyReplayService idempotencyReplayService
    ) {
        return Arrays.stream(ToolCallbacks.from(afterSaleWriteTool))
                .map(callback -> new IdempotentToolCallback(
                        callback, idempotentWriteExecutor, idempotencyReplayService))
                .toArray(ToolCallback[]::new);
    }
}
