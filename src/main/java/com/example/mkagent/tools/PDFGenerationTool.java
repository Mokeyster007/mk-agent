package com.example.mkagent.tools;

import cn.hutool.core.io.FileUtil;
import com.example.mkagent.constant.FileConstant;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class PDFGenerationTool {

    @Tool(
            name = "generate_pdf",
            description = """
                    根据提供的文件名和正文内容生成 PDF 文件。

                    当用户明确要求生成、导出、保存或输出 PDF 文档时，
                    必须调用该工具。

                    工具会将文件保存到系统指定的 PDF 目录，
                    并返回实际生成结果和文件路径。
                    """
    )
    public String generatePDF(
            @ToolParam(
                    description = """
                            PDF 文件名，不需要包含目录路径。
                            例如：spring-ai-learning-notes.pdf
                            或：spring-ai-learning-notes
                            """
            )
            String fileName,

            @ToolParam(
                    description = """
                            要写入 PDF 的完整正文内容。
                            内容应已包含标题、章节、段落等最终文档文本。
                            """
            )
            String content
    ) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";

        try {
            String safeFileName = sanitizeFileName(fileName);

            if (!safeFileName.toLowerCase().endsWith(".pdf")) {
                safeFileName += ".pdf";
            }

            String filePath = fileDir + "/" + safeFileName;

            FileUtil.mkdir(fileDir);

            try (PdfWriter writer = new PdfWriter(filePath);
                 PdfDocument pdf = new PdfDocument(writer);
                 Document document = new Document(pdf)) {

                PdfFont font = PdfFontFactory.createFont(
                        "STSongStd-Light",
                        "UniGB-UCS2-H"
                );

                document.setFont(font);
                document.add(new Paragraph(content));
            }

            return "PDF 生成成功，文件路径：" + filePath;

        } catch (IOException e) {
            return "PDF 生成失败：" + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "PDF 生成失败，文件名不合法：" + e.getMessage();
        }
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        String safeFileName = fileName.trim();

        if (safeFileName.contains("..")
                || safeFileName.contains("/")
                || safeFileName.contains("\\")
                || safeFileName.contains("\0")) {
            throw new IllegalArgumentException("文件名不能包含路径字符");
        }

        return safeFileName;
    }
}