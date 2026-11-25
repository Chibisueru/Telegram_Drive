#!/usr/bin/env bash
# Makes short test files in every format the built-in players should handle (needs ffmpeg).
# Used by MediaEngineTest and the CI self-test:  desktop/make-media-samples.sh [out-dir]
set -euo pipefail
out="${1:-$(dirname "$0")/build/media-samples}"
mkdir -p "$out" && cd "$out"
q=(-loglevel error -y)
ffmpeg "${q[@]}" -f lavfi -i "testsrc=size=300x300:rate=1:duration=1" -frames:v 1 cover.png
tone=(-f lavfi -i "sine=frequency=440:duration=6")
ffmpeg "${q[@]}" "${tone[@]}" -i cover.png -map 0:a -map 1:v -c:a libmp3lame -c:v mjpeg -disposition:v attached_pic \
  -metadata title="Tone MP3" -metadata artist="Test Artist" -metadata album="Test Album" tone.mp3
ffmpeg "${q[@]}" "${tone[@]}" -i cover.png -map 0:a -map 1:v -c:a flac -c:v png -disposition:v attached_pic \
  -metadata title="Tone FLAC" -metadata artist="Test Artist" -metadata album="Test Album" tone.flac
ffmpeg "${q[@]}" "${tone[@]}" -c:a libopus -metadata title="Tone Opus" -metadata artist="Test Artist" tone.opus
ffmpeg "${q[@]}" "${tone[@]}" -c:a libvorbis -metadata title="Tone Vorbis" tone.ogg
ffmpeg "${q[@]}" "${tone[@]}" -c:a aac -metadata title="Tone AAC" tone.m4a
ffmpeg "${q[@]}" "${tone[@]}" tone.wav
video=(-f lavfi -i "testsrc2=size=640x360:rate=30:duration=6" -f lavfi -i "sine=frequency=330:duration=6")
ffmpeg "${q[@]}" "${video[@]}" -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest video.mp4
ffmpeg "${q[@]}" "${video[@]}" -c:v libvpx-vp9 -b:v 500k -c:a libopus -shortest video.webm
ffmpeg "${q[@]}" -i video.mp4 -c copy video.mkv
ls "$out"
