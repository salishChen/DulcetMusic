import 'package:flutter/material.dart';
import 'package:flute_example/data/subsonic_service.dart';
import 'package:flute_example/data/models/subsonic_config.dart';
import 'package:flute_example/widgets/mp_nav_scaffold.dart';

/// 远程配置页面
///
/// 配置 Subsonic 服务器连接信息，支持内网/公网双 URL。
class SubsonicConfigPage extends StatefulWidget {
  const SubsonicConfigPage({Key? key}) : super(key: key);

  @override
  State<SubsonicConfigPage> createState() => _SubsonicConfigPageState();
}

class _SubsonicConfigPageState extends State<SubsonicConfigPage> {
  final _formKey = GlobalKey<FormState>();
  final _intranetController = TextEditingController();
  final _publicController = TextEditingController();
  final _usernameController = TextEditingController();
  final _passwordController = TextEditingController();
  final _serverNameController = TextEditingController();

  bool _loading = true;
  bool _testing = false;
  bool _saving = false;
  bool _obscurePassword = true;
  String? _testResult;
  bool _testSuccess = false;

  @override
  void initState() {
    super.initState();
    _loadConfig();
  }

  @override
  void dispose() {
    _intranetController.dispose();
    _publicController.dispose();
    _usernameController.dispose();
    _passwordController.dispose();
    _serverNameController.dispose();
    super.dispose();
  }

  Future<void> _loadConfig() async {
    final config = await SubsonicService.instance.loadConfig();
    if (config != null && mounted) {
      setState(() {
        _intranetController.text = config.intranetUrl;
        _publicController.text = config.publicUrl;
        _usernameController.text = config.username;
        _passwordController.text = config.password;
        _serverNameController.text = config.serverName ?? '';
        _loading = false;
      });
    } else if (mounted) {
      setState(() => _loading = false);
    }
  }

  Future<void> _testConnection() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() {
      _testing = true;
      _testResult = null;
    });

    // 临时保存配置以测试
    final tempConfig = SubsonicConfig(
      intranetUrl: _intranetController.text.trim(),
      publicUrl: _publicController.text.trim(),
      username: _usernameController.text.trim(),
      password: _passwordController.text,
      serverName: _serverNameController.text.trim(),
    );
    await SubsonicService.instance.saveConfig(tempConfig);

    final success = await SubsonicService.instance.ping();

    if (mounted) {
      setState(() {
        _testing = false;
        _testSuccess = success;
        _testResult = success ? '连接成功！' : '连接失败，请检查配置';
      });
    }
  }

  Future<void> _save() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _saving = true);

    final config = SubsonicConfig(
      intranetUrl: _intranetController.text.trim(),
      publicUrl: _publicController.text.trim(),
      username: _usernameController.text.trim(),
      password: _passwordController.text,
      serverName: _serverNameController.text.trim(),
    );

    await SubsonicService.instance.saveConfig(config);

    if (mounted) {
      setState(() => _saving = false);
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('配置已保存'),
          duration: Duration(seconds: 1),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: buildPrimaryAppBar(context, '远程配置'),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : SingleChildScrollView(
              padding: const EdgeInsets.all(16.0),
              child: Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    // 服务器名称
                    TextFormField(
                      controller: _serverNameController,
                      decoration: InputDecoration(
                        labelText: '服务器名称（可选）',
                        hintText: '例如：我的音乐服务器',
                        prefixIcon: const Icon(Icons.dns),
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(12.0),
                        ),
                      ),
                    ),
                    const SizedBox(height: 16.0),

                    // 内网地址
                    TextFormField(
                      controller: _intranetController,
                      decoration: InputDecoration(
                        labelText: '内网地址 *',
                        hintText: '例如：http://192.168.1.100:4040',
                        prefixIcon: const Icon(Icons.home),
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(12.0),
                        ),
                      ),
                      validator: (v) {
                        if (v == null || v.trim().isEmpty) return '请输入内网地址';
                        if (!v.startsWith('http://') && !v.startsWith('https://')) {
                          return '请输入有效的 URL（以 http:// 或 https:// 开头）';
                        }
                        return null;
                      },
                    ),
                    const SizedBox(height: 16.0),

                    // 公网地址
                    TextFormField(
                      controller: _publicController,
                      decoration: InputDecoration(
                        labelText: '公网地址 *',
                        hintText: '例如：https://music.example.com',
                        prefixIcon: const Icon(Icons.public),
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(12.0),
                        ),
                      ),
                      validator: (v) {
                        if (v == null || v.trim().isEmpty) return '请输入公网地址';
                        if (!v.startsWith('http://') && !v.startsWith('https://')) {
                          return '请输入有效的 URL（以 http:// 或 https:// 开头）';
                        }
                        return null;
                      },
                    ),
                    const SizedBox(height: 8.0),
                    Text(
                      '优先使用内网连接，内网不可达时自动切换公网',
                      style: TextStyle(
                        fontSize: 12.0,
                        color: Theme.of(context)
                            .textTheme
                            .bodySmall
                            ?.color
                            ?.withOpacity(0.6),
                      ),
                    ),
                    const SizedBox(height: 16.0),

                    // 用户名
                    TextFormField(
                      controller: _usernameController,
                      decoration: InputDecoration(
                        labelText: '用户名 *',
                        prefixIcon: const Icon(Icons.person),
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(12.0),
                        ),
                      ),
                      validator: (v) =>
                          v == null || v.trim().isEmpty ? '请输入用户名' : null,
                    ),
                    const SizedBox(height: 16.0),

                    // 密码
                    TextFormField(
                      controller: _passwordController,
                      obscureText: _obscurePassword,
                      decoration: InputDecoration(
                        labelText: '密码 *',
                        prefixIcon: const Icon(Icons.lock),
                        suffixIcon: IconButton(
                          icon: Icon(_obscurePassword
                              ? Icons.visibility_off
                              : Icons.visibility),
                          onPressed: () => setState(
                              () => _obscurePassword = !_obscurePassword),
                        ),
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(12.0),
                        ),
                      ),
                      validator: (v) =>
                          v == null || v.isEmpty ? '请输入密码' : null,
                    ),
                    const SizedBox(height: 24.0),

                    // 测试结果
                    if (_testResult != null)
                      Container(
                        padding: const EdgeInsets.all(12.0),
                        decoration: BoxDecoration(
                          color: _testSuccess
                              ? Colors.green.withOpacity(0.1)
                              : Colors.red.withOpacity(0.1),
                          borderRadius: BorderRadius.circular(12.0),
                          border: Border.all(
                            color: _testSuccess
                                ? Colors.green.withOpacity(0.3)
                                : Colors.red.withOpacity(0.3),
                          ),
                        ),
                        child: Row(
                          children: [
                            Icon(
                              _testSuccess
                                  ? Icons.check_circle
                                  : Icons.error,
                              color: _testSuccess ? Colors.green : Colors.red,
                            ),
                            const SizedBox(width: 8.0),
                            Text(
                              _testResult!,
                              style: TextStyle(
                                color:
                                    _testSuccess ? Colors.green : Colors.red,
                              ),
                            ),
                          ],
                        ),
                      ),
                    if (_testResult != null) const SizedBox(height: 16.0),

                    // 按钮行
                    Row(
                      children: [
                        Expanded(
                          child: OutlinedButton.icon(
                            onPressed: _testing ? null : _testConnection,
                            icon: _testing
                                ? const SizedBox(
                                    width: 16,
                                    height: 16,
                                    child: CircularProgressIndicator(
                                        strokeWidth: 2),
                                  )
                                : const Icon(Icons.wifi_find),
                            label: Text(_testing ? '测试中...' : '测试连接'),
                            style: OutlinedButton.styleFrom(
                              padding:
                                  const EdgeInsets.symmetric(vertical: 14.0),
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(12.0),
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(width: 12.0),
                        Expanded(
                          child: FilledButton.icon(
                            onPressed: _saving ? null : _save,
                            icon: _saving
                                ? const SizedBox(
                                    width: 16,
                                    height: 16,
                                    child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                        color: Colors.white),
                                  )
                                : const Icon(Icons.save),
                            label: Text(_saving ? '保存中...' : '保存配置'),
                            style: FilledButton.styleFrom(
                              padding:
                                  const EdgeInsets.symmetric(vertical: 14.0),
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(12.0),
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 24.0),

                    // 说明
                    Container(
                      padding: const EdgeInsets.all(16.0),
                      decoration: BoxDecoration(
                        color: Theme.of(context).cardColor,
                        borderRadius: BorderRadius.circular(12.0),
                        border: Border.all(
                            color: Theme.of(context).dividerColor),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              Icon(Icons.info_outline,
                                  size: 18,
                                  color: Theme.of(context)
                                      .textTheme
                                      .bodySmall
                                      ?.color),
                              const SizedBox(width: 8.0),
                              Text(
                                '使用说明',
                                style: TextStyle(
                                  fontWeight: FontWeight.w600,
                                  color: Theme.of(context)
                                      .textTheme
                                      .bodySmall
                                      ?.color,
                                ),
                              ),
                            ],
                          ),
                          const SizedBox(height: 8.0),
                          Text(
                            '1. 填写 Subsonic 服务器的内网和公网地址\n'
                            '2. 输入用户名和密码\n'
                            '3. 点击"测试连接"验证配置\n'
                            '4. 保存后可在"扫描音乐"页面扫描远程音乐\n'
                            '5. 播放远程音乐会自动缓存到本地',
                            style: TextStyle(
                              fontSize: 12.0,
                              color: Theme.of(context)
                                  .textTheme
                                  .bodySmall
                                  ?.color
                                  ?.withOpacity(0.8),
                              height: 1.5,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
    );
  }
}
