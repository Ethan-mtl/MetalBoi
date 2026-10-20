#!/usr/bin/env bash
# Recon: dump the exact Minecraft 1.21.11 / LWJGL / Sodium API surface that this
# sandbox cannot reach directly, so the mod code can be written against real signatures.
set -uo pipefail

OUT="ci-out"
rm -rf "$OUT"
mkdir -p "$OUT"

log() { echo "$@" | tee -a "$OUT/recon-log.txt"; }

log "########## ENVIRONMENT ##########"
java -version 2>&1 | tee -a "$OUT/recon-log.txt"
log "pwd: $(pwd)"

log ""
log "########## MC 1.21.11 VERSION MANIFEST ##########"
curl -sS "https://piston-meta.mojang.com/mcgame/version_manifest_v2.json" -o "$OUT/version_manifest_v2.json" || log "manifest fetch failed"
python3 - <<'PY' 2>&1 | tee -a "$OUT/recon-log.txt"
import json, urllib.request
try:
    d = json.load(open('ci-out/version_manifest_v2.json'))
    vs = [x for x in d['versions'] if x['id'] == '1.21.11']
    print('1.21.11 manifest entry:', vs)
    if vs:
        m = json.load(urllib.request.urlopen(vs[0]['url']))
        open('ci-out/mc-1.21.11.json','w').write(json.dumps(m, indent=1))
        print('javaVersion:', m.get('javaVersion'))
        print('assetIndex:', m.get('assetIndex', {}).get('id'))
        print('--- lwjgl / opengl libs ---')
        for l in m.get('libraries', []):
            n = l['name']
            if 'lwjgl' in n.lower():
                print('  ', n)
except Exception as e:
    print('ERR', e)
PY

log ""
log "########## SODIUM COORDINATE PROBES ##########"
for u in \
  "https://maven.caffeinemc.net/releases/net/caffeinemc/sodium-fabric/maven-metadata.xml" \
  "https://maven.caffeinemc.net/snapshots/net/caffeinemc/sodium-fabric/maven-metadata.xml" \
  "https://maven.fabricmc.net/net/caffeinemc/sodium-fabric/maven-metadata.xml" \
  "https://api.modrinth.com/maven/maven.modrinth/sodium/maven-metadata.xml" ; do
  code=$(curl -sS -o "$OUT/probe.xml" -w "%{http_code}" "$u" || echo "000")
  log "--- $u -> http=$code"
  if [ "$code" = "200" ]; then
    grep -o '<version>[^<]*</version>' "$OUT/probe.xml" | tail -30 | tee -a "$OUT/recon-log.txt"
  fi
done

log "--- Modrinth API: sodium versions for 1.21.11 / fabric ---"
curl -sS "https://api.modrinth.com/v2/project/sodium/version?game_versions=%5B%221.21.11%22%5D&loaders=%5B%22fabric%22%5D" -o "$OUT/modrinth_sodium.json" || log "modrinth fetch failed"
python3 - <<'PY' 2>&1 | tee -a "$OUT/recon-log.txt"
import json
try:
    d = json.load(open('ci-out/modrinth_sodium.json'))
    for v in d[:10]:
        print(v.get('version_number'), '|', v.get('name'), '|', [f.get('filename') for f in v.get('files', [])])
except Exception as e:
    print('ERR', e)
PY
rm -f "$OUT/probe.xml"

log ""
log "########## GRADLE DEPENDENCY TREE ##########"
./gradlew --no-daemon -q dependencies --configuration runtimeClasspath > "$OUT/deps-runtime.txt" 2>&1 || log "deps task failed"
grep -iE "lwjgl|opengl|minecraft|fabric-loader" "$OUT/deps-runtime.txt" | sort -u | head -60 | tee -a "$OUT/recon-log.txt"

log ""
log "########## LOCATE NAMED MINECRAFT JAR ##########"
NAMED=""
while IFS= read -r j; do
  [ -z "$j" ] && continue
  if unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/client/Minecraft\.class"; then
    NAMED="$j"; break
  fi
done < <(find "$HOME/.gradle" .gradle build -name '*.jar' 2>/dev/null)
log "NAMED_MC_JAR=$NAMED"

if [ -z "$NAMED" ]; then
  log "!!! Could not locate the mojmap-named Minecraft jar; dumping all candidate jars"
  find "$HOME/.gradle" .gradle build -name '*.jar' 2>/dev/null | head -60 | tee -a "$OUT/recon-log.txt"
  exit 0
fi

cp_classlist() {
  unzip -l "$NAMED" | awk '{print $4}' | grep -E "$1" | sort > "$OUT/$2" || true
}

log ""
log "########## CLASS LISTINGS ##########"
cp_classlist '^net/minecraft/client/[^/]*\.class$'                                  'classes-client-root.txt'
cp_classlist '^net/minecraft/client/renderer/[^/]*\.class$'                          'classes-renderer.txt'
cp_classlist '^net/minecraft/client/gl/'                                             'classes-client-gl.txt'
cp_classlist '^com/mojang/blaze3d/'                                                  'classes-blaze3d.txt'
cp_classlist '^net/minecraft/client/gui/[^/]*\.class$'                               'classes-gui-root.txt'
cp_classlist '^net/minecraft/client/gui/screens/[^/]*\.class$'                       'classes-screens.txt'

log "--- framebuffer/target/present/post/pipeline related classes ---"
unzip -l "$NAMED" | awk '{print $4}' | grep -E '\.class$' \
  | grep -iE 'framebuffer|rendertarget|present|postchain|postpass|renderpipeline|mainsource|targets' \
  | sort | tee "$OUT/classes-fb-related.txt" | tee -a "$OUT/recon-log.txt" | head -80

log ""
log "########## JAVAP DUMPS ##########"
dump() {
  local cls="$1"
  local name
  name=$(echo "$cls" | tr './' '__')
  javap -cp "$NAMED" -p "$cls" > "$OUT/javap_$name.txt" 2>&1 \
    || echo "JAVAP FAILED for $cls" >> "$OUT/javap_$name.txt"
  log "dumped $cls -> javap_$name.txt ($(wc -l < "$OUT/javap_$name.txt") lines)"
}

for c in \
  net.minecraft.client.Minecraft \
  net.minecraft.client.renderer.GameRenderer \
  net.minecraft.client.renderer.LevelRenderer \
  net.minecraft.client.gui.Gui \
  net.minecraft.client.DeltaTracker \
  com.mojang.blaze3d.platform.Window \
  com.mojang.blaze3d.pipeline.RenderTarget \
  com.mojang.blaze3d.systems.RenderSystem \
  net.minecraft.client.Options \
  net.minecraft.client.GraphicsStatus ; do
  dump "$c"
done

# Also dump every framebuffer-related class found above.
while IFS= read -r p; do
  [ -z "$p" ] && continue
  cls=$(echo "$p" | sed 's#/#.#g; s#\.class$##')
  dump "$cls"
done < "$OUT/classes-fb-related.txt"

log ""
log "########## KEY SIGNATURE GREPS ##########"
{
  echo "=== Minecraft: target/framebuffer/resize/window ==="
  grep -inE 'target|framebuffer|resize|window|getDisplay|swapBuffers|blit' "$OUT/javap_net.minecraft.client.Minecraft.txt" 2>/dev/null | head -60
  echo
  echo "=== GameRenderer: render/blit/target ==="
  grep -inE 'render|blit|target|post|resize' "$OUT/javap_net.minecraft.client.renderer.GameRenderer.txt" 2>/dev/null | head -60
  echo
  echo "=== Window: size getters ==="
  grep -inE 'Width|Height|Screen|Framebuffer|setScale|getScale' "$OUT/javap_com.mojang.blaze3d.platform.Window.txt" 2>/dev/null | head -60
  echo
  echo "=== RenderTarget: all ==="
  head -80 "$OUT/javap_com.mojang.blaze3d.pipeline.RenderTarget.txt" 2>/dev/null
} | tee "$OUT/signature-greps.txt" | tee -a "$OUT/recon-log.txt"

log ""
log "########## DONE ##########"
touch "$OUT/recon.done"
du -sh "$OUT"
