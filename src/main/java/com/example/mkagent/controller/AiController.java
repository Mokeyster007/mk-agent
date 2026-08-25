package com.example.mkagent.controller;

import com.example.mkagent.agent.AgentTaskCanceller;
import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.MkManus;
import com.example.mkagent.app.chatApp;
import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.model.RunningAgentTask;
import com.example.mkagent.resilience.AgentRequestRateLimiter;
import com.example.mkagent.resilience.RateLimitResult;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    @Resource
    private chatApp chatApp;

    @Resource
    private ToolCallback[] mkToolCallbacks;

    @Resource
    private ChatModel dashscopeChatModel;

    @Resource
    private Executor agentExecutor;

    /**
     * Spring 管理的 MkManus 单例。
     *
     * MkManus 本身无状态（每次任务的状态都在 per-run 的 AgentRunContext 中），
     * 注入单例既避免了每次请求 new 时重复构建 ToolCallingManager，
     * 也能让 @Value/@PostConstruct 配置（如 agent.debug-sse-events）正常生效。
     */
    @Resource
    private MkManus mkManus;

    /**
     * 运行中任务注册表：按 runId 查询任务，支撑主动取消接口。
     */
    @Resource
    private AgentTaskRegistry agentTaskRegistry;

    /**
     * 任务取消执行器：封装状态转换 + 中断线程 + SSE 通知，
     * 与 POST /ai/runs/{runId}/cancel 共用同一套取消逻辑。
     */
    @Resource
    private AgentTaskCanceller agentTaskCanceller;

    /**
     * Agent 请求限流器：仅作用于发起 Agent 任务的接口，
     * 不影响普通聊天等不涉及 Agent 的业务接口。
     * 当前为进程内实现；后续接入 Redis 只需替换实现类。
     */
    @Resource
    private AgentRequestRateLimiter agentRequestRateLimiter;


    /**
     * 同步调用：等待 AI 完整回答生成完，再一次性返回。
     */
    @GetMapping("/chat_app/chat/sync")
    public String doChatWithChatAppSync(
            @RequestParam String message,
            @RequestParam String chatId
    ) {
        return chatApp.doChat(message, chatId);
    }

    /**
     * 方式 1：
     * 直接返回 Flux<String>。
     *
     * Flux<String>
     * ↓
     * Spring 自动订阅
     * ↓
     * Spring 自动按 text/event-stream 写入 SSE 响应
     * ↓
     * 浏览器逐段收到 chunk
     */
    @GetMapping(
            value = "/chat_app/chat/sse",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<String> doChatWithChatAppSSE(
            @RequestParam String message,
            @RequestParam String chatId
    ) {
        return chatApp.doChatWithByStream(message, chatId);
    }

    /**
     * 方式 2：
     * 将每个 String chunk 包装为一个 ServerSentEvent<String>。
     *
     * Flux<String>
     * ↓
     * map 转换
     * ↓
     * Flux<ServerSentEvent<String>>
     * ↓
     * Spring 自动写入 SSE 响应
     */
    @GetMapping(
            value = "/chat_app/chat/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Flux<ServerSentEvent<String>> doChatWithChatAppEvents(
            @RequestParam String message,
            @RequestParam String chatId
    ) {
        return chatApp.doChatWithByStream(message, chatId)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }

    /**
     * 方式 3：
     * 创建一个 SseEmitter，代表本次请求的整条 SSE 连接。
     *
     * Flux 每产生一个 chunk：
     * ↓
     * emitter.send(chunk)
     * ↓
     * 向浏览器推送一条 SSE 消息
     */
    @GetMapping(
            value = "/chat_app/chat/sse/emitter",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter doChatWithChatAppSseEmitter(
            @RequestParam String message,
            @RequestParam String chatId
    ) {
        SseEmitter emitter = new SseEmitter(180_000L);

        chatApp.doChatWithByStream(message, chatId)
                .subscribe(
                        chunk -> {
                            try {
                                emitter.send(chunk);
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        emitter::completeWithError,
                        emitter::complete
                );

        return emitter;
    }



    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(
            String message,
            HttpServletRequest request
    ) {
        /*
         * 任务归属需要用户身份：当前由请求头 X-User-Id 占位提供，
         * 后续接入真实认证时只需替换拦截器解析逻辑。
         */
        requireUserId();

        /*
         * 用户级限流：优先按 userId，无身份时临时回退 IP。
         * 超限返回 429 + 友好提示 + 建议等待秒数。
         */
        checkAgentRateLimit(request);

        return mkManus.runStream(message);
    }

    /**
     * 主动取消运行中的 Agent 任务。
     *
     * 流程：
     * 1. 按 runId 查注册表，不存在 → 404 业务错误；
     * 2. 运行中：由 AgentTaskCanceller 完成状态转换、
     *    线程中断与 SSE 通知（终态竞态时抛 409）。
     *
     * 注册表移除由任务线程的 finally 完成，这里不重复移除。
     */
    @PostMapping("/manus/{runId}/cancel")
    public Map<String, Object> cancelManusTask(@PathVariable String runId) {
        RunningAgentTask task = agentTaskRegistry.get(runId);

        if (task == null) {
            throw new BusinessException(404, "任务不存在或已结束：" + runId);
        }

        AgentTaskCanceller.CancelResult result = agentTaskCanceller.cancel(task);

        return Map.of(
                "success", true,
                "runId", runId,
                "state", result.state(),
                "message", result.message()
        );
    }

    /**
     * 要求当前请求携带用户身份，否则 401。
     */
    private String requireUserId() {
        String userId = UserContextHolder.get();
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(
                    401,
                    "缺少用户身份，请在请求头中携带 "
                            + UserContextHolder.USER_ID_HEADER
            );
        }
        return userId;
    }

    /**
     * Agent 请求限流。
     *
     * 限流键解析（后续切换真实用户体系的唯一扩展点）：
     * 1. 有用户身份 → user:{userId}；
     * 2. 无用户身份的临时方案 → ip:{remoteAddr}。
     *
     * 当前仅拦截发起 Agent 任务的接口，
     * 普通聊天等接口不受影响。
     */
    private void checkAgentRateLimit(HttpServletRequest request) {
        String userId = UserContextHolder.get();

        String rateLimitKey = (userId != null && !userId.isBlank())
                ? "user:" + userId
                : "ip:" + request.getRemoteAddr();

        RateLimitResult result = agentRequestRateLimiter.tryAcquire(rateLimitKey);

        if (!result.allowed()) {
            throw new BusinessException(
                    429,
                    "请求过于频繁，请在约 " + result.waitSeconds()
                            + " 秒后重试（限制：每分钟最多 "
                            + result.limit() + " 次 Agent 请求）。"
            );
        }
    }

}