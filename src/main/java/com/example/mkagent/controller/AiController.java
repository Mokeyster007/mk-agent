package com.example.mkagent.controller;

import com.example.mkagent.agent.AgentTaskCanceller;
import com.example.mkagent.agent.AgentTaskRegistry;
import com.example.mkagent.agent.AfterSaleAgent;
import com.example.mkagent.agent.MkManus;
import com.example.mkagent.agent.MusicAgent;
import com.example.mkagent.app.chatApp;
import com.example.mkagent.context.UserContextHolder;
import com.example.mkagent.conversation.ConversationSeed;
import com.example.mkagent.conversation.ConversationService;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.model.AgentType;
import com.example.mkagent.model.RunningAgentTask;
import com.example.mkagent.music.MusicAccessGuard;
import com.example.mkagent.shop.ShopAccessGuard;
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
import java.util.UUID;
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
     * 音乐普通用户 Agent（AgentType.MUSIC_USER）。
     *
     * 与 MkManus 同为 ToolCallAgent 的具体实例，共用同一套循环与 SSE 逻辑；
     * 差别只在工具白名单与系统提示词，见 MusicAgentConfig。
     */
    @Resource(name = "musicUserAgent")
    private MusicAgent musicUserAgent;

    /**
     * 音乐管理员 Agent（AgentType.MUSIC_ADMIN）。
     */
    @Resource(name = "musicAdminAgent")
    private MusicAgent musicAdminAgent;

    /**
     * 音乐入口访问控制：在启动 Agent **之前**校验身份与角色。
     */
    @Resource
    private MusicAccessGuard musicAccessGuard;

    /**
     * 电商售后 Agent（AgentType.AFTER_SALES）。
     *
     * 与音乐 Agent 共用同一套循环、SSE、会话与审批；
     * 差别只有工具白名单（售后只读 + 唯一写工具 submit_return）与提示词。
     */
    @Resource(name = "afterSaleAgent")
    private AfterSaleAgent afterSaleAgent;

    /**
     * 商城访问控制：售后入口复用它解析买家身份。
     *
     * <p>关闭 demo 身份且没有可信身份时，{@code requireBuyerIdentity()} 直接 403，
     * 因此"新增的私有读写入口"不会因为只加了 {@code X-User-Id} 就被放行。
     */
    @Resource
    private ShopAccessGuard shopAccessGuard;

    /**
     * 会话读写（同一窗口连续聊天）。
     */
    @Resource
    private ConversationService conversationService;

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
     * 知识库问答（RAG）。
     *
     * 与其他 chat 接口保持一致的同步返回风格。
     * 知识库未启用或初始化失败时，由 chatApp 抛出
     * BusinessException(503)，经 GlobalExceptionHandler
     * 转换为 503 + JSON 业务错误，不影响其他接口。
     */
    @GetMapping("/chat_app/rag")
    public String doChatWithChatAppRag(
            @RequestParam String message,
            @RequestParam String chatId
    ) {
        return chatApp.doChatWithRag(message, chatId);
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
     * 音乐普通用户入口。
     *
     * 访问控制先于 Agent 启动：缺身份 401、未知用户 403。
     * agentType 由**入口**决定（MUSIC_USER），不接受任何请求参数。
     *
     * 与 /ai/manus/chat 使用同一套限流；
     * 该接口本身没有输入长度校验（全项目统一由模型侧截断），
     * 音乐入口保持一致，不额外加一条只作用于新接口的规则。
     *
     * <p>conversationId 可选：带上就是"同一会话里的下一轮"，
     * 不带就是老的独立任务（行为与以前完全一致，历史记录不需要迁移）。
     * 会话只影响"注入哪些历史文本 / 业务引用"和"终态写哪条会话"，
     * **每轮仍然新建 runId**，绝不复活已终态的 run。
     */
    @GetMapping("/music/chat")
    public SseEmitter doChatWithMusic(
            String message,
            @RequestParam(required = false) String conversationId,
            HttpServletRequest request
    ) {
        String userId = musicAccessGuard.requireUser();
        checkAgentRateLimit(request);

        if (conversationId == null || conversationId.isBlank()) {
            return musicUserAgent.runStream(message);
        }

        /*
         * 先落会话（校验归属、拦并发、组装上下文、写用户消息），再启动 Agent。
         * runId 由这里生成并交给 Agent，这样"用户消息"与"run"从一开始就是同一个 id，
         * 不需要事后回填。
         */
        UUID runId = UUID.randomUUID();
        ConversationSeed seed = conversationService.beginTurn(
                userId, conversationId, message, runId);

        try {
            return musicUserAgent.runStream(message, seed);
        } catch (RuntimeException e) {
            // 启动失败就把刚落的用户消息撤掉，避免会话里留一条永远没有回答的提问
            conversationService.dropUserMessage(runId.toString());
            throw e;
        }
    }

    /**
     * 音乐管理员入口。
     *
     * 要求 music_user 表中角色为 ADMIN，否则 403；
     * agentType 固定为 MUSIC_ADMIN（白名单里没有歌单类工具）。
     */
    @GetMapping("/music/admin/chat")
    public SseEmitter doChatWithMusicAdmin(
            String message,
            HttpServletRequest request
    ) {
        musicAccessGuard.requireAdmin();
        checkAgentRateLimit(request);

        return musicAdminAgent.runStream(message);
    }

    /**
     * 电商售后入口。
     *
     * <p>与音乐入口同构：先做访问控制，再决定是否落会话、启动 Agent。
     *
     * <p>身份来自 {@link ShopAccessGuard#requireBuyerIdentity()}：
     * 缺 {@code X-User-Id} 401、未启用演示身份 403
     * {@code SHOP_IDENTITY_NOT_CONFIGURED}、不在 {@code shop_user} 里 403
     * {@code SHOP_USER_NOT_PROVISIONED}。
     * 身份在 {@code runStream} 的同步段被冻结进 {@code AgentRunContext}，
     * 工具侧没有 buyerId 参数，模型无法改变它。
     *
     * <p>conversationId 可选：带上就是"同一会话里的下一轮"（追问哪一单、几件、为什么），
     * 不带就是独立任务。每轮仍然新建 runId。
     */
    @GetMapping("/aftersale/chat")
    public SseEmitter doChatWithAfterSale(
            String message,
            @RequestParam(required = false) String conversationId,
            HttpServletRequest request
    ) {
        String userId = shopAccessGuard.requireBuyerIdentity();
        checkAgentRateLimit(request);

        if (conversationId == null || conversationId.isBlank()) {
            return afterSaleAgent.runStream(message);
        }

        UUID runId = UUID.randomUUID();
        ConversationSeed seed = conversationService.beginTurn(
                userId, conversationId, message, runId, AgentType.AFTER_SALES);

        try {
            return afterSaleAgent.runStream(message, seed);
        } catch (RuntimeException e) {
            // 启动失败就撤掉刚落的用户消息，避免会话里留一条永远没有回答的提问
            conversationService.dropUserMessage(runId.toString());
            throw e;
        }
    }

    /**
     * 主动取消运行中的 Agent 任务。
     *
     * 流程：
     * 1. 必须携带用户身份，否则 401；
     * 2. 按 runId 查注册表，不存在**或不属于当前用户** → 404 业务错误；
     * 3. 运行中：由 AgentTaskCanceller 完成状态转换、
     *    线程中断与 SSE 通知（终态竞态时抛 409）。
     *
     * 归属校验为什么返回 404 而不是 403：
     * 403 等于告诉调用方"这个 runId 确实存在，只是不是你的"，
     * 攻击者可以据此枚举他人的 runId；两种情况共用同一句文案，
     * 外部无法区分。
     *
     * 注册表移除由任务线程的 finally 完成，这里不重复移除。
     */
    @PostMapping("/manus/{runId}/cancel")
    public Map<String, Object> cancelManusTask(@PathVariable String runId) {
        String userId = requireUserId();

        RunningAgentTask task = agentTaskRegistry.get(runId);

        if (task == null || !userId.equals(task.context().getUserId())) {
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