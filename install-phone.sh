#!/bin/bash
# 无线安装自动记账到手机：构建 + 自动发现 + 连接 + 覆盖安装（数据保留）
set -e
export JAVA_HOME="$HOME/android-dev-tools/jdk/Contents/Home"
export PATH="$HOME/android-dev-tools/sdk/platform-tools:$PATH"
cd "$(dirname "$0")/ledger-app"

echo "1/3 构建 APK..."
./gradlew :app:assembleDebug -q
APK=app/build/outputs/apk/debug/app-debug.apk

echo "2/3 发现手机（无线调试需开启，手机和 Mac 同一 Wi-Fi）..."
ADDR=$(adb mdns services 2>/dev/null | grep "_adb-tls-connect" | awk '{print $3}' | head -1)
if [ -z "$ADDR" ]; then
  echo "   未发现手机。请检查：手机已解锁、无线调试已开启、同一 Wi-Fi。"
  echo "   也可以直接运行: adb connect <无线调试页面显示的IP:端口>"
  exit 1
fi
adb connect "$ADDR" > /dev/null
echo "   已连接 $ADDR"

echo "3/3 安装..."
adb -s "$ADDR" install -r "$APK" && echo "✅ 已更新到手机（数据保留）"
