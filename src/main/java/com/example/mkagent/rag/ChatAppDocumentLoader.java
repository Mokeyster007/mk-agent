package com.example.mkagent.rag;

import com.github.xiaoymin.knife4j.spring.model.MarkdownFiles;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import org.springframework.ai.document.Document;
import java.lang.annotation.Documented;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class ChatAppDocumentLoader {

    private final ResourcePatternResolver resourcePatternResolver;

    ChatAppDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    public List<Document> loadMarkdowns() {
        List<Document> alldocument = new ArrayList<>(); //用来存放最终结果的数组

        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");

            for(Resource resource:resources){
                //获取到文件名
                String filename = resource.getFilename();

                //定义读取MarkDown文件的规则，同时会自动对文件内容进行分片
                String status = filename.substring(filename.length() - 6, filename.length() - 4);
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("filename", filename)
                        //将文件转换为document的同时，基于文件的名称来决定这个文档的状态，目前只是初学展示metada是如何使用的
                        .withAdditionalMetadata("status", status)
                        .build();


                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource,config);
                alldocument.addAll(reader.get());
            }

        } catch (Exception e){
            log.error("Markdown 文档加载失败", e);

        }
        return alldocument;

    }

}
