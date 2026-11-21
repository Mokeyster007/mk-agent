package com.example.mkagent.config;

import com.example.mkagent.exception.BusinessException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 不可用的对话模型占位。
 *
 * <h2>为什么需要它</h2>
 * 商城的下单 / 发货 / 签收完全不经过模型。但工程启动时
 * {@link DashScopeChatModelConfig} 会在构造模型 Bean 时校验凭据，
 * 没有凭据就整个应用起不来——于是"模型没配"会把商城一起拖死。
 *
 * <p>这个占位 Bean 让应用能起来：页面、订单、库存全部照常，
 * 只有真正调用模型的接口返回 503 {@code MODEL_UNAVAILABLE}。
 *
 * <h2>它不会伪装成正常模型</h2>
 * {@link #call(Prompt)} 直接抛业务异常，不返回空回答、不返回"我无法回答"。
 * 一个会说话的假模型比直接报错危险得多：用户会以为 Agent 只是"答得不好"，
 * 而实际上它根本没连上模型。
 *
 * <h2>只在显式开启时生效</h2>
 * 由 {@code mkagent.model.allow-missing-credentials=true} + 凭据确实为空
 * 两个条件同时满足才会注册（见 {@link MissingModelCredentialCondition}）。
 * 默认关闭，原有"缺凭据就启动失败并给出明确提示"的行为完全不变。
 */
public class UnavailableChatModel implements ChatModel {

    /** 稳定原因码：前端与运维按它分支，不解析中文。 */
    public static final String CODE = "MODEL_UNAVAILABLE";

    private static final String MESSAGE =
            "对话模型不可用：未配置模型凭据（MKAGENT_MODEL_API_KEY / spring.ai.dashscope.api-key）。"
                    + "商城、订单与库存等不依赖模型的功能不受影响；"
                    + "需要使用 Agent 时请先配置凭据并重启。";

    @Override
    public ChatResponse call(Prompt prompt) {
        throw new BusinessException(503, CODE, MESSAGE);
    }

    /**
     * 流式调用同样直接失败。
     *
     * <p>不给默认实现：接口默认实现抛 {@code UnsupportedOperationException}，
     * 会被全局异常处理器转成 500。这里给同一个 503 业务异常，
     * 让"模型没配"在流式与阻塞两条链路上表现一致。
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        throw new BusinessException(503, CODE, MESSAGE);
    }

    /**
     * 仅供诊断：日志与排查时一眼看出这是占位实现，而不是某个真模型。
     */
    @Override
    public String toString() {
        return "UnavailableChatModel（未配置模型凭据）";
    }
}
