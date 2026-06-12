#!/usr/bin/env bash
set -euo pipefail

real_ffmpeg="${JELLYFIN_REAL_FFMPEG:-/usr/lib/jellyfin-ffmpeg/ffmpeg}"

if [ "${JELLYFIN_FORCE_BROWSER_SAFE_H264:-false}" != "true" ]; then
    exec "$real_ffmpeg" "$@"
fi

args=("$@")
uses_libx264=0
uses_explicit_video_codec=0
uses_video_copy=0
uses_hevc_hls_copy=0
uses_hls_output=0
hls_time="6"
force_browser_safe="${JELLYFIN_FORCE_BROWSER_SAFE_H264:-false}"
force_browser_safe="$(printf '%s' "$force_browser_safe" | tr '[:upper:]' '[:lower:]')"
hls_time_override="${JELLYFIN_HLS_TIME:-}"
for ((i = 0; i < ${#args[@]}; i++)); do
    arg="${args[$i]}"
    if [ "$arg" = "libx264" ]; then
        uses_libx264=1
    elif [ "$arg" = "copy" ]; then
        uses_video_copy=1
    elif [ "$arg" = "hevc_mp4toannexb" ] || [ "$arg" = "hvc1" ]; then
        uses_hevc_hls_copy=1
    fi
    case "$arg" in
        -codec:v|-codec:v:*|-c:v|-c:v:*)
            uses_explicit_video_codec=1
            ;;
        -f)
            if [ $((i + 1)) -lt ${#args[@]} ] && [ "${args[$((i + 1))]}" = "hls" ]; then
                uses_hls_output=1
            fi
            ;;
        *.m3u8)
            uses_hls_output=1
            ;;
    esac
done
for ((i = 0; i < ${#args[@]}; i++)); do
    if [ "${args[$i]}" = "-hls_time" ] && [ $((i + 1)) -lt ${#args[@]} ]; then
        hls_time="${args[$((i + 1))]}"
        break
    fi
done

needs_default_hls_transcode=0
if [ "$uses_hls_output" -eq 1 ] && [ "$uses_explicit_video_codec" -ne 1 ]; then
    needs_default_hls_transcode=1
fi

if [ "$needs_default_hls_transcode" -ne 1 ] && [ "$uses_libx264" -ne 1 ] && { [ "$uses_video_copy" -ne 1 ] || [ "$uses_hevc_hls_copy" -ne 1 ]; }; then
    if [ "$force_browser_safe" != "true" ] || [ "$uses_hls_output" -ne 1 ]; then
        exec "$real_ffmpeg" "$@"
    fi
fi

profile="${JELLYFIN_TRANSCODE_H264_PROFILE:-baseline}"
level="${JELLYFIN_TRANSCODE_H264_LEVEL:-31}"
max_width="${JELLYFIN_TRANSCODE_MAX_WIDTH:-1280}"
max_height="${JELLYFIN_TRANSCODE_MAX_HEIGHT:-720}"
log_file="${JELLYFIN_FFMPEG_WEBSAFE_LOG:-/cache/websafe-ffmpeg.log}"
browser_safe_filter="scale=trunc(min(max(iw\\,ih*a)\\,min(${max_width}\\,${max_height}*a))/2)*2:trunc(min(max(iw/a\\,ih)\\,min(${max_width}/a\\,${max_height}))/2)*2,format=yuv420p"
out=()
inserted_video_filter=0

for ((i = 0; i < ${#args[@]}; i++)); do
    arg="${args[$i]}"
    next=""
    if [ $((i + 1)) -lt ${#args[@]} ]; then
        next="${args[$((i + 1))]}"
    fi

    case "$arg" in
        -hls_time)
            out+=("$arg")
            if [ -n "$next" ]; then
                if [ -n "$hls_time_override" ]; then
                    out+=("$hls_time_override")
                    hls_time="$hls_time_override"
                else
                    out+=("$next")
                fi
                i=$((i + 1))
            fi
            continue
            ;;
        -codec:v|-codec:v:*)
            out+=("$arg")
            if [ -n "$next" ]; then
                if [ "$uses_hevc_hls_copy" -eq 1 ] && [ "$next" = "copy" ]; then
                    out+=("libx264")
                elif [ "$force_browser_safe" = "true" ] && [[ "$next" == hevc* || "$next" == av1* ]]; then
                    out+=("h264_nvenc")
                else
                    out+=("$next")
                fi
                i=$((i + 1))
            fi
            continue
            ;;
        -tag:v|-tag:v:*)
            if [ "$uses_hevc_hls_copy" -eq 1 ] && [ "$next" = "hvc1" ]; then
                i=$((i + 1))
                continue
            fi
            ;;
        -bsf:v|-bsf:v:*)
            if [ "$uses_hevc_hls_copy" -eq 1 ] && [ "$next" = "hevc_mp4toannexb" ]; then
                i=$((i + 1))
                continue
            fi
            ;;
        -copyts)
            if [ "$uses_hevc_hls_copy" -eq 1 ]; then
                continue
            fi
            ;;
        -avoid_negative_ts)
            out+=("$arg")
            if [ -n "$next" ]; then
                if [ "$uses_hevc_hls_copy" -eq 1 ]; then
                    out+=("make_zero")
                else
                    out+=("$next")
                fi
                i=$((i + 1))
            fi
            continue
            ;;
        -profile:v|-profile:v:*)
            out+=("$arg")
            if [ -n "$next" ]; then
                out+=("$profile")
                i=$((i + 1))
            fi
            continue
            ;;
        -level|-level:*)
            out+=("$arg")
            if [ -n "$next" ]; then
                out+=("$level")
                i=$((i + 1))
            fi
            continue
            ;;
        -x264opts|-x264opts:*)
            out+=("$arg")
            if [ -n "$next" ]; then
                if [[ "$next" == *bframes=* ]]; then
                    out+=("$next")
                else
                    out+=("${next}:bframes=0")
                fi
                i=$((i + 1))
            fi
            continue
            ;;
        -vf|-filter:v|-filter:v:*)
            out+=("$arg")
            if [ -n "$next" ]; then
                filter="$(printf '%s' "$next" \
                    | sed -E "s/min\\(1920\\\\,1080\\*a\\)/min(${max_width}\\\\,${max_height}*a)/g; s/min\\(1920\\/a\\\\,1080\\)/min(${max_width}\\/a\\\\,${max_height})/g")"
                out+=("$filter")
                inserted_video_filter=1
                i=$((i + 1))
            fi
            continue
            ;;
    esac

    out+=("$arg")
done

if [ "${#out[@]}" -gt 0 ]; then
    last_index=$((${#out[@]} - 1))
    last_arg="${out[$last_index]}"
    if [[ "$last_arg" == /cache/transcodes/* || "$last_arg" == *.m3u8 || "$last_arg" == *.mp4 ]]; then
        video_options=()
        if [ "$force_browser_safe" = "true" ] || [ "$needs_default_hls_transcode" -eq 1 ] || [ "$uses_hevc_hls_copy" -eq 1 ]; then
            video_options=("-profile:v:0" "$profile" "-level:v:0" "$level" "-bf:v:0" "0" "-refs:v:0" "1")
        fi
        if [ "$needs_default_hls_transcode" -eq 1 ]; then
            video_options=(
                "-map" "0:v:0"
                "-map" "0:a:0?"
                "-codec:v:0" "libx264"
                "-preset:v:0" "veryfast"
                "-crf:v:0" "23"
                "-force_key_frames:v:0" "expr:gte(t,n_forced*${hls_time})"
                "-sc_threshold:v:0" "0"
                "-vf" "$browser_safe_filter"
                "${video_options[@]}"
                "-codec:a:0" "libfdk_aac"
                "-ac:a:0" "2"
                "-ab:a:0" "256000"
                "-af:a:0" "volume=2"
            )
        elif [ "$uses_hevc_hls_copy" -eq 1 ]; then
            if [ "$inserted_video_filter" -ne 1 ]; then
                video_options=("-vf" "$browser_safe_filter" "${video_options[@]}")
            fi
            video_options=("-preset:v:0" "veryfast" "-crf:v:0" "23" "-force_key_frames:v:0" "expr:gte(t,n_forced*${hls_time})" "-sc_threshold:v:0" "0" "${video_options[@]}")
        fi
        out=("${out[@]:0:$last_index}" "${video_options[@]}" "$last_arg")
    fi
fi

{
    printf '[websafe-ffmpeg] '
    printf '%q ' "$real_ffmpeg" "${out[@]}"
    printf '\n'
} >>"$log_file" 2>/dev/null || true

exec "$real_ffmpeg" "${out[@]}"
