/// Subsonic 服务器配置实体
///
/// 单条配置记录，包含内网和公网两个 URL。
/// 优先使用内网连接，内网不可达时自动回退公网。
class SubsonicConfig {
  final int? id;
  final String intranetUrl;
  final String publicUrl;
  final String username;
  final String password;
  final String? serverName;
  final bool isActive;

  const SubsonicConfig({
    this.id,
    required this.intranetUrl,
    required this.publicUrl,
    required this.username,
    required this.password,
    this.serverName,
    this.isActive = true,
  });

  factory SubsonicConfig.fromMap(Map<String, dynamic> m) => SubsonicConfig(
        id: m['id'] as int?,
        intranetUrl: m['intranetUrl'] as String,
        publicUrl: m['publicUrl'] as String,
        username: m['username'] as String,
        password: m['password'] as String,
        serverName: m['serverName'] as String?,
        isActive: (m['isActive'] as int? ?? 1) == 1,
      );

  Map<String, dynamic> toMap() => {
        if (id != null) 'id': id,
        'intranetUrl': intranetUrl,
        'publicUrl': publicUrl,
        'username': username,
        'password': password,
        'serverName': serverName,
        'isActive': isActive ? 1 : 0,
      };

  SubsonicConfig copyWith({
    int? id,
    String? intranetUrl,
    String? publicUrl,
    String? username,
    String? password,
    String? serverName,
    bool? isActive,
  }) =>
      SubsonicConfig(
        id: id ?? this.id,
        intranetUrl: intranetUrl ?? this.intranetUrl,
        publicUrl: publicUrl ?? this.publicUrl,
        username: username ?? this.username,
        password: password ?? this.password,
        serverName: serverName ?? this.serverName,
        isActive: isActive ?? this.isActive,
      );
}
