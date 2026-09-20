package com.whale.pingpong.client;

import com.whale.pingpong.item.ModItems;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 手持球拍时在准星下方显示拍面状态。
 * 只做提示，不影响任何逻辑。
 */
public final class PingPongHud {

	private static final int BAR_WIDTH = 60;
	private static final int BAR_HEIGHT = 4;

	private PingPongHud() {
	}

	public static void register() {
		HudRenderCallback.EVENT.register(PingPongHud::onHudRender);
	}

	private static void onHudRender(DrawContext context, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.options.hudHidden) {
			return;
		}
		// 只有手持球拍时才显示
		if (!client.player.getMainHandStack().isOf(ModItems.PINGPONG_PADDLE)) {
			return;
		}

		int screenWidth = client.getWindow().getScaledWidth();
		int screenHeight = client.getWindow().getScaledHeight();
		int centerX = screenWidth / 2;
		int baseY = screenHeight - 78;

		double tilt = PingPongClientState.tilt();
		double side = PingPongClientState.sideTilt();

		Text tiltLabel = Math.abs(tilt) < 0.05
				? Text.translatable("hud.pingpong.flat")
				: (tilt > 0 ? Text.translatable("hud.pingpong.topspin") : Text.translatable("hud.pingpong.backspin"));
		Text sideLabel = Math.abs(side) < 0.05
				? Text.translatable("hud.pingpong.center")
				: (side > 0 ? Text.translatable("hud.pingpong.right") : Text.translatable("hud.pingpong.left"));

		Text line = Text.translatable("hud.pingpong.status",
				tiltLabel.copy().formatted(Formatting.AQUA),
				String.format("%d%%", (int) Math.round(Math.abs(tilt) * 100)),
				sideLabel.copy().formatted(Formatting.LIGHT_PURPLE),
				String.format("%d%%", (int) Math.round(Math.abs(side) * 100)));

		context.drawCenteredTextWithShadow(client.textRenderer, line, centerX, baseY, 0xFFFFFF);
		context.drawCenteredTextWithShadow(client.textRenderer,
				Text.translatable("hud.pingpong.hint").formatted(Formatting.DARK_GRAY),
				centerX, baseY + 11, 0xAAAAAA);

		// 两根小条：俯仰 / 侧偏
		int barY = baseY + 24;
		drawSlider(context, centerX - BAR_WIDTH - 4, barY, tilt);
		drawSlider(context, centerX + 4, barY, side);
	}

	/** 以中点为 0 的滑块，正数向右。 */
	private static void drawSlider(DrawContext context, int x, int y, double value) {
		context.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0x80000000);
		int mid = x + BAR_WIDTH / 2;
		context.fill(mid, y, mid + 1, y + BAR_HEIGHT, 0xFFAAAAAA);

		int offset = (int) Math.round(value * (BAR_WIDTH / 2.0 - 1));
		if (offset >= 0) {
			context.fill(mid, y, mid + offset, y + BAR_HEIGHT, 0xFF55FFFF);
		} else {
			context.fill(mid + offset, y, mid, y + BAR_HEIGHT, 0xFFFF55FF);
		}
	}
}
