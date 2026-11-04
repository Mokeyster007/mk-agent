package com.example.mkagent.tools;

import com.example.mkagent.music.IdempotencyKeys;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一轮工具调用的"身份计划"：把框架即将执行的 {@code ToolCall} 与
 * 回调里拿得到的 (工具名, 参数) 对上号。
 *
 * <h2>为什么需要它</h2>
 *
 * Spring AI 1.0.0 的 {@code ToolCallback} 只有
 * {@code call(String toolInput)} / {@code call(String, ToolContext)}，
 * <b>拿不到 toolCallId</b>（已核对 spring-ai-model 1.0.0 的接口签名）。
 * 但 {@code ToolCallAgent} 在执行前手里有完整的 {@code List<ToolCall>}，
 * 每个都带 id。于是执行前把这一轮登记下来，回调执行时按顺序认领。
 *
 * <h2>它依赖哪些运行契约（不是"任何情况下都能认出来"）</h2>
 *
 * 已核对 {@code DefaultToolCallingManager}（spring-ai-model 1.0.0）的实现：
 * {@code executeToolCalls → executeToolCall} 对 {@code AssistantMessage.getToolCalls()}
 * 用 {@code Iterator} <b>串行</b>遍历，逐个 {@code call(...)}，
 * 没有线程池、没有并行流；因此当前版本的运行契约是：
 * <b>同一线程、按声明顺序、每条 ToolCall 恰好一次回调</b>。
 * 计划在这三条都成立时才可能正确绑定。
 *
 * <h2>哪些契约被破坏能被发现</h2>
 *
 * <ul>
 *   <li><b>换了线程</b>：构建计划时记下所属线程，认领时线程不同 → 直接拒绝；</li>
 *   <li><b>乱序但参数不同</b>：认领到的队首参数与本次回调参数不一致 → 拒绝；</li>
 *   <li><b>多调/少调</b>：队列空 → 拒绝；剩余未认领的条目不会被执行；</li>
 *   <li><b>id 缺失 / 同轮重复</b>：构建计划时整轮标记为拒绝。</li>
 * </ul>
 *
 * <h2>哪些契约被破坏<b>无法</b>被发现（已知残余风险）</h2>
 *
 * 同一线程内乱序分发、且两条调用的<b>工具名与参数完全相同、只有 id 不同</b>时，
 * 认领只能按队列顺序发号，无法分辨谁是谁——此时副作用会被记到另一条调用的身份上
 * （两条都会执行，但记录与调用的对应关系互换）。
 * 仅凭 (工具名, 参数) 不可能识别这种交换，本类<b>不</b>声称能识别；
 * 它依赖上面的顺序契约。真要做强身份绑定，需要框架把 toolCallId 传进回调
 * （或换掉工具执行框架），那超出本批范围。
 *
 * <h2>为什么要按工具名分队列</h2>
 *
 * 一轮里可能先调只读工具、再调写工具；只读工具不认领计划，
 * 全局游标会被卡住。按工具名分队列后，每个工具只消费自己的那几条，
 * 同名的多条按声明顺序依次认领——这正好解决
 * "同名同参、不同 id" 的两次调用：它们各拿各的 id，不会被合并。
 *
 * <h2>失败关闭的两种情形</h2>
 *
 * 1. 身份不可判定（写工具调用缺 id / 同轮 id 重复）→ 构建计划时就标记为拒绝，
 *    该轮任何写工具都不执行、不碰数据库；
 * 2. 认领时对不上（队列空、参数不符、跨线程）→ 本次调用直接返回脱敏错误，同样不执行。
 */
public final class ToolCallPlan {

    /**
     * 计划中的一条调用。
     *
     * @param toolCallId         模型给出的调用 ID（原样保留，不截断）
     * @param toolName           工具名
     * @param canonicalArguments 规范化后的参数，用于认领时比对
     */
    public record PlannedCall(
            String toolCallId,
            String toolName,
            String canonicalArguments
    ) {
    }

    /**
     * 计划无法建立时的稳定原因（脱敏，可直接返回给模型）。
     */
    public static final String REASON_ID_MISSING =
            "工具调用标识缺失，本次写操作已拒绝（未产生任何副作用）";

    /**
     * 同轮出现重复 ID 时的稳定原因。
     */
    public static final String REASON_ID_DUPLICATED =
            "工具调用标识重复，无法确定本次写操作的身份，已拒绝（未产生任何副作用）";

    /**
     * 回调没有绑定计划时的稳定原因。
     */
    public static final String REASON_NOT_BOUND =
            "工具调用身份未绑定，本次写操作已拒绝（未产生任何副作用）";

    /**
     * 认领时参数或顺序对不上时的稳定原因。
     */
    public static final String REASON_MISMATCH =
            "工具调用参数与已登记的计划不一致，本次写操作已拒绝（未产生任何副作用）";

    /**
     * 回调不在构建计划的那个线程上执行时的稳定原因。
     */
    public static final String REASON_CROSS_THREAD =
            "工具调用未在登记它的线程上执行，无法确认调用身份，已拒绝（未产生任何副作用）";

    private final Map<String, Deque<PlannedCall>> pending;

    /**
     * 非 null 表示整轮写操作被拒绝，值为稳定原因。
     */
    private final String rejectedReason;

    /**
     * 构建计划时所在的线程。当前 Spring AI 1.0.0 在调用线程上串行分发，
     * 换线程说明运行契约已被破坏，认领不再可信。
     */
    private final Thread ownerThread;

    private ToolCallPlan(
            Map<String, Deque<PlannedCall>> pending,
            String rejectedReason
    ) {
        this.pending = pending;
        this.rejectedReason = rejectedReason;
        this.ownerThread = Thread.currentThread();
    }

    /**
     * 构建计划。
     *
     * @param calls            本轮模型请求的全部工具调用（按声明顺序）
     * @param identityRequired 需要幂等身份的工具名（即被幂等装饰器包住的写工具）
     */
    public static ToolCallPlan build(
            List<AssistantMessage.ToolCall> calls,
            Set<String> identityRequired
    ) {
        Map<String, Deque<PlannedCall>> pending = new HashMap<>();
        if (calls == null || calls.isEmpty()) {
            return new ToolCallPlan(pending, null);
        }

        Set<String> seenIds = new HashSet<>();
        List<PlannedCall> ordered = new ArrayList<>();

        for (AssistantMessage.ToolCall call : calls) {
            if (!identityRequired.contains(call.name())) {
                continue;
            }

            String id = call.id();
            if (id == null || id.isBlank()) {
                /*
                 * 缺 id 时不做任何"退化派生"：
                 * 用参数派生会让同参的两条独立调用合并成一条，
                 * 等于把两次真实的写操作静默吞掉一次。
                 */
                return new ToolCallPlan(pending, REASON_ID_MISSING);
            }

            if (!seenIds.add(id)) {
                /*
                 * 同轮重复 id：框架会用同一个 id 回填两条 ToolResponse，
                 * 我们无法判断哪条回调对应哪次调用，只能整轮拒绝。
                 */
                return new ToolCallPlan(pending, REASON_ID_DUPLICATED);
            }

            ordered.add(new PlannedCall(
                    id,
                    call.name(),
                    IdempotencyKeys.canonicalJson(call.arguments())
            ));
        }

        for (PlannedCall planned : ordered) {
            pending.computeIfAbsent(planned.toolName(), key -> new ArrayDeque<>())
                    .addLast(planned);
        }

        return new ToolCallPlan(pending, null);
    }

    /**
     * 认领本次回调对应的计划条目。
     *
     * @throws ToolCallPlanException 身份不可判定或与计划不一致（调用方必须失败关闭）
     */
    public PlannedCall claim(String toolName, String rawArguments) {
        if (rejectedReason != null) {
            throw new ToolCallPlanException(rejectedReason);
        }

        if (Thread.currentThread() != ownerThread) {
            /*
             * 换了线程：顺序契约已经不成立（可能是框架改成并行分发，
             * 也可能是回调被丢进了别的线程池）。此时按队列认领会把副作用
             * 记到不确定的身份上，直接拒绝。
             */
            throw new ToolCallPlanException(REASON_CROSS_THREAD);
        }

        Deque<PlannedCall> queue = pending.get(toolName);
        if (queue == null || queue.isEmpty()) {
            throw new ToolCallPlanException(REASON_NOT_BOUND);
        }

        PlannedCall head = queue.peekFirst();
        if (!head.canonicalArguments().equals(
                IdempotencyKeys.canonicalJson(rawArguments))) {
            /*
             * 顺序或参数对不上：宁可拒绝，也不要把这次副作用
             * 记到另一条调用的身份上（那会让幂等记录与业务事实错位）。
             */
            throw new ToolCallPlanException(REASON_MISMATCH);
        }

        queue.removeFirst();
        return head;
    }

    /**
     * 整轮是否已被判定为不可执行（原因脱敏，可直接回给模型）。
     */
    public String rejectedReason() {
        return rejectedReason;
    }

    /**
     * 计划认领失败。消息本身就是可返回给模型的脱敏文案。
     */
    public static class ToolCallPlanException extends RuntimeException {

        public ToolCallPlanException(String message) {
            super(message);
        }
    }
}
