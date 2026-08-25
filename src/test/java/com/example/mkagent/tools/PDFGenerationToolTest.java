package com.example.mkagent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PDFGenerationToolTest {

    @Test
    public void pdfgeneratetootest() {
        PDFGenerationTool tool = new PDFGenerationTool();
        String filename = "pdf测试文件名";
        String context = "pdf测试文件内容，目前格式等等还没有学会，只是懂得了如何把文本内容转化为pdf文件";
        String result = tool.generatePDF(filename,context);
        assertNotNull(result);
    }

}