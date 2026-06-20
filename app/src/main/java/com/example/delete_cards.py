import sys

with open('app/src/main/java/com/example/MainActivity.kt', 'r') as f:
    lines = f.readlines()

start_idx = -1
end_idx = -1

for i, line in enumerate(lines):
    if '// 1. Google Edge AI Model Settings Card' in line:
        start_idx = i
    if '// 2. Strict Transcription Switch' in line:
        end_idx = i
        break

if start_idx != -1 and end_idx != -1:
    del lines[start_idx:end_idx]
    with open('app/src/main/java/com/example/MainActivity.kt', 'w') as f:
        f.writelines(lines)
    print(f"Deleted lines from {start_idx} to {end_idx}")
else:
    print(f"Could not find indices: start={start_idx}, end={end_idx}")
