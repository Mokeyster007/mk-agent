package com.example.mkagent.app;



import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.example.mkagent.advisor.MyLoggerAdvisor;
import com.example.mkagent.advisor.ReReadingAdvisor;
import com.example.mkagent.chatmemory.FileBasedChatMemory;
import com.example.mkagent.demo.invoke.TestApiKey;
import com.example.mkagent.exception.BusinessException;
import com.example.mkagent.rag.ChatAppDocumentLoader;
import com.example.mkagent.rag.ChatAppRagCloudAdvisorConfig;
import com.example.mkagent.rag.ChatAppRagCustomAdvisorFactory;
import com.example.mkagent.rag.QueryRewriter;
import com.example.mkagent.rag.RagAvailability;
import com.example.mkagent.rag.RagProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
@Slf4j
public class chatApp {

    private static final String SYSTEM_PROMPT = """
            你是一名专注于 AI 知识学习咨询的助手。
            开场时先表明身份，并告诉用户可以咨询任何 AI 学习相关的问题。

            根据用户的学习阶段进行追问：
            - 零基础：询问学习目标、编程与数学基础、可投入的学习时间；
            - 入门中：询问当前学习的内容、遇到的卡点、使用的工具与框架；
            - 进阶实践：询问正在做的项目、技术选型、遇到的具体技术问题。

            引导用户说明学习背景、目标方向和当前的困惑，
            再给出清晰、具体、可执行的学习建议与资源推荐。
            回答时优先基于知识库中的资料作答；如知识库中没有相关内容，
            请如实说明，并给出通用性的学习建议，不要编造不存在的课程或链接。
            """;

    private final ChatClient chatClient;

    /**
     * 知识库向量库的延迟提供者。
     *
     * 用 ObjectProvider 而不是直接注入 VectorStore：
     * 1. lazy 模式下向量库 Bean 带 @Lazy，只有真正调用 RAG 时才构建；
     * 2. 构建失败时这里能捕获到异常并转换成 503，而不是让整个应用起不来。
     */
    private final ObjectProvider<VectorStore> chatAppVectorStoreProvider;

    /**
     * RAG 开关与构建时机。
     */
    private final RagProperties ragProperties;

    /**
     * 知识库可用性标记（构建失败后 fail-closed）。
     */
    private final RagAvailability ragAvailability;

    /**
     * Spring 创建 ChatApp Bean 时自动注入 ChatModel。
     */
    public chatApp(
            ChatModel chatModel,
            @Qualifier("chatAppVectorStore")
            ObjectProvider<VectorStore> chatAppVectorStoreProvider,
            RagProperties ragProperties,
            RagAvailability ragAvailability
    ) {
        this.chatAppVectorStoreProvider = chatAppVectorStoreProvider;
        this.ragProperties = ragProperties;
        this.ragAvailability = ragAvailability;

        //初始化对于文件的对话记忆
        String fileDir = System.getProperty("user.dir") + "/tmp/chat-memory";
        ChatMemory chatMemory = new FileBasedChatMemory(fileDir);

        /*
        //初始化对于内存的对话记忆
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)//由于Spring AI版本更新，现在的上下文记忆条数需要在记忆设置的时候配置
                .build();
         */
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        //自定义日志拦截器
                        new MyLoggerAdvisor()

                        /*自定义ReReading

                        new ReReadingAdvisor()

                         */
                )
                .build();
    }

    /**
     * 单次 AI 对话。
     *
     * @param message 用户本轮输入
     * @param chatId 当前会话 ID；同一聊天窗口必须始终传相同的 chatId
     * @return AI 文本回复
     */
    public String doChat(String message, String chatId) {
        ChatResponse response = chatClient.prompt()
                .user(message)
                .advisors(advisor -> advisor.param(
                        ChatMemory.CONVERSATION_ID,
                        chatId
                ))
                .call()
                .chatResponse();

        String content = response.getResult().getOutput().getText();
        log.info("chatId: {}, content: {}", chatId, content);
        return content;
    }


    record StudyReport(String title, List<String> suggestion){

    }
    /**
     * AI 学习报告功能（支持结构化输出）
     * @param message
     * @param chatId
     * @return
     */
    public StudyReport doChatWithReport(String message, String chatId) {
        StudyReport studyReport = chatClient.prompt()
                .system(SYSTEM_PROMPT + "每次对话都要生成学习报告结构，标题为{用户名}的AI学习报告，内容为建议列表")
                .user(message)
                .advisors(advisor -> advisor.param(
                        ChatMemory.CONVERSATION_ID,
                        chatId
                ))
                .call()
                .entity(StudyReport.class);

        log.info("StudyReport: {}", studyReport);
        return studyReport;
    }





     @Resource(name = "chatAppRagCloudAdvisor")
    private Advisor chatAppRagCloudAdvisor;

    @Resource
    private QueryRewriter queryRewriter;

    /**
     * 解析当前可用的知识库向量库。
     *
     * 顺序很重要：必须在查询改写（会调用大模型）之前判断，
     * 否则知识库不可用时仍会先花一次模型调用才返回 503。
     *
     * @return 可用的向量库
     * @throws BusinessException 503：知识库未启用 / 初始化失败
     */
    private VectorStore resolveRagVectorStore() {
        if (!ragProperties.isEnabled()) {
            throw new BusinessException(503, "知识库未启用");
        }

        if (!ragAvailability.isAvailable()) {
            log.warn("知识库不可用，拒绝 RAG 请求：reason={}",
                    ragAvailability.getUnavailableReason());
            throw new BusinessException(503, "知识库暂不可用");
        }

        try {
            // lazy 模式下，这一行才会真正构建向量库（含模型调用）。
            return chatAppVectorStoreProvider.getObject();

        } catch (Exception e) {
            /*
             * 对外只返回统一文案：异常原文可能包含路径、URL 等内部信息。
             */
            ragAvailability.markUnavailable("向量库初始化失败");
            log.error("知识库初始化失败，RAG 请求返回 503", e);
            throw new BusinessException(503, "知识库暂不可用");
        }
    }

    /**
     * 和RAG知识库进行问答
     * @param message
     * @param chatId
     * @return
     */

    public String doChatWithRag(String message, String chatId) {

        VectorStore chatAppVectorStore = resolveRagVectorStore();

        String rewrittenMessage = queryRewriter.doQueryRewrite(message);

        ChatResponse chatResponse = chatClient.prompt()
                .user(rewrittenMessage)//优化用户的输入内容之后再次进行引入
                .advisors(spec -> spec.param(
                        ChatMemory.CONVERSATION_ID,
                        chatId
                ))
                //对本地数据进行分片，再存储到向量数据库中，再针对向量数据进行初步检索召回
                //.advisors(new QuestionAnswerAdvisor(chatAppVectorStore)) //对本地数据进行分片，再存储到向量数据库中，再针对向量数据进行初步检索召回

                //对云数据库的内容进行获取，云数据库
                //.advisors(chatAppRagCloudAdvisor)

                /**
                 *应用自定义的RAG检索增强顾问（文档查询器 + 上下文增强器）
                 */
                .advisors(
                        ChatAppRagCustomAdvisorFactory.createChatAppRagCustomAdvisor(
                                chatAppVectorStore, "入门"  //自定义RAG，按学习阶段（入门/进阶/实践）过滤知识库文档
                        )
                )
                .call()
                .chatResponse();

        String content = chatResponse.getResult().getOutput().getText();
        log.info("chatId: {}, content: {}", chatId, content);
        return content;
    }


    @Resource
    private ToolCallbackProvider toolCallbackProvider; //mcp工具注入

    public String doChatWithMcp(String message, String chatId) {
        ChatResponse response = chatClient
                .prompt()
                .user(message)
                .advisors(advisor -> advisor.param(
                        ChatMemory.CONVERSATION_ID,
                        chatId
                ))

                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("content: {}", content);
        return content;
    }


    /**
     * 流式 AI 对话（响应式）。
     * 调用方可通过 Flux 订阅逐字输出，例如对接 SSE 推送给前端。
     *
     * @param message 用户本轮输入
     * @param chatId  当前会话 ID
     * @return AI 回复的文本流
     */
    public Flux<String> doChatWithByStream(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId)
                        .param("chat_memory_retrieve_size", 10))
                .stream()
                .content();
    }
}
