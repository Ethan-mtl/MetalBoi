#!/usr/bin/env bash
# Recon round 2: decompile Minecraft (mojmap) via Loom genSources and extract the
# specific classes MetalBoi needs to hook, plus extra javap dumps.
set -uo pipefail

OUT="ci-out"
mkdir -p "$OUT/src"

log() { echo "$@" | tee -a "$OUT/recon2-log.txt"; }

log "########## GEN SOURCES ##########"
./gradlew --no-daemon --stacktrace genSources > "$OUT/gensources.log" 2>&1
log "genSources exit=$?"
tail -25 "$OUT/gensources.log" | tee -a "$OUT/recon2-log.txt"

log ""
log "########## LOCATE SOURCES JAR ##########"
SRCJAR=""
while IFS= read -r j; do
  [ -z "$j" ] && continue
  if unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/client/Minecraft\.java"; then
    SRCJAR="$j"; break
  fi
done < <(find "$HOME/.gradle" .gradle build -name '*.jar' 2>/dev/null)
log "SRCJAR=$SRCJAR"

NAMED=""
while IFS= read -r j; do
  [ -z "$j" ] && continue
  if unzip -l "$j" 2>/dev/null | grep -q "net/minecraft/client/Minecraft\.class"; then
    NAMED="$j"; break
  fi
done < <(find "$HOME/.gradle" .gradle build -name '*.jar' 2>/dev/null)
log "NAMED_MC_JAR=$NAMED"

WANT="
net/minecraft/client/Minecraft.java
net/minecraft/client/renderer/VirtualScreen.java
net/minecraft/client/renderer/GameRenderer.java
net/minecraft/client/renderer/RenderPipelines.java
net/minecraft/client/gui/render/GuiRenderer.java
com/mojang/blaze3d/pipeline/MainTarget.java
com/mojang/blaze3d/pipeline/RenderTarget.java
com/mojang/blaze3d/pipeline/TextureTarget.java
com/mojang/blaze3d/pipeline/RenderPipeline.java
com/mojang/blaze3d/systems/RenderSystem.java
com/mojang/blaze3d/platform/Window.java
com/mojang/blaze3d/platform/MacosUtil.java
com/mojang/blaze3d/platform/GLX.java
com/mojang/blaze3d/opengl/GlDevice.java
com/mojang/blaze3d/opengl/GlRenderPass.java
com/mojang/blaze3d/opengl/GlProgram.java
com/mojang/blaze3d/textures/GpuTexture.java
com/mojang/blaze3d/textures/GpuTextureView.java
com/mojang/blaze3d/textures/TextureFormat.java
com/mojang/blaze3d/textures/FilterMode.java
"

if [ -n "$SRCJAR" ]; then
  for w in $WANT; do
    dest="$OUT/src/$(echo "$w" | tr '/' '_')"
    unzip -p "$SRCJAR" "$w" > "$dest" 2>/dev/null && log "extracted $w ($(wc -l < "$dest") lines)" || log "MISSING $w"
  done
fi

log ""
log "########## EXTRA JAVAP ##########"
dump() {
  local cls="$1"; local name
  name=$(echo "$cls" | tr './' '__')
  javap -cp "$NAMED" -p "$cls" > "$OUT/javap_$name.txt" 2>&1 \
    || echo "JAVAP FAILED $cls" >> "$OUT/javap_$name.txt"
  log "javap $cls ($(wc -l < "$OUT/javap_$name.txt") lines)"
}

for c in \
  net.minecraft.client.renderer.VirtualScreen \
  com.mojang.blaze3d.pipeline.MainTarget \
  'com.mojang.blaze3d.pipeline.MainTarget$Dimension' \
  com.mojang.blaze3d.pipeline.TextureTarget \
  com.mojang.blaze3d.platform.MacosUtil \
  com.mojang.blaze3d.platform.GLX \
  com.mojang.blaze3d.opengl.GlDevice \
  com.mojang.blaze3d.opengl.GlRenderPass \
  com.mojang.blaze3d.systems.RenderPass \
  com.mojang.blaze3d.systems.GpuDevice \
  com.mojang.blaze3d.systems.CommandEncoder \
  com.mojang.blaze3d.textures.GpuTexture \
  com.mojang.blaze3d.textures.GpuTextureView \
  com.mojang.blaze3d.textures.TextureFormat \
  com.mojang.blaze3d.textures.GpuSampler \
  net.minecraft.client.gui.render.GuiRenderer \
  net.minecraft.client.gui.screens.Screen \
  net.minecraft.network.chat.Component ; do
  dump "$c"
done

log ""
log "########## RENDERER PACKAGE CLASS LIST ##########"
if [ -n "$NAMED" ]; then
  unzip -l "$NAMED" | awk '{print $4}' | grep -E '^net/minecraft/client/renderer/[^/]*\.class$' | sort > "$OUT/classes-renderer.txt"
  unzip -l "$NAMED" | awk '{print $4}' | grep -iE 'virtualscreen|maintarget|texturetarget' | sort | tee -a "$OUT/recon2-log.txt"
fi

log "########## DONE ##########"
touch "$OUT/recon2.done"
du -sh "$OUT"
