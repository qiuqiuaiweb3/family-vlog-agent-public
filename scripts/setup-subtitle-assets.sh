#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
font_root="$repo_root/.local-subtitle/assets/fonts"
subtitle_license_root="$repo_root/third_party/subtitle/assets/licenses"
subtitle_resource_root="$repo_root/.local-subtitle/res/drawable-xxhdpi"
font_url="https://raw.githubusercontent.com/notofonts/noto-cjk/Sans2.004/Sans/OTF/SimplifiedChinese/NotoSansCJKsc-Bold.otf"
google_attribution_url="https://docs.cloud.google.com/static/translate/images/google-translate-attribution.zip"

for target in "$repo_root/.local-subtitle" "$font_root" "$subtitle_license_root" "$subtitle_resource_root"; do
    if [[ -L "$target" ]]; then
        echo "字幕资产目录不得为符号链接：$target" >&2
        exit 1
    fi
done

temp_root=$(mktemp -d)
if [[ -z "$temp_root" || "$temp_root" != /tmp/* || ! -d "$temp_root" || -L "$temp_root" ]]; then
    echo "无法建立安全的临时目录" >&2
    exit 1
fi

cleanup() {
    if [[ -n "$temp_root" && "$temp_root" == /tmp/* && -d "$temp_root" && ! -L "$temp_root" ]]; then
        rm -r -- "$temp_root"
    fi
}
trap cleanup EXIT

download() {
    local url=$1
    local output=$2
    curl --fail --location --proto '=https' --tlsv1.2 "$url" --output "$output"
    if [[ ! -f "$output" || -L "$output" || ! -s "$output" ]]; then
        echo "下载的字幕资产无效：$url" >&2
        exit 1
    fi
}

attribution_zip="$temp_root/google-translate-attribution.zip"
attribution_image="$temp_root/google_translate_attribution.png"
download "$google_attribution_url" "$attribution_zip"
unzip -p "$attribution_zip" "png/color-regular@3x.png" > "$attribution_image"
if [[ ! -s "$attribution_image" || -L "$attribution_image" || \
      $(wc -c < "$attribution_image") -ne 12770 || \
      $(LC_ALL=C od -An -tu4 --endian=big -j16 -N8 "$attribution_image" | xargs) != "528 48" ]]; then
    echo "Google Translate 官方归属图形长度或尺寸不符合冻结制品" >&2
    exit 1
fi

font_file="$temp_root/NotoSansCJKsc-Bold.otf"
download "$font_url" "$font_file"
if [[ $(wc -c < "$font_file") -ne 17002248 || $(LC_ALL=C head -c 4 "$font_file") != "OTTO" ]]; then
    echo "Noto Sans CJK SC 2.004 字体长度或文件头不符合冻结制品" >&2
    exit 1
fi

for license_file in "$subtitle_license_root"/*; do
    if [[ ! -f "$license_file" || -L "$license_file" ]]; then
        echo "仓库受控的字幕许可资产缺失或为符号链接：$license_file" >&2
        exit 1
    fi
    if [[ $(wc -c < "$license_file") -lt 100 ]]; then
        echo "第三方许可文本异常短：$license_file" >&2
        exit 1
    fi
done

mkdir -p "$font_root" "$subtitle_resource_root"
install -m 0644 "$font_file" "$font_root/NotoSansCJKsc-Bold.otf"
install -m 0644 "$attribution_image" "$subtitle_resource_root/google_translate_attribution.png"

echo "字幕字体与 Google Translate 官方归属图形已准备完成；仓库受控许可资产已验证"
