package com.example.mkagent.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourceDownloadToolTest {



    @Test
    public void resourcedownloadtest () {
        ResourceDownloadTool tool = new ResourceDownloadTool();
        String url = "https://i0.hdslb.com/bfs/archive/43a6ec5c0e220fc95222e49e6eadd2a2e7cc36bc.jpg@672w_378h_1c_!web-home-common-cover.avif";
        String filename = "测试图片";
        String result = tool.downloadResource(url,filename);
        assertNotNull(result);

    }

}