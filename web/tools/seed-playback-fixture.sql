-- =============================================================
-- 播放验收用的**会话夹具**（只用于本地 demo 库）
--
-- 为什么需要它：
--   播放卡的数据来自"会话已存的真实工具引用"，而不是模型文本。
--   要在**不调用模型**的前提下验证"点播 → 出声 → 切歌 → 拖进度"，
--   就必须有一个已经带引用的会话。
--
-- 这个会话是**明确标注的夹具**：
--   - 标题写明"播放验收（测试音）"；
--   - 引用的三首是：两首测试音（1901/1902，可播）+ 夜航星（1001，无音源，用来验证"不可播"状态）。
--
-- 不修改任何种子曲目、不写 music_track_source、不动别人的歌单。
-- 重复执行安全（先删后插，只删自己这一条）。
-- =============================================================

DELETE FROM agent_conversation_message WHERE conversation_id = 'demo-playback-fixture';
DELETE FROM agent_conversation WHERE conversation_id = 'demo-playback-fixture';

INSERT INTO agent_conversation
    (id, conversation_id, user_id, title, refs_json, last_run_id, created_at, updated_at)
VALUES
    (9000001,
     'demo-playback-fixture',
     'music-user-1',
     '播放验收（测试音）',
     '{"songs":[{"id":1901,"title":"测试音 A（琶音）"},{"id":1902,"title":"测试音 B（低音）"},{"id":1001,"title":"夜航星"}],"playlists":[]}',
     'demo-playback-run-1',
     CURRENT_TIMESTAMP,
     CURRENT_TIMESTAMP);

INSERT INTO agent_conversation_message
    (id, conversation_id, run_id, role, content, state, created_at)
VALUES
    (9000002, 'demo-playback-fixture', 'demo-playback-run-1', 'USER',
     '播放验收：把测试音找出来', 'SUCCEEDED', CURRENT_TIMESTAMP);

INSERT INTO agent_conversation_message
    (id, conversation_id, run_id, role, content, state, created_at)
VALUES
    (9000003, 'demo-playback-fixture', 'demo-playback-run-1', 'ASSISTANT',
     '找到 2 首可播放的测试音（A 琶音 / B 低音），另有 1 首（夜航星）只有元数据、没有音源。播放由页面完成，点击下方播放卡即可。',
     'SUCCEEDED', CURRENT_TIMESTAMP);
