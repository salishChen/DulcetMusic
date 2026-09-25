# ============================================================================
# 交叉编译 EasyTier 核心（easytier-core）为 Android 可执行文件
#
# 用法（Windows / PowerShell）：
#   .\tools\build_easytier.ps1                # 默认 v2.6.4 / arm64-v8a
#   .\tools\build_easytier.ps1 -Tag v2.6.4 -Abi arm64-v8a
#
# 前置条件与已知坑（本脚本已自动处理）：
#   - Rust (rustup) + Android NDK（自动探测 ANDROID_NDK_HOME / ANDROID_HOME\ndk\*）
#   - protoc：prost-wkt-types 构建依赖，缺失时自动下载到 %LOCALAPPDATA%\protoc
#   - libclang.dll：bindgen 依赖，取自 LLVM 或 `pip install libclang`
#   - clang 内置头文件（stddef.h）：pip 版 libclang 不带；kcp-sys 的 build.rs
#     按 ':' 切分 KCP_SYS_EXTRA_HEADER_PATH，Windows 盘符会被切碎 ——
#     脚本在 kcp-sys 源码目录内建目录联接 clanginc，改用无盘符相对路径
#   - API 级别 24：getifaddrs/freeifaddrs 在 bionic 中 API 24 才可用
#   - EasyTier 的 rust-toolchain.toml 固定了工具链版本，Rust 目标要装到该工具链
#
# 产物：app\src\main\jniLibs\<abi>\libeasytier.so
#   （以 .so 命名以便随 APK 分发；运行时从 nativeLibraryDir 直接 exec，
#     Android 10+ 允许执行 nativeLibraryDir 中的只读文件。）
# ============================================================================
param(
    [string]$Tag = "v2.6.4",
    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86_64")]
    [string]$Abi = "arm64-v8a",
    [string]$Repo = "https://github.com/EasyTier/EasyTier.git",
    [string]$WorkDir = (Join-Path $env:TEMP "easytier-build")
)

# 原生命令（rustup/cargo/git）的 stderr 会触发 NativeCommandError，
# 统一用 Continue + 显式检查 $LASTEXITCODE 控制流程
$ErrorActionPreference = "Continue"

function Invoke-Step($desc, $cmd) {
    Write-Host "[easytier] $desc"
    & cmd /c "$cmd 2>&1"
    if ($LASTEXITCODE -ne 0) { throw "[easytier] 失败：$desc (exit $LASTEXITCODE)" }
}

# ---- 1. 定位 Android NDK ----
if ($env:ANDROID_NDK_HOME -and (Test-Path $env:ANDROID_NDK_HOME)) {
    $ndk = $env:ANDROID_NDK_HOME
} else {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "D:\code\DevEnvironment\Android\SDK" }
    $ndk = Get-ChildItem (Join-Path $sdk "ndk") -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $ndk) { throw "未找到 Android NDK，请设置 ANDROID_NDK_HOME" }
Write-Host "[easytier] NDK: $ndk"

# ---- 1.5 准备 protoc（prost-wkt-types 等构建依赖） ----
& cmd /c "protoc --version >nul 2>&1"
if ($LASTEXITCODE -ne 0) {
    $protocDir = Join-Path $env:LOCALAPPDATA "protoc"
    $protocExe = Join-Path $protocDir "bin\protoc.exe"
    if (-not (Test-Path $protocExe)) {
        $zip = Join-Path $env:TEMP "protoc-29.3-win64.zip"
        Invoke-Step "下载 protoc 29.3" `
            "curl.exe -L -o `"$zip`" https://github.com/protocolbuffers/protobuf/releases/download/v29.3/protoc-29.3-win64.zip"
        New-Item -ItemType Directory -Force -Path $protocDir | Out-Null
        Invoke-Step "解压 protoc" "tar -xf `"$zip`" -C `"$protocDir`""
    }
    $env:PROTOC = $protocExe
    Write-Host "[easytier] PROTOC: $env:PROTOC"
}

# ---- 1.6 准备 libclang（bindgen 依赖） ----
if (-not $env:LIBCLANG_PATH) {
    foreach ($c in @(
            "C:\Program Files\LLVM\bin",
            "$env:LOCALAPPDATA\Programs\LLVM\bin",
            "D:\code\DevEnvironment\python\Lib\site-packages\clang\native"
        )) {
        if ($c -and (Test-Path "$c\libclang.dll")) { $env:LIBCLANG_PATH = $c; break }
    }
}
if (-not $env:LIBCLANG_PATH) {
    Invoke-Step "安装 libclang（pip）" "python -m pip install --quiet libclang"
    $pipNative = python -c "import clang,os;print(os.path.join(os.path.dirname(clang.__file__),'native'))"
    if (Test-Path "$pipNative\libclang.dll") { $env:LIBCLANG_PATH = $pipNative }
}
if (-not $env:LIBCLANG_PATH) { throw "未找到 libclang.dll：请安装 LLVM 或 pip install libclang" }
Write-Host "[easytier] LIBCLANG_PATH: $env:LIBCLANG_PATH"

# clang 内置头文件目录（stddef.h 等）
$builtinInclude = Get-ChildItem "$ndk\toolchains\llvm\prebuilt\windows-x86_64\lib\clang\*\include" `
    -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path "$($_.FullName)\stddef.h" } |
    Select-Object -First 1

# ---- 2. 准备 Rust 目标与 cargo-ndk ----
$rustTarget = switch ($Abi) {
    "arm64-v8a"   { "aarch64-linux-android" }
    "armeabi-v7a" { "armv7-linux-androideabi" }
    "x86_64"      { "x86_64-linux-android" }
}
Invoke-Step "安装 Rust 目标 $rustTarget" "rustup target add $rustTarget"

& cmd /c "cargo ndk --version >nul 2>&1"
if ($LASTEXITCODE -ne 0) {
    Invoke-Step "安装 cargo-ndk" "cargo install cargo-ndk --locked"
}

# ---- 3. 拉取源码（浅克隆指定 tag） ----
$src = Join-Path $WorkDir "EasyTier-$Tag"
if (-not (Test-Path $src)) {
    Invoke-Step "克隆 $Repo @ $Tag" "git clone --depth 1 --branch $Tag `"$Repo`" `"$src`""
}

# ---- 4. 编译 easytier-core（release） ----
$env:ANDROID_NDK_HOME = $ndk

# kcp-sys 的 build.rs 按 ':' 切分 KCP_SYS_EXTRA_HEADER_PATH，Windows 盘符会被切碎：
# 在 kcp-sys 源码目录内建目录联接 clanginc，改用无盘符相对路径传入
if ($builtinInclude) {
    $kcpCheckout = Get-ChildItem "$env:USERPROFILE\.cargo\git\checkouts\kcp-sys-*\*" `
        -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($kcpCheckout) {
        $junction = Join-Path $kcpCheckout.FullName "clanginc"
        if (-not (Test-Path $junction)) {
            cmd /c mklink /J `"$junction`" `"$($builtinInclude.FullName)`" | Out-Null
        }
        if (Test-Path (Join-Path $junction "stddef.h")) {
            $env:KCP_SYS_EXTRA_HEADER_PATH = "clanginc"
            Write-Host "[easytier] KCP_SYS_EXTRA_HEADER_PATH: clanginc -> $($builtinInclude.FullName)"
        }
    }
}

Push-Location $src
try {
    # 在源码目录内安装目标：EasyTier 的 rust-toolchain.toml 固定了工具链版本，
    # 目标必须装到该工具链（否则报 can't find crate for `core`）
    Invoke-Step "安装 Rust 目标 $rustTarget（仓库 pin 的工具链）" "rustup target add $rustTarget"
    # -P 24：getifaddrs/freeifaddrs 等符号 API 24 才进入 bionic，与 app minSdk 一致
    # （cargo-ndk 的 -P 是平台/API 级别，-p 是包名）
    Invoke-Step "编译 easytier-core ($rustTarget, release)" `
        "cargo ndk -t $Abi -P 24 build --release -p easytier --bin easytier-core"
} finally {
    Pop-Location
}

# ---- 5. 拷贝产物到 jniLibs ----
$releaseDir = Join-Path $src "target\$rustTarget\release"
$exe = Get-ChildItem $releaseDir -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -eq "easytier-core" -or $_.Name -eq "easytier-core.exe" } |
    Select-Object -First 1
if (-not $exe) {
    $candidates = (Get-ChildItem $releaseDir -File | Select-Object -ExpandProperty Name) -join ", "
    throw "未找到编译产物 easytier-core（目录内：$candidates）"
}

$outDir = Join-Path $PSScriptRoot "..\app\src\main\jniLibs\$Abi"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$out = Join-Path $outDir "libeasytier.so"
Copy-Item $exe.FullName $out -Force
Write-Host "[easytier] 完成: $out ($([math]::Round((Get-Item $out).Length / 1MB, 1)) MB)"
