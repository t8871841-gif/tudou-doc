import 'package:flutter_test/flutter_test.dart';
import 'package:jarvis_app/services/agent_service.dart';
import 'package:jarvis_app/services/llm_service.dart';
import 'package:jarvis_app/services/local_llm_service.dart';
import 'package:jarvis_app/services/memory_service.dart';
import 'package:jarvis_app/services/tool_service.dart';

void main() {
  test('toPlain 不再吃掉乘号（128*3+45 保持原样）', () {
    expect(LlmService.toPlain('128*3+45'), '128*3+45');
    expect(LlmService.toPlain('**加粗**文字'), '加粗文字');
    expect(LlmService.toPlain('- 条目'), '· 条目');
  });

  test('简单对话分流：闲聊本地、知识/实时上云', () {
    expect(LlmService.isSimpleChat('你好呀'), isTrue);
    expect(LlmService.isSimpleChat('讲个笑话'), isTrue);
    expect(LlmService.isSimpleChat('为什么天空是蓝色的'), isFalse);
    expect(LlmService.isSimpleChat('今天最新的新闻'), isFalse);
    expect(LlmService.isSimpleChat('帮我写一封很长很长的辞职信给我的上司并且要求措辞礼貌得体而且尽量详细'),
        isFalse);
  });

  test('needsTools 区分任务与聊天', () {
    expect(AgentService.needsTools('打开微信'), isTrue);
    expect(AgentService.needsTools('帮我查一下最新的汇率'), isTrue);
    expect(AgentService.needsTools('今天心情不好'), isFalse);
  });

  test('中文数字解析', () {
    expect(ToolService.cnToInt('十二'), 12);
    expect(ToolService.cnToInt('二十'), 20);
    expect(ToolService.cnToInt('一百零五'), 105);
    expect(ToolService.cnToInt('45'), 45);
  });

  test('本地模型提示词模板按文件名选择', () {
    final q = LocalLlmService.formatPrompt('/x/Qwen2.5-1.5B.task', '系统', const [], '你好');
    expect(q.contains('<|im_start|>user'), isTrue);
    final g = LocalLlmService.formatPrompt('/x/gemma3.task', '系统', const [], '你好');
    expect(g.contains('<start_of_turn>user'), isTrue);
    expect(g.endsWith('<start_of_turn>model\n'), isTrue);
  });

  test('记忆口令解析保持不变', () {
    expect(MemoryService.parseCommand('记住我不吃辣')?.kind, 'remember');
    expect(MemoryService.parseCommand('忘记我的住址')?.kind, 'forget');
  });
}
