#!/usr/bin/env bash
# Recon round 3: the GL command encoder / texture-view / state-manager internals that
# MetalBoi's upscale-present pass builds on.
set -uo pipefail

OUT="ci-out"
mkdir -p "$OUT/src3" "$OUT/shaders"

log() { echo "$@" | tee -a "$OUT/recon3-log.txt"; }

log "########## GEN SOURCES ##########"
./gradlew --no-daemon --stacktrace genSources >"$OUT/gensources3.log" 2>&1
log "genSources exit=$?"
tail -15 "$OUT/gensources3.log" | tee -a "$OUT/recon3-log.txt"

SRCJAR=""
while IFS= read -r j; do
  [ -z "$j" ] && continue
  if unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/client/Minecraft\.java"; then SRCJAR="$j"; break; fi
done < <(find "$HOME/.gradle" .gradle build -name '*.jar' 2>/dev/null)
log "SRCJAR=$SRCJAR"

NAMED=""
while IFS= read -r j; do
  [ -z "$j" ] && continue
  if unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/client/Minecraft\.class"; then NAMED="$j"; break; fi
done < <(find "$HOME/.gradle" .gradle build -name '*.jar' 2>/dev/null)
log "NAMED_MC_JAR=$NAMED"

WANT="
com/mojang/blaze3d/opengl/GlCommandEncoder.java
com/mojang/blaze3d/opengl/GlTextureView.java
com/mojang/blaze3d/opengl/GlTexture.java
com/mojang/blaze3d/opengl/GlSampler.java
com/mojang/blaze3d/opengl/GlStateManager.java
com/mojang/blaze3d/opengl/GlProgram.java
com/mojang/blaze3d/opengl/GlConst.java
com/mojang/blaze3d/systems/SamplerCache.java
com/mojang/blaze3d/systems/GpuDevice.java
com/mojang/blaze3d/textures/GpuTextureView.java
com/mojang/blaze3d/textures/GpuSampler.java
com/mojang/blaze3d/shaders/ShaderSource.java
com/mojang/blaze3d/shaders/ShaderType.java
com/mojang/blaze3d/vertex/DefaultVertexFormat.java
net/minecraft/client/renderer/ShaderManager.java
net/minecraft/client/renderer/ShaderDefines.java
"

if [ -n "$SRCJAR" ]; then
  for w in $WANT; do
    dest="$OUT/src3/$(echo "$w" | tr '/' '_')"
    if unzip -p "$SRCJAR" "$w" >"$dest" 2>/dev/null && [ -s "$dest" ]; then
      log "extracted $w ($(wc -l <"$dest") lines)"
    else
      log "MISSING $w"
      rm -f "$dest"
    fi
  done
else
  log "!!! no sources jar found"
fi

log ""
log "########## JAVAP ##########"
if [ -n "$NAMED" ]; then
  dump() {
    local cls="$1"
    local name
    name=$(echo "$cls" | tr './' '__')
    javap -cp "$NAMED" -p "$cls" >"$OUT/javap_$name.txt" 2>&1 || echo "JAVAP FAILED $cls" >>"$OUT/javap_$name.txt"
    log "javap $cls ($(wc -l <"$OUT/javap_$name.txt") lines)"
  }
  for c in \
    com.mojang.blaze3d.opengl.GlCommandEncoder \
    com.mojang.blaze3d.opengl.GlTextureView \
    com.mojang.blaze3d.opengl.GlTexture \
    com.mojang.blaze3d.opengl.GlSampler \
    com.mojang.blaze3d.opengl.GlStateManager \
    com.mojang.blaze3d.systems.SamplerCache \
    com.mojang.blaze3d.systems.GpuDevice \
    com.mojang.blaze3d.vertex.DefaultVertexFormat; do
    dump "$c"
  done
fi

log "########## DONE ##########"
touch "$OUT/recon3.done"
du -sh "$OUT"
