package com.whale.pingpong;

import com.whale.pingpong.entity.ModEntities;
import com.whale.pingpong.item.ModItems;
import com.whale.pingpong.net.ModNetworking;
import net.fabricmc.api.ModInitializer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 乒乓球 Mod 主入口（两端共用）。
 *
 * 设计要点：
 * 1. 所有飞行 / 弹跳物理都在【服务端】计算，客户端只接收结果并插值渲染；
 * 2. 玩家操作（挥拍、拍面角度）通过 C2S 包发给服务端，由服务端做命中判定；
 * 3. 自旋通过实体 TrackedData 同步，速度变化通过自定义 S2C 包同步。
 */
public class PingPongMod implements ModInitializer {
	public static final String MOD_ID = "pingpong";
	public static final Logger LOGGER = LoggerFactory.getLogger("PingPong");

	/** 统一构造 Identifier，避免到处写字符串。 */
	public static Identifier id(String path) {
		return new Identifier(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		// 顺序有讲究：物品先注册（实体默认参数里可能引用物品），再注册实体，最后网络。
		ModItems.register();
		ModEntities.register();
		ModNetworking.registerCommon();

		LOGGER.info("[pingpong] 乒乓球 Mod 已加载：服务端权威物理 + 马格努斯效应");
	}
}
