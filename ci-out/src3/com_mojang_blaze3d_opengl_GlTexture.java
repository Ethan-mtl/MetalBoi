package com.mojang.blaze3d.opengl;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntFunction;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

@Environment(EnvType.CLIENT)
public class GlTexture extends GpuTexture {
	private static final int EMPTY = -1;
	protected final int id;
	private int firstFboId = -1;
	private int firstFboDepthId = -1;
	@Nullable
	private Int2IntMap fboCache;
	protected boolean closed;
	private int views;

	protected GlTexture(@GpuTexture.Usage int i, String string, TextureFormat textureFormat, int j, int k, int l, int m, int n) {
		super(i, string, textureFormat, j, k, l, m);
		this.id = n;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			if (this.views == 0) {
				this.destroyImmediately();
			}
		}
	}

	private void destroyImmediately() {
		GlStateManager._deleteTexture(this.id);
		if (this.firstFboId != -1) {
			GlStateManager._glDeleteFramebuffers(this.firstFboId);
		}

		if (this.fboCache != null) {
			IntIterator var1 = this.fboCache.values().iterator();

			while (var1.hasNext()) {
				int i = (Integer)var1.next();
				GlStateManager._glDeleteFramebuffers(i);
			}
		}
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	public int getFbo(DirectStateAccess directStateAccess, @Nullable GpuTexture gpuTexture) {
		int i = gpuTexture == null ? 0 : ((GlTexture)gpuTexture).id;
		if (this.firstFboDepthId == i) {
			return this.firstFboId;
		} else if (this.firstFboId == -1) {
			this.firstFboId = this.createFbo(directStateAccess, i);
			this.firstFboDepthId = i;
			return this.firstFboId;
		} else {
			if (this.fboCache == null) {
				this.fboCache = new Int2IntArrayMap();
			}

			return this.fboCache.computeIfAbsent(i, (Int2IntFunction)(ix -> this.createFbo(directStateAccess, ix)));
		}
	}

	private int createFbo(DirectStateAccess directStateAccess, int i) {
		int j = directStateAccess.createFrameBufferObject();
		directStateAccess.bindFrameBufferTextures(j, this.id, i, 0, 0);
		return j;
	}

	public int glId() {
		return this.id;
	}

	public void addViews() {
		this.views++;
	}

	public void removeViews() {
		this.views--;
		if (this.closed && this.views == 0) {
			this.destroyImmediately();
		}
	}
}
