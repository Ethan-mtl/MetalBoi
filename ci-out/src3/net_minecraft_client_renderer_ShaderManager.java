package net.minecraft.client.renderer;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.IdentifierException;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.FileUtil;
import net.minecraft.util.StrictJsonParser;
import net.minecraft.util.profiling.ProfilerFiller;
import org.apache.commons.io.IOUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

@Environment(EnvType.CLIENT)
public class ShaderManager extends SimplePreparableReloadListener<ShaderManager.Configs> implements AutoCloseable {
	static final Logger LOGGER = LogUtils.getLogger();
	public static final int MAX_LOG_LENGTH = 32768;
	public static final String SHADER_PATH = "shaders";
	private static final String SHADER_INCLUDE_PATH = "shaders/include/";
	private static final FileToIdConverter POST_CHAIN_ID_CONVERTER = FileToIdConverter.json("post_effect");
	final TextureManager textureManager;
	private final Consumer<Exception> recoveryHandler;
	private ShaderManager.CompilationCache compilationCache = new ShaderManager.CompilationCache(ShaderManager.Configs.EMPTY);
	final CachedOrthoProjectionMatrixBuffer postChainProjectionMatrixBuffer = new CachedOrthoProjectionMatrixBuffer("post", 0.1F, 1000.0F, false);

	public ShaderManager(TextureManager textureManager, Consumer<Exception> consumer) {
		this.textureManager = textureManager;
		this.recoveryHandler = consumer;
	}

	protected ShaderManager.Configs prepare(ResourceManager resourceManager, ProfilerFiller profilerFiller) {
		Builder<ShaderManager.ShaderSourceKey, String> builder = ImmutableMap.builder();
		Map<Identifier, Resource> map = resourceManager.listResources("shaders", ShaderManager::isShader);

		for (Entry<Identifier, Resource> entry : map.entrySet()) {
			Identifier identifier = (Identifier)entry.getKey();
			ShaderType shaderType = ShaderType.byLocation(identifier);
			if (shaderType != null) {
				loadShader(identifier, (Resource)entry.getValue(), shaderType, map, builder);
			}
		}

		Builder<Identifier, PostChainConfig> builder2 = ImmutableMap.builder();

		for (Entry<Identifier, Resource> entry2 : POST_CHAIN_ID_CONVERTER.listMatchingResources(resourceManager).entrySet()) {
			loadPostChain((Identifier)entry2.getKey(), (Resource)entry2.getValue(), builder2);
		}

		return new ShaderManager.Configs(builder.build(), builder2.build());
	}

	private static void loadShader(
		Identifier identifier, Resource resource, ShaderType shaderType, Map<Identifier, Resource> map, Builder<ShaderManager.ShaderSourceKey, String> builder
	) {
		Identifier identifier2 = shaderType.idConverter().fileToId(identifier);
		GlslPreprocessor glslPreprocessor = createPreprocessor(map, identifier);

		try {
			Reader reader = resource.openAsReader();

			try {
				String string = IOUtils.toString(reader);
				builder.put(new ShaderManager.ShaderSourceKey(identifier2, shaderType), String.join("", glslPreprocessor.process(string)));
			} catch (Throwable var11) {
				if (reader != null) {
					try {
						reader.close();
					} catch (Throwable var10) {
						var11.addSuppressed(var10);
					}
				}

				throw var11;
			}

			if (reader != null) {
				reader.close();
			}
		} catch (IOException var12) {
			LOGGER.error("Failed to load shader source at {}", identifier, var12);
		}
	}

	private static GlslPreprocessor createPreprocessor(Map<Identifier, Resource> map, Identifier identifier) {
		final Identifier identifier2 = identifier.withPath(FileUtil::getFullResourcePath);
		return new GlslPreprocessor() {
			private final Set<Identifier> importedLocations = new ObjectArraySet<>();

			@Nullable
			@Override
			public String applyImport(boolean bl, String string) {
				Identifier identifierx;
				try {
					if (bl) {
						identifierx = identifier2.withPath((UnaryOperator<String>)(string2 -> FileUtil.normalizeResourcePath(string2 + string)));
					} else {
						identifierx = Identifier.parse(string).withPrefix("shaders/include/");
					}
				} catch (IdentifierException var8) {
					ShaderManager.LOGGER.error("Malformed GLSL import {}: {}", string, var8.getMessage());
					return "#error " + var8.getMessage();
				}

				if (!this.importedLocations.add(identifierx)) {
					return null;
				} else {
					try {
						Reader reader = ((Resource)map.get(identifierx)).openAsReader();

						String var5;
						try {
							var5 = IOUtils.toString(reader);
						} catch (Throwable var9) {
							if (reader != null) {
								try {
									reader.close();
								} catch (Throwable var7) {
									var9.addSuppressed(var7);
								}
							}

							throw var9;
						}

						if (reader != null) {
							reader.close();
						}

						return var5;
					} catch (IOException var10) {
						ShaderManager.LOGGER.error("Could not open GLSL import {}: {}", identifierx, var10.getMessage());
						return "#error " + var10.getMessage();
					}
				}
			}
		};
	}

	private static void loadPostChain(Identifier identifier, Resource resource, Builder<Identifier, PostChainConfig> builder) {
		Identifier identifier2 = POST_CHAIN_ID_CONVERTER.fileToId(identifier);

		try {
			Reader reader = resource.openAsReader();

			try {
				JsonElement jsonElement = StrictJsonParser.parse(reader);
				builder.put(identifier2, PostChainConfig.CODEC.parse(JsonOps.INSTANCE, jsonElement).getOrThrow(JsonSyntaxException::new));
			} catch (Throwable var8) {
				if (reader != null) {
					try {
						reader.close();
					} catch (Throwable var7) {
						var8.addSuppressed(var7);
					}
				}

				throw var8;
			}

			if (reader != null) {
				reader.close();
			}
		} catch (JsonParseException | IOException var9) {
			LOGGER.error("Failed to parse post chain at {}", identifier, var9);
		}
	}

	private static boolean isShader(Identifier identifier) {
		return ShaderType.byLocation(identifier) != null || identifier.getPath().endsWith(".glsl");
	}

	protected void apply(ShaderManager.Configs configs, ResourceManager resourceManager, ProfilerFiller profilerFiller) {
		ShaderManager.CompilationCache compilationCache = new ShaderManager.CompilationCache(configs);
		Set<RenderPipeline> set = new HashSet(RenderPipelines.getStaticPipelines());
		List<Identifier> list = new ArrayList();
		GpuDevice gpuDevice = RenderSystem.getDevice();
		gpuDevice.clearPipelineCache();

		for (RenderPipeline renderPipeline : set) {
			CompiledRenderPipeline compiledRenderPipeline = gpuDevice.precompilePipeline(renderPipeline, compilationCache::getShaderSource);
			if (!compiledRenderPipeline.isValid()) {
				list.add(renderPipeline.getLocation());
			}
		}

		if (!list.isEmpty()) {
			gpuDevice.clearPipelineCache();
			throw new RuntimeException(
				"Failed to load required shader programs:\n" + (String)list.stream().map(identifier -> " - " + identifier).collect(Collectors.joining("\n"))
			);
		} else {
			this.compilationCache.close();
			this.compilationCache = compilationCache;
		}
	}

	@Override
	public String getName() {
		return "Shader Loader";
	}

	private void tryTriggerRecovery(Exception exception) {
		if (!this.compilationCache.triggeredRecovery) {
			this.recoveryHandler.accept(exception);
			this.compilationCache.triggeredRecovery = true;
		}
	}

	@Nullable
	public PostChain getPostChain(Identifier identifier, Set<Identifier> set) {
		try {
			return this.compilationCache.getOrLoadPostChain(identifier, set);
		} catch (ShaderManager.CompilationException var4) {
			LOGGER.error("Failed to load post chain: {}", identifier, var4);
			this.compilationCache.postChains.put(identifier, Optional.empty());
			this.tryTriggerRecovery(var4);
			return null;
		}
	}

	public void close() {
		this.compilationCache.close();
		this.postChainProjectionMatrixBuffer.close();
	}

	@Nullable
	public String getShader(Identifier identifier, ShaderType shaderType) {
		return this.compilationCache.getShaderSource(identifier, shaderType);
	}

	@Environment(EnvType.CLIENT)
	class CompilationCache implements AutoCloseable {
		private final ShaderManager.Configs configs;
		final Map<Identifier, Optional<PostChain>> postChains = new HashMap();
		boolean triggeredRecovery;

		CompilationCache(final ShaderManager.Configs configs) {
			this.configs = configs;
		}

		@Nullable
		public PostChain getOrLoadPostChain(Identifier identifier, Set<Identifier> set) throws ShaderManager.CompilationException {
			Optional<PostChain> optional = (Optional<PostChain>)this.postChains.get(identifier);
			if (optional != null) {
				return (PostChain)optional.orElse(null);
			} else {
				PostChain postChain = this.loadPostChain(identifier, set);
				this.postChains.put(identifier, Optional.of(postChain));
				return postChain;
			}
		}

		private PostChain loadPostChain(Identifier identifier, Set<Identifier> set) throws ShaderManager.CompilationException {
			PostChainConfig postChainConfig = (PostChainConfig)this.configs.postChains.get(identifier);
			if (postChainConfig == null) {
				throw new ShaderManager.CompilationException("Could not find post chain with id: " + identifier);
			} else {
				return PostChain.load(postChainConfig, ShaderManager.this.textureManager, set, identifier, ShaderManager.this.postChainProjectionMatrixBuffer);
			}
		}

		public void close() {
			this.postChains.values().forEach(optional -> optional.ifPresent(PostChain::close));
			this.postChains.clear();
		}

		@Nullable
		public String getShaderSource(Identifier identifier, ShaderType shaderType) {
			return (String)this.configs.shaderSources.get(new ShaderManager.ShaderSourceKey(identifier, shaderType));
		}
	}

	@Environment(EnvType.CLIENT)
	public static class CompilationException extends Exception {
		public CompilationException(String string) {
			super(string);
		}
	}

	@Environment(EnvType.CLIENT)
	public record Configs(Map<ShaderManager.ShaderSourceKey, String> shaderSources, Map<Identifier, PostChainConfig> postChains) {
		public static final ShaderManager.Configs EMPTY = new ShaderManager.Configs(Map.of(), Map.of());
	}

	@Environment(EnvType.CLIENT)
	record ShaderSourceKey(Identifier id, ShaderType type) {
		public String toString() {
			return this.id + " (" + this.type + ")";
		}
	}
}
